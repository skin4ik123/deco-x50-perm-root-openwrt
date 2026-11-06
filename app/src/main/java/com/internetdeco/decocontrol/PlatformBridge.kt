package com.internetdeco.decocontrol
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Base64
import com.jcraft.jsch.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class PlatformBridge(val context:Context,val vault:SecureVault) {
 private class ReadRequest(val host:String,val key:String){@Volatile var cancelled=false;val closers=mutableSetOf<()->Unit>()}
 private val request=ThreadLocal<ReadRequest?>()
 private val reads=ConcurrentHashMap.newKeySet<ReadRequest>()
 fun <T> reading(host:String,key:String=host,body:()->T):T {val r=ReadRequest(host,key);reads.add(r);request.set(r);try{return body()}finally{request.remove();reads.remove(r)}}
 fun cancelReads(host:String){reads.filter{it.host==host}.forEach{r->synchronized(r){r.cancelled=true;r.closers.toList().forEach{runCatching(it)};r.closers.clear()}}}
 fun cancelRead(key:String){reads.filter{it.key==key}.forEach{r->synchronized(r){r.cancelled=true;r.closers.toList().forEach{runCatching(it)};r.closers.clear()}}}
 private fun track(close:()->Unit):()->Unit {val r=request.get()?:return {};synchronized(r){if(r.cancelled){close();throw IOException("Request cancelled after switching devices")};r.closers.add(close)};return {synchronized(r){r.closers.remove(close)}}}
 private fun checkCancelled(){if(request.get()?.cancelled==true)throw IOException("Request cancelled after switching devices")}
 private val deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
 private val sessions=ConcurrentHashMap<String,Session>()
 private val ramSessions=RamSessionPool<Session>{it.disconnect()}
 private val ramCandidatePins=ConcurrentHashMap<String,String>()
 private val ramVerification=ThreadLocal<Pair<RamEndpoint,Session>?>()
 private val owners=ConcurrentHashMap<String,Pair<Session,Int>>()
 private val sessionLocks=ConcurrentHashMap<String,Any>()
 private val passwords=ConcurrentHashMap<String,String>()
 private val modes=ConcurrentHashMap<String,String>()
 private val heartbeat=ConcurrentHashMap<String,Long>()
 private val connectionTimes=ConcurrentHashMap<String,JSONObject>()
 private data class LoginCredential(val password:String,val remember:Boolean)
 private val loginCredentials=ConcurrentHashMap<String,LoginCredential>()
 private class Connecting(val host:String,val port:Int,val options:ConnectionOptions,val profileKey:String=host){
  @Volatile var cancelled=false
  @Volatile var socket:java.net.Socket?=null
  @Volatile var session:Session?=null
  var state=ConnectionState(host,port,attempts=options.attempts,profileKey=profileKey)
 }
 private val connecting=ConcurrentHashMap<String,Connecting>()
 fun loginCredential(host:String,password:String,remember:Boolean){loginCredentials[host]=LoginCredential(password,remember)}
 fun discardLoginCredential(host:String){loginCredentials.remove(host)}
 fun cancelConnection(host:String){loginCredentials.remove(host);connecting.values.filter{it.profileKey==host}.forEach{it.cancelled=true;runCatching{it.socket?.close()};runCatching{it.session?.disconnect()}}}
 private fun phase(job:Connecting,value:ConnectionPhase,detail:String?=null,retryAt:Long=0){job.state=job.state.copy(phase=value,detail=detail,retryAt=retryAt);Controller.connectionChanged(job.state)}
 private fun check(job:Connecting){checkCancelled();if(job.cancelled)throw IOException("Request cancelled")}
 init {if(Security.getProvider("BC") !is BouncyCastleProvider){Security.removeProvider("BC");Security.addProvider(BouncyCastleProvider())}}
 fun connected(host:String)=if(host.startsWith("ram:"))ramSessions.terminalSession(host)?.isConnected==true else sessions[host]?.isConnected==true
 fun sessionPort(host:String)=if(modes[host]=="temporary")20003 else 22
 fun installPassword(host:String,password:String){passwords[host]=password}
 fun clearInstallPassword(host:String){passwords.remove(host)}
 private fun encoded(data:ByteArray)=Base64.encodeToString(data,Base64.NO_WRAP)
 private fun decode(s:String)=Base64.decode(s,Base64.DEFAULT)
 private fun fingerprint(data:ByteArray)="SHA256:"+Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(data),Base64.NO_WRAP or Base64.NO_PADDING)
 fun networkCheck(){
  val cm=context.getSystemService(ConnectivityManager::class.java)
  val available=cm.allNetworks.firstOrNull{n->cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}
  require(available!=null){"Connect your phone to the Deco Wi-Fi network"}
  // Route this process to the local Wi-Fi even if Android prefers mobile data
  // because the AP has no Internet validation. This does not alter the router.
  require(cm.bindProcessToNetwork(available)){"Could not select the local Wi-Fi route"}
 }
 private fun client(host:String,port:Int,exactKey:String?=null,pinScope:String?=null,profileKey:String=host,waitingForKey:()->Unit={},verified:()->Unit={}):JSch {
  val jsch=JSch()
  jsch.setHostKeyRepository(object:HostKeyRepository {
   override fun check(ignored:String?,key:ByteArray):Int {
    if(!exactKey.isNullOrBlank()) {
     val split=exactKey.trim().split(Regex("\\s+"));if(split.size>=2&&MessageDigest.isEqual(key,decode(split[1]))){vault.put("pin:$host:$port",fingerprint(key));verified();return HostKeyRepository.OK};return HostKeyRepository.CHANGED
    }
    val name=if(pinScope==null)"pin:$host:$port" else "$pinScope:$host:$port";val fp=fingerprint(key);val previous=pinScope?.let{ramCandidatePins[it]}?:vault.get(name)
    if(previous.isNotBlank())return if(previous==fp){verified();HostKeyRepository.OK}else HostKeyRepository.CHANGED
    waitingForKey();val waiting=android.os.SystemClock.elapsedRealtime();val approved=Controller.trust(host,port,fp);connectionTimes["$profileKey:$port"]?.put("key_confirmation_ms",android.os.SystemClock.elapsedRealtime()-waiting);if(!approved)return HostKeyRepository.CHANGED
    if(pinScope==null)vault.put(name,fp)else ramCandidatePins[pinScope]=fp;verified();return HostKeyRepository.OK
   }
   override fun add(key:HostKey?,ui:UserInfo?)=Unit
   override fun remove(host:String?,type:String?)=Unit
   override fun remove(host:String?,type:String?,key:ByteArray?)=Unit
   override fun getKnownHostsRepositoryID()="Android Keystore"
   override fun getHostKey():Array<HostKey> = emptyArray()
   override fun getHostKey(host:String?,type:String?):Array<HostKey> = emptyArray()
  })
  return jsch
 }
 private fun passwordSession(host:String,port:Int,user:String,password:String,exact:String?=null,allowEmpty:Boolean=false,pinScope:String?=null,profileKey:String=host):Session {
  require(password.isNotEmpty()||allowEmpty){"Enter the password for this Deco"}
  val options=Controller.connectionOptions();val job=Connecting(host,port,options,profileKey);val key="$profileKey:$port";connecting[key]=job
  try {
   return ConnectionRetry.establish(options,checkCancelled={check(job)},onAttempt={number->
    job.state=job.state.copy(attempt=number);phase(job,ConnectionPhase.ROUTING)
   },onRetry={next,wait,error->
    job.state=job.state.copy(attempt=next);phase(job,ConnectionPhase.RETRY_WAIT,error.message,System.currentTimeMillis()+wait)
   }) { passwordAttempt(host,port,user,password,exact,job,options,pinScope) }.also{phase(job,ConnectionPhase.CONNECTED)}
  }catch(e:Exception){phase(job,if(job.cancelled||request.get()?.cancelled==true)ConnectionPhase.CANCELLED else ConnectionPhase.FAILED,e.message);throw e}
  finally{connecting.remove(key,job)}
 }
 private fun passwordAttempt(host:String,port:Int,user:String,password:String,exact:String?,job:Connecting,options:ConnectionOptions,pinScope:String?=null):Session {
  val started=android.os.SystemClock.elapsedRealtime();val times=JSONObject().put("port",port).put("observed_at",System.currentTimeMillis());connectionTimes["${job.profileKey}:$port"]=times
  var connectedAt=started;var bannerAt=0L;var untrack:()->Unit={};var stage="tcp_connection"

  networkCheck();check(job);phase(job,ConnectionPhase.CONNECTING)
  val s=client(host,port,exact,pinScope=pinScope,profileKey=job.profileKey,waitingForKey={phase(job,ConnectionPhase.VERIFYING_KEY)}){stage="authentication";phase(job,ConnectionPhase.AUTHENTICATING)}.getSession(user,host,port)
  job.session=s
  SshPolicy.configure(s,port==20001)
  s.setSocketFactory(object:com.jcraft.jsch.SocketFactory {
   override fun createSocket(h:String,p:Int):java.net.Socket {
    val socket=java.net.Socket();job.socket=socket;untrack=track{socket.close()};socket.tcpNoDelay=true;socket.keepAlive=true
    check(job);socket.connect(InetSocketAddress(h,p),options.tcpTimeoutMs);connectedAt=android.os.SystemClock.elapsedRealtime();times.put("tcp_ms",connectedAt-started);times.put("tcp_ok",true);stage="ssh_banner";phase(job,ConnectionPhase.BANNER);Controller.event(host,"$host:$port · TCP connected; waiting for SSH banner")
    return socket
   }
   override fun getInputStream(socket:java.net.Socket):InputStream=object:FilterInputStream(socket.getInputStream()){
    private val first=ByteArrayOutputStream();private var done=false
    override fun read():Int{val n=super.read();if(n>=0)observe(byteArrayOf(n.toByte()),0,1);return n}
    override fun read(b:ByteArray,off:Int,len:Int):Int{val n=`in`.read(b,off,len);if(n>0)observe(b,off,n);return n}
    private fun observe(b:ByteArray,offset:Int,count:Int){if(done)return;for(i in offset until offset+count){val value=b[i].toInt() and 255;if(first.size()<512)first.write(value);if(value==10){val line=first.toString("US-ASCII");if(line.startsWith("SSH-")){stage="key_exchange";phase(job,ConnectionPhase.KEY_EXCHANGE);bannerAt=android.os.SystemClock.elapsedRealtime();times.put("banner_ms",bannerAt-connectedAt);times.put("banner_ok",true);done=true;Controller.event(host,"SSH banner received; key exchange and sign-in");return};first.reset()}}}
   }
   override fun getOutputStream(socket:java.net.Socket)=socket.getOutputStream()
  })
  s.setPassword(password.toByteArray());s.timeout=options.handshakeTimeoutMs;s.setServerAliveInterval(20000);s.setServerAliveCountMax(3)
  try{s.connect(options.handshakeTimeoutMs);check(job);s.timeout=maxOf(options.commandTimeoutMs,120_000);times.put("total_ms",android.os.SystemClock.elapsedRealtime()-started);times.put("key_exchange_and_auth_ms",android.os.SystemClock.elapsedRealtime()-(if(bannerAt>0)bannerAt else connectedAt)-times.optLong("key_confirmation_ms",0));times.put("authenticated",true)}catch(e:Exception){times.put("failed",true).put("failed_stage",stage);times.put("total_ms",android.os.SystemClock.elapsedRealtime()-started);s.disconnect();check(job);val failure=connectionFailure(host,port,stage,e,password);times.put("error_code",failure.code).put("error",failure.message);throw failure}finally{untrack();job.socket=null;job.session=null}
  s.setPassword(null as ByteArray?);return s
 }
 fun connect(host:String):Session=synchronized(sessionLocks.getOrPut(host){Any()}) {
  val entered=loginCredentials[host]
  sessions[host]?.takeIf{it.isConnected&&entered==null}?.let{s->
   if(System.currentTimeMillis()-(heartbeat[host]?:0)>30_000&&!TerminalHub.isOpen(host)){
    if(!sessionAlive(s)){sessions.remove(host,s);s.disconnect()}else{heartbeat[host]=System.currentTimeMillis();return@synchronized s}
   }else return@synchronized s
  }
  val candidates=(if(entered!=null)listOf(entered.password)else listOf(vault.get("pending-root:$host"),vault.get("root:$host"))).filter{it.isNotBlank()}.distinct()
  var last:Exception?=null
  try{for(password in candidates){try{val s=passwordSession(host,22,"root",password);sessions.put(host,s)?.disconnect();modes[host]="password";heartbeat[host]=System.currentTimeMillis();
   if(entered?.remember==true){try{vault.put("root:$host",password);Controller.credentialsChanged(host)}catch(e:Exception){Controller.toast("Connected, but the password could not be saved. Unlock your phone and try again.")}}
   return@synchronized s
  }catch(e:Exception){last=e;if(e is ConnectionFailure&&e.code=="host_key")throw e;if(e !is ConnectionFailure||e.code!="authentication")break}}}finally{if(entered!=null)loginCredentials.remove(host,entered)}
  val state=File(context.filesDir,"nodes/$host/bootstrap.json").takeIf{it.isFile}?.let{runCatching{JSONObject(it.readText())}.getOrNull()}
  if(entered==null&&state?.optString("phase")=="ready"&&state.optString("public_key").isNotBlank())return@synchronized temporary(host,state.getString("public_key"))
  throw last?:IOException("Enter the password for this Deco")

 }
 private fun sessionAlive(s:Session):Boolean {
  var channel:ChannelExec?=null
  return try{channel=s.openChannel("exec")as ChannelExec;channel.setCommand(":");channel.setInputStream(null);val out=channel.inputStream;channel.connect(10_000);val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
   while(!channel.isClosed&&System.nanoTime()<end){checkCancelled();while(out.available()>0)out.read();Thread.sleep(20)}
   channel.isClosed&&channel.exitStatus==0
  }catch(e:Exception){checkCancelled();false}finally{channel?.disconnect()}
 }
 private fun owner(host:String):Int=synchronized(owners) {
  owners[host]?.takeIf{it.first.isConnected}?.let{return@synchronized it.second}
  val owner=vault.get("owner_id").trim();require(owner.isNotBlank()){"Enter the TP-Link owner email or SSH username"}
  val user=if(owner.matches(Regex("[a-fA-F0-9]{40}")))owner.lowercase() else MessageDigest.getInstance("SHA-1").digest(owner.toByteArray()).joinToString(""){"%02x".format(it)}
  val s=passwordSession(host,20001,user,vault.get("owner_password"));val port=try{s.setPortForwardingL("127.0.0.1",0,"127.0.0.1",20002)}catch(e:Exception){s.disconnect();throw connectionFailure(host,20001,"authenticated_file_channel",e)}
  owners.put(host,s to port)?.first?.disconnect();port
 }
 private fun publicKey():String=synchronized(vault) {
  vault.get("client_public").takeIf{it.isNotBlank()}?.let{return@synchronized it}
  val pair=SshClientKeys.generate()
  try{vault.update{it.put("client_private",encoded(pair.privatePem));it.put("client_public",pair.publicKey)};pair.publicKey}finally{pair.privatePem.fill(0)}
 }
 private fun temporary(host:String,key:String):Session=synchronized(sessionLocks.getOrPut(host){Any()}) {
  val options=Controller.connectionOptions();val job=Connecting(host,20003,options);val endpoint="$host:20003";connecting[endpoint]=job
  try{
   val session=ConnectionRetry.establish(options,checkCancelled={check(job)},onAttempt={n->job.state=job.state.copy(attempt=n);phase(job,ConnectionPhase.CONNECTING)},onRetry={next,wait,error->job.state=job.state.copy(attempt=next);phase(job,ConnectionPhase.RETRY_WAIT,error.message,System.currentTimeMillis()+wait)}){
    networkCheck();publicKey();val jsch=client(host,20003,key,verified={phase(job,ConnectionPhase.AUTHENTICATING)});val bytes=decode(vault.get("client_private"))
    try{jsch.addIdentity("deco-control",bytes,null,null)}finally{bytes.fill(0)}
    val s=jsch.getSession("root",host,20003);job.session=s;s.setConfig("StrictHostKeyChecking","yes");s.setConfig("PreferredAuthentications","publickey");s.timeout=options.handshakeTimeoutMs;s.setServerAliveInterval(20000);s.setServerAliveCountMax(3)
    val stop=track{s.disconnect()}
    try{s.connect(options.handshakeTimeoutMs);check(job);s}catch(e:Exception){s.disconnect();check(job);throw connectionFailure(host,20003,"temporary_root_ssh",e)}finally{stop();job.session=null}
   }
   sessions.put(host,session)?.disconnect();modes[host]="temporary";heartbeat[host]=System.currentTimeMillis();phase(job,ConnectionPhase.CONNECTED);session
  }catch(e:Exception){phase(job,if(job.cancelled||request.get()?.cancelled==true)ConnectionPhase.CANCELLED else ConnectionPhase.FAILED,e.message);throw e}finally{connecting.remove(endpoint,job)}
 }
 private fun exec(host:String,command:String,input:ByteArray,limit:Int,provided:Session?=null):JSONObject {
  checkCancelled();require(limit in 1..(2*1024*1024)){"SSH response limit"};val s=provided?:connect(host)
  val c=s.openChannel("exec") as ChannelExec;c.setCommand(command);c.setInputStream(ByteArrayInputStream(input));c.setErrStream(object:OutputStream(){var count=0;override fun write(b:Int){if(++count>65536)throw IOException("SSH stderr exceeded its size limit")}})
  val options=Controller.connectionOptions();val stream=c.inputStream;val out=ByteArrayOutputStream();val untrack=track{c.disconnect()};try{c.connect(options.channelTimeoutMs);val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(if(request.get()!=null)options.commandTimeoutMs.toLong()else maxOf(options.commandTimeoutMs,120_000).toLong());val buffer=ByteArray(8192)
   while(true){checkCancelled();while(stream.available()>0){val n=stream.read(buffer,0,minOf(buffer.size,stream.available()));if(n<0)break;check(out.size()+n<=limit){"Deco response exceeded the allowed size"};out.write(buffer,0,n)};if(c.isClosed&&stream.available()==0)break;if(System.nanoTime()>end)throw IOException("Request timed out. Check the command result");Thread.sleep(20)}
   heartbeat[host]=System.currentTimeMillis();return JSONObject().put("data",encoded(out.toByteArray())).put("exit",c.exitStatus)
  }catch(e:Exception){if(request.get()?.cancelled!=true&&(!s.isConnected||e is java.net.SocketException)){if(sessions.remove(host,s)){s.disconnect();Controller.connectionChanged(ConnectionState(host,phase=ConnectionPhase.FAILED,detail=e.message))}};throw e}finally{untrack();c.disconnect()}
 }
 private fun https(host:String,certificate:ByteArray,path:String):JSONObject {
  require(path.startsWith("/cgi-bin/luci/;stok=/login?form=keys&operation=read")){"Unsupported HTTPS request"};networkCheck()
  val expected=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
  val tm=object:X509TrustManager {override fun getAcceptedIssuers()=arrayOf(expected);override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?) {throw java.security.cert.CertificateException("Not a TLS server")};override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?){if(c.isNullOrEmpty()||!MessageDigest.isEqual(c[0].encoded,expected.encoded))throw java.security.cert.CertificateException("Deco certificate did not match its authenticated copy")}}
  val ssl=SSLContext.getInstance("TLS").apply{init(null,arrayOf(tm),SecureRandom())}
  val socket=ssl.socketFactory.createSocket() as SSLSocket
  try{socket.connect(InetSocketAddress(host,443),30000);socket.soTimeout=45000;socket.startHandshake();socket.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray());socket.outputStream.flush()
   val out=ByteArrayOutputStream();val buffer=ByteArray(4096);while(true){val n=socket.inputStream.read(buffer);if(n<0)break;require(out.size()+n<262144){"HTTPS response exceeded the size limit"};out.write(buffer,0,n)}
   val raw=out.toByteArray();val head=String(raw,Charsets.ISO_8859_1).substringBefore("\r\n\r\n");val status=head.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull()?:0
   return JSONObject().put("status",status)
  }finally{socket.close()}
 }
 private fun remoteQuote(value:String)="'"+value.replace("'","'\\''")+"'"
 private fun withinFiles(path:String):File {val f=File(path).canonicalFile;require(f.path.startsWith(context.filesDir.canonicalPath+File.separator)){"Path is outside app storage"};return f}
 private fun download(v:JSONObject,nand:Boolean):JSONObject {
  val host=v.getString("host");val destination=withinFiles(v.getString("path"));destination.parentFile?.mkdirs()
  require(!destination.exists()){"File already exists"};val max=if(nand)v.getLong("bytes") else v.optLong("max",64*1024*1024L)
  require(max in 1..(64*1024*1024L)){"File exceeds the size limit"};require(StatFs(context.filesDir.path).availableBytes>max+32*1024*1024L){"Not enough free space on the phone"}
  val partial=File(destination.path+".partial");require(!partial.exists()){"A partial copy already exists"}
  val c=connect(host).openChannel("exec") as ChannelExec;c.setCommand(v.getString("command"));c.setInputStream(null);c.setErrStream(ByteArrayOutputStream());val input=c.inputStream
  fun line():String{val b=ByteArrayOutputStream();while(b.size()<8192){val x=input.read();if(x<0)throw EOFException("Incomplete NAND receipt");if(x==10)return b.toString("UTF-8");b.write(x)};throw IOException("Receipt exceeded the size limit")}
  val digest=MessageDigest.getInstance("SHA-256");var size=0L;var footer=JSONObject()
  val untrack=track{c.disconnect()};val deadline=deadlines.schedule({c.disconnect()},10,TimeUnit.MINUTES)
  try{c.connect(Controller.connectionOptions().channelTimeoutMs)
   if(nand){val h=JSONObject(line());require(h.optString("protocol")=="IDCNAND1"&&h.optString("nonce")==v.getString("nonce")&&h.optInt("index")==v.getInt("index")&&h.optLong("bytes")==max){"Initial NAND receipt did not match"}}
   FileOutputStream(partial).use{out->val buffer=ByteArray(32768);while(!nand||size<max){checkCancelled();val request=if(nand)minOf(buffer.size.toLong(),max-size).toInt() else buffer.size;val n=input.read(buffer,0,request);if(n<0)break;size+=n;require(size<=max){"File exceeded the size limit"};digest.update(buffer,0,n);out.write(buffer,0,n)};out.fd.sync()}
   val sha=digest.digest().joinToString(""){"%02x".format(it)}
   if(nand){require(size==max){"Incomplete NAND copy"};footer=JSONObject(line());require(footer.optString("protocol")=="IDCNAND1_END"&&footer.optString("nonce")==v.getString("nonce")&&footer.optInt("index")==v.getInt("index")&&footer.optLong("bytes")==max&&footer.optString("sha256")==sha&&footer.optBoolean("badblock_map_stable")&&footer.optLong("ecc_failed_after")==footer.optLong("ecc_failed_before")){"NAND checksum did not match"}}
   val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!c.isClosed){if(System.nanoTime()>end)throw IOException("Transfer completion is unconfirmed");Thread.sleep(20)};require(c.exitStatus==0){"Transfer did not complete"};require(partial.renameTo(destination)){"Could not save the copy"}
   return JSONObject().put("path",destination.path).put("bytes",size).put("sha256",sha).put("footer",footer)
  }finally{untrack();deadline.cancel(false);c.disconnect()}
 }
 private fun upload(v:JSONObject):JSONObject {
  val file=withinFiles(v.getString("path"));require(file.isFile&&file.length()<=64*1024*1024L){"Invalid source file size"};val remote=v.getString("remote");require(remote.startsWith("/tmp/")||remote.startsWith("/tp_data/user_data/")){"Directory not allowed"};require(!remote.contains("..")&&!remote.contains('\u0000')){"Invalid path"}
  val c=connect(v.getString("host")).openChannel("exec") as ChannelExec
  val sha=FileInputStream(file).use{input->val digest=MessageDigest.getInstance("SHA-1");val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)};digest.digest().joinToString(""){"%02x".format(it)}}
  c.setCommand(FileTransferPolicy.uploadCommand(remote,sha,java.util.UUID.randomUUID().toString().replace("-","")))
  val input=FileInputStream(file);c.setInputStream(input);val output=c.inputStream;try{c.connect(Controller.connectionOptions().channelTimeoutMs);val until=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);while(!c.isClosed){if(System.nanoTime()>until)throw IOException("Transfer is unconfirmed. Check the destination directory");while(output.available()>0)output.read();Thread.sleep(25)};require(c.exitStatus==0){"Upload failed or the destination already exists"};return JSONObject().put("uploaded",true).put("bytes",file.length())}finally{input.close();c.disconnect()}
 }
 private fun sshProbe(host:String):JSONObject {
  networkCheck();val started=android.os.SystemClock.elapsedRealtime();val out=JSONObject().put("port",22)
  val socket=java.net.Socket();try{socket.tcpNoDelay=true;socket.connect(InetSocketAddress(host,22),8000);val tcp=android.os.SystemClock.elapsedRealtime();out.put("tcp_ms",tcp-started).put("tcp_ok",true);socket.soTimeout=10000
   socket.outputStream.write("SSH-2.0-DecoControl-Diagnostics\r\n".toByteArray());socket.outputStream.flush();val line=ByteArrayOutputStream()
   while(line.size()<512){val byte=socket.inputStream.read();if(byte<0)break;line.write(byte);if(byte==10){val text=line.toString("US-ASCII");if(text.startsWith("SSH-")){out.put("banner_ms",android.os.SystemClock.elapsedRealtime()-tcp).put("banner_ok",true).put("banner",text.trim());break};line.reset()}}
  }catch(e:Exception){out.put("error",if(out.optBoolean("tcp_ok"))"The port is reachable but no SSH banner was received" else "TCP connection failed. Check Wi-Fi and device availability")}finally{socket.close()}
  connectionTimes["$host:22"]?.let{out.put("last_connection",it)};return out
 }
 private fun verifyRoot(host:String,password:String,key:String?=null){
  val fresh=passwordSession(host,22,"root",password,key)
  try{val proof=exec(host,"id -u",byteArrayOf(),1024,fresh);require(String(decode(proof.getString("data"))).trim()=="0"){"SSH did not confirm root"}
   if(sessions[host]?.isConnected==true&&modes[host]=="password")fresh.disconnect() else sessions.put(host,fresh)?.disconnect()
   modes[host]="password";heartbeat[host]=System.currentTimeMillis()
  }catch(e:Exception){fresh.disconnect();throw e}
 }
 private fun ramEndpoint(v:JSONObject):RamEndpoint {
  require(v.getString("host")==RamEndpoint.HOST){"Unexpected RAM address"}
  return RamEndpoint(v.getString("stock_host"),v.getString("operation_id"))
 }
 private fun connectRam(v:JSONObject):JSONObject {
  val endpoint=ramEndpoint(v)
  synchronized(sessionLocks.getOrPut(endpoint.terminalKey){Any()}){
   // Every explicit verification authenticates a fresh connection on the chosen Wi-Fi.
   TerminalHub.close(endpoint.terminalKey)
   ramVerification.remove()
   ramSessions.begin(endpoint.terminalKey)
   ramCandidatePins.remove(endpoint.pinScope)
   val session=passwordSession(RamEndpoint.HOST,22,"root","",allowEmpty=true,pinScope=endpoint.pinScope,profileKey=endpoint.terminalKey)
   ramSessions.stage(endpoint.terminalKey,session)
   ramVerification.set(endpoint to session)
  }
  return JSONObject()
 }
 private fun execRam(v:JSONObject):JSONObject {
  val endpoint=ramEndpoint(v)
  val active=ramVerification.get()
  val session=ramSessions.verificationSession(endpoint.terminalKey)?.takeIf{it.isConnected&&active!=null&&active.first==endpoint&&active.second===it}?:throw IOException("The RAM verification session changed; verify OpenWrt again")
  return exec(RamEndpoint.HOST,v.getString("command"),decode(v.optString("input","")),v.getInt("limit"),session)
 }
 fun confirmRamVerification(stockHost:String,operationId:String){
  val endpoint=RamEndpoint(stockHost,operationId)
  val active=ramVerification.get()?:throw IOException("No RAM verification session is active")
  require(active.first==endpoint&&active.second.isConnected){"RAM verification does not match this operation"}
  ramSessions.confirm(endpoint.terminalKey,active.second){
   ramCandidatePins[endpoint.pinScope]?.let{vault.put("${endpoint.pinScope}:${RamEndpoint.HOST}:22",it)}
  }
  ramCandidatePins.remove(endpoint.pinScope)
  ramVerification.remove()
 }
 fun discardRamVerification(stockHost:String,operationId:String){
  val endpoint=RamEndpoint(stockHost,operationId)
  ramSessions.discardUnverified(endpoint.terminalKey)
  ramCandidatePins.remove(endpoint.pinScope)
  ramVerification.remove()
 }
 fun closeRam(stockHost:String){
  TerminalHub.removeMatchingPrefix("ram:$stockHost:")
  ramSessions.closeMatching("ram:$stockHost:")
  ramCandidatePins.keys.filter{it.startsWith("ram-pin:$stockHost:")}.forEach{ramCandidatePins.remove(it)}
 }
 fun shell(host:String,columns:Int,rows:Int):ChannelShell {
  val session=if(host.startsWith("ram:")){
   RamEndpoint.fromTerminalKey(host)
   ramSessions.terminalSession(host)?.takeIf{it.isConnected}?:throw IOException("Reconnect to OpenWrt from the RAM boot screen")
  }else connect(host)
  val c=session.openChannel("shell") as ChannelShell;c.setPtyType("xterm-256color",columns,rows,0,0);c.setPty(true);return c
 }
 fun closeIdle(except:String){val now=System.currentTimeMillis();for((host,time)in heartbeat){if(host!=except&&now-time>300000&&!TerminalHub.isOpen(host)){sessions.remove(host)?.disconnect();heartbeat.remove(host);Controller.connectionChanged(ConnectionState(host,phase=ConnectionPhase.DISCONNECTED))}}}
 fun disconnect(host:String){cancelConnection(host);cancelReads(host);sessions.remove(host)?.disconnect();owners.remove(host)?.first?.disconnect();modes.remove(host);Controller.connectionChanged(ConnectionState(host))}
 fun releaseOwnerChannels()=synchronized(owners){owners.values.forEach{it.first.disconnect()};owners.clear()}
 fun close(){sessions.keys.toList().forEach(::disconnect);ramSessions.closeAll();ramCandidatePins.clear();owners.values.forEach{it.first.disconnect()};owners.clear();context.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)}
 fun perform(request:String):String=try{
  val v=JSONObject(request);val host=v.optString("host");val r=when(v.getString("op")){
   "event"->{Controller.event(host,v.getString("text"));JSONObject()}
   "disk"->JSONObject().put("free_bytes",StatFs(context.filesDir.path).availableBytes)
   "ssh_probe"->sshProbe(host)
   "owner_connection_timing"->{val controller=vault.get("owner_controller:$host").ifBlank{host};connectionTimes["$controller:20001"]?.let{JSONObject(it.toString())}?:JSONObject()}
   "connection_timing"->connectionTimes["$host:22"]?.let{JSONObject(it.toString())}?:JSONObject()
   "connect"->{connect(host);JSONObject()}
   "ram_connect"->connectRam(v)
   "ram_exec"->execRam(v)
   "owner"->JSONObject().put("port",owner(host))
   "client_public"->JSONObject().put("key",publicKey())
   "temporary_connect"->{temporary(host,v.getString("key"));JSONObject()}
   "exec"->exec(host,v.getString("command"),decode(v.optString("input","")),v.getInt("limit"))
   "https"->https(host,decode(v.getString("certificate")),v.getString("path"))
   "pending_password_present"->JSONObject().put("present",vault.get("pending-root:$host").isNotBlank())
   "install_password"->{val pass=passwords[host]?:throw IOException("Enter a new SSH password");vault.put("pending-root:$host",pass);JSONObject().put("password",pass)}
   "verify_password"->{val pass=v.getString("password");verifyRoot(host,pass,v.optString("key").takeIf{it.isNotBlank()});vault.update{it.put("root:$host",pass);it.remove("pending-root:$host")};Controller.credentialsChanged(host);JSONObject()}
   "remember_root"->{val pass=vault.get("root:$host");require(pass.isNotBlank()){"Enter the existing root SSH password"};verifyRoot(host,pass);JSONObject()}
   "verify_pending_password"->{val pass=vault.get("pending-root:$host");require(pass.isNotBlank()){"Re-enter the new SSH password"};verifyRoot(host,pass);vault.update{it.put("root:$host",pass);it.remove("pending-root:$host")};Controller.credentialsChanged(host);JSONObject()}
   "download"->download(v,false)
   "nand_download"->download(v,true)
   "upload"->upload(v)
   else->throw IOException("Unknown Android operation")
  };r.put("ok",true).toString()
 }catch(e:Exception){JSONObject().put("ok",false).put("error",e.message?.take(300)?:"Connection failed").toString()}
}
