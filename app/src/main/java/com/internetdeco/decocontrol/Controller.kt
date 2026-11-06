package com.internetdeco.decocontrol
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class Sample(val time:Long,val cpu:Double?,val memory:Double?,val network:JSONObject)
data class Node(val host:String,val name:String,val favorite:Boolean=false,val online:Boolean=false,val lastSeen:Long=0,val metrics:JSONObject?=null,val identity:JSONObject?=null,val error:String?=null,val samples:List<Sample> = emptyList(),val passwordSaved:Boolean=false,val reachable:Boolean?=null)
data class Event(val host:String,val text:String,val time:Long=System.currentTimeMillis())
data class PinRequest(val host:String,val port:Int,val fingerprint:String,val answer:CompletableFuture<Boolean>)
data class Operation(val host:String,val action:String,val running:Boolean,val stage:String,val error:String?=null,val result:JSONObject?=null)
data class ScreenState(val nodes:List<Node> = emptyList(),val selected:String="",val tab:Int=0,val page:String="",val loading:Set<String> = emptySet(),val results:Map<String,Any> = emptyMap(),val errors:Map<String,String> = emptyMap(),val events:List<Event> = emptyList(),val operation:Operation?=null,val pin:PinRequest?=null,val message:String?=null,val resumed:Boolean=false,val monitor:Boolean=true,val connections:Map<String,ConnectionState> = emptyMap(),val terminalFullscreen:Boolean=false,internal val ramImage:RamImageState=RamImageState())
object Controller {
 private val state=MutableStateFlow(ScreenState());val ui=state.asStateFlow()
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(2))
 lateinit var app:Context;private set
 lateinit var vault:SecureVault;private set
 lateinit var bridge:PlatformBridge;private set
 internal lateinit var ramImages:RamImageRepository;private set
 val filePaths=java.util.concurrent.ConcurrentHashMap<String,String>()
 val fileNavigation=java.util.concurrent.ConcurrentHashMap<String,FileNavigation>()
 private val readVersions=java.util.concurrent.ConcurrentHashMap<String,Long>()
 private val sequence=java.util.concurrent.atomic.AtomicLong()
 private var monitorJob:Job?=null
 @Volatile var ready=false;private set
 @Synchronized fun initialize(context:Context){if(ready)return;app=context.applicationContext;vault=SecureVault(app);bridge=PlatformBridge(app,vault);ramImages=RamImageRepository(File(app.filesDir,"firmware"))
  val saved=vault.get("profiles").takeIf{it.isNotBlank()}?.let{runCatching{JSONArray(it)}.getOrNull()}
  val nodes=profilesFromStorage(saved).map{it.copy(passwordSaved=hasSavedPassword(it.host))}
  state.update{it.copy(nodes=nodes,selected=vault.get("selected").takeIf{h->nodes.any{it.host==h}}?:nodes.firstOrNull()?.host?:"")}
  val old=File(app.filesDir,"active-ui.json");if(old.exists()){runCatching{JSONObject(old.readText())}.getOrNull()?.let{v->state.update{it.copy(operation=Operation(v.optString("host"),v.optString("action"),false,"The operation was interrupted. Check its result",error="Check the current Deco state"))}}}
  ready=true
  nodes.forEach{refreshRamStatus(it.host)}
  refreshRamImage()
  monitorJob=scope.launch{while(isActive){delay(15000);val s=state.value;if(s.resumed&&s.monitor&&s.selected.isNotBlank()&&bridge.connected(s.selected)&&s.operation?.running!=true){read("metrics",host=s.selected,silent=true);bridge.closeIdle(s.selected)}}}
 }
 private fun profiles(){vault.put("profiles",JSONArray().apply{state.value.nodes.forEach{put(JSONObject().put("host",it.host).put("name",it.name).put("favorite",it.favorite))}}.toString())}
 fun select(host:String){val old=state.value.selected;if(old!=host){cancelReadOnly(old);if(state.value.pin?.host==old&&state.value.operation?.running!=true)answerPin(false)};state.update{it.copy(selected=host,message=null)};vault.put("selected",host);refreshRamStatus(host);if(bridge.connected(host))read("metrics",host=host,silent=true)}
 fun tab(tab:Int){state.update{it.copy(tab=tab,page="",terminalFullscreen=false)}}
 fun terminalFocus(value:Boolean){state.update{it.copy(terminalFullscreen=value)}}
 fun page(page:String){state.update{it.copy(page=page)}}
 fun resumed(value:Boolean){state.update{it.copy(resumed=value)}}
 fun monitor(value:Boolean){state.update{it.copy(monitor=value)}}
 fun dismissMessage(){state.update{it.copy(message=null)}}
 fun event(host:String,text:String){state.update{it.copy(events=(it.events+Event(host,text)).takeLast(300),operation=it.operation?.let{o->if(o.running&&o.host==host)o.copy(stage=text)else o})}}
 fun toast(text:String){state.update{it.copy(message=text)}}
 fun addNode(host:String,name:String){require(validHost(host)){"Enter the Deco local IPv4 address"};state.update{val previous=it.nodes.firstOrNull{n->n.host==host};val n=previous?.copy(name=name.ifBlank{host})?:Node(host,name.ifBlank{host},passwordSaved=hasSavedPassword(host));it.copy(nodes=it.nodes.filterNot{n->n.host==host}+n,selected=host)};profiles()}
 fun favorite(host:String){state.update{it.copy(nodes=it.nodes.map{n->if(n.host==host)n.copy(favorite=!n.favorite)else n})};profiles()}
 fun remove(host:String){if(state.value.operation?.let{it.running&&it.host==host}==true){toast("Wait for the operation on this Deco to finish");return};if(state.value.results["$host:ram_status"]==null||state.value.errors.containsKey("$host:ram_status")){refreshRamStatus(host);toast("Checking the saved RAM boot state before removing this Deco");return};if(ramState(host).pending){toast("Check or cancel the RAM boot before removing this Deco");return};bridge.disconnect(host);bridge.closeRam(host);TerminalHub.remove(host);state.update{it.copy(nodes=it.nodes.filterNot{n->n.host==host},selected=it.nodes.firstOrNull{n->n.host!=host}?.host?:"")};profiles()}
 fun saveRoot(host:String,password:String){vault.put("root:$host",password);credentialsChanged(host)}
 fun hasSavedPassword(host:String)=vault.get("root:$host").isNotBlank()||vault.get("pending-root:$host").isNotBlank()
 fun credentialsChanged(host:String){val saved=hasSavedPassword(host);state.update{it.copy(nodes=it.nodes.map{n->if(n.host==host)n.copy(passwordSaved=saved)else n})}}
 fun forgetPassword(host:String){if(state.value.operation?.let{it.running&&it.host==host}==true){toast("Wait for the operation to finish");return};vault.update{it.remove("root:$host")};credentialsChanged(host);toast(if(vault.get("pending-root:$host").isNotBlank())"Saved password removed. Pending installation credentials have been kept." else "Saved password removed from this phone")}
 fun connectionChanged(value:ConnectionState){state.update{if((it.connections[value.key]?.startedAt?:0)>value.startedAt)it else it.copy(connections=it.connections+(value.key to value),nodes=if(value.profileKey==value.host&&value.port==22&&value.phase in setOf(ConnectionPhase.FAILED,ConnectionPhase.CANCELLED,ConnectionPhase.DISCONNECTED)&&!bridge.connected(value.host))it.nodes.map{n->if(n.host==value.host)n.copy(online=false)else n}else it.nodes)}}
 fun connectionOptions():ConnectionOptions {val seconds=vault.get("ssh_timeout_seconds").toIntOrNull()?.coerceIn(30,120)?:90;return ConnectionOptions(tcpTimeoutMs=minOf(seconds*1000,30_000),handshakeTimeoutMs=seconds*1000,commandTimeoutMs=seconds*1000,attempts=vault.get("ssh_attempts").toIntOrNull()?.coerceIn(1,3)?:3)}
 fun connect(host:String,password:String?=null,remember:Boolean=true){if("$host:connect" in state.value.loading)return;if(password!=null)bridge.loginCredential(host,password,remember);connectionChanged(ConnectionState(host,phase=ConnectionPhase.QUEUED));read("connect",host=host)}
 fun cancelConnection(host:String){if(state.value.operation?.let{it.running&&it.host==host}==true){toast("The active operation is still using this connection");return};bridge.cancelConnection(host);cancelReadOnly(host);val pinHost=if(host.startsWith("ram:"))RamEndpoint.HOST else host;if(state.value.pin?.host==pinHost)answerPin(false);val previous=state.value.connections.values.filter{it.profileKey==host}.maxByOrNull{it.startedAt};connectionChanged(previous?.copy(phase=ConnectionPhase.CANCELLED)?:ConnectionState(host,phase=ConnectionPhase.CANCELLED))}
 private fun cancelReadOnly(host:String){bridge.cancelReads(host);bridge.discardLoginCredential(host);val keys=readVersions.keys.filter{it.startsWith("$host:")&&!it.endsWith(":owner_check")};keys.forEach{readVersions[it]=sequence.incrementAndGet()};state.update{it.copy(loading=it.loading-keys.toSet())}}
 fun cancelFileRead(host:String,action:String){require(action in setOf("files","read_file","download"));val key="$host:$action";bridge.cancelRead(key);readVersions[key]=sequence.incrementAndGet();state.update{it.copy(loading=it.loading-key,errors=it.errors-key)}}
 fun clearResult(host:String,action:String){val key="$host:$action";state.update{it.copy(results=it.results-key,errors=it.errors-key)}}
 fun ownerHost(target:String)=vault.get("owner_controller:$target").takeIf(::validHost)?:target
 fun ownerSettings(id:String,password:String,host:String,target:String=state.value.selected){require(validHost(host)){"Enter the mesh controller IPv4 address"};vault.update{it.put("owner_id",id.trim());it.put("owner_password",password);it.put("owner_controller:$target",host)}}
 fun forgetKey(host:String){bridge.disconnect(host);vault.update{it.remove("pin:$host:22");it.remove("pin:$host:20001")};toast("Saved key removed. Verify the device key when reconnecting")}
 fun trust(host:String,port:Int,fingerprint:String):Boolean {
  val answer=CompletableFuture<Boolean>();synchronized(this){if(state.value.pin!=null)return false;state.update{it.copy(pin=PinRequest(host,port,fingerprint,answer))}}
  return try{answer.get(90,TimeUnit.SECONDS)}catch(e:Exception){false}finally{state.update{if(it.pin?.answer===answer)it.copy(pin=null)else it}}
 }
 fun answerPin(accepted:Boolean){state.value.pin?.answer?.complete(accepted);state.update{it.copy(pin=null)}}
 fun validHost(host:String):Boolean {
  val parts=host.split('.');if(parts.size!=4||parts.any{it.isEmpty()||it.length>3||!it.all{c->c in '0'..'9'}||(it.length>1&&it[0]=='0')})return false
  val a=parts.map{it.toInt()};return a.all{it in 0..255}&&a[3] in 1..254&&(a[0]==10||(a[0]==192&&a[1]==168)||(a[0]==172&&a[1] in 16..31))
 }
 fun profilesFromStorage(saved:JSONArray?):List<Node> = if(saved==null)emptyList()else (0 until saved.length()).mapNotNull{i->saved.optJSONObject(i)?.let{n->val host=n.optString("host");if(!validHost(host))null else Node(host,n.optString("name").ifBlank{host},n.optBoolean("favorite"))}}.distinctBy{it.host}
 fun response(action:String,host:String,args:JSONObject=JSONObject()):JSONObject {
  if(action=="discover")bridge.networkCheck()
  PrivateStorage.prepare(app,host.takeIf(::validHost))
  args.put("action",action).put("host",host).put("home",app.filesDir.path)
  return JSONObject(Native.run(args.toString(),bridge))
 }
 internal fun ramState(host:String)=RamState(state.value.results["$host:ram_status"] as? JSONObject)
 fun refreshRamStatus(host:String){if(!ready||!validHost(host))return;scope.launch{try{val r=response("ram_status",host);if(!r.optBoolean("ok"))throw Exception(r.optString("error"));state.update{it.copy(results=it.results+("$host:ram_status" to (r.optJSONObject("data")?:JSONObject())),errors=it.errors-"$host:ram_status")}}catch(e:Exception){state.update{it.copy(errors=it.errors+("$host:ram_status" to (e.message?:"Could not read the RAM operation")))}}}}
 internal fun ramImageChanged(value:RamImageState){state.update{it.copy(ramImage=value)}}
 fun refreshRamImage(){if(!ready)return;scope.launch{try{val file=ramImages.verifiedFile();state.update{if(it.ramImage.downloading)it else it.copy(ramImage=RamImageState(path=file?.path,received=if(file==null)0 else file.length()))}}catch(e:Exception){state.update{if(it.ramImage.downloading)it else it.copy(ramImage=RamImageState(error=e.message?:"Could not check the image"))}}}}
 fun cancelRamImage(){ramImages.cancelDownload()}
 fun read(action:String,args:JSONObject=JSONObject(),host:String=state.value.selected,silent:Boolean=false){
  if(host.isBlank()&&action!="discover")return
  if(action=="owner_check"&&(state.value.operation?.running==true||state.value.loading.any{it.endsWith(":owner_check")})){toast("Wait for the current operation to finish");return}
  val key="$host:$action"
  val replaceable=action in setOf("files","read_file")
  if(key in state.value.loading&&!replaceable)return
  if(replaceable)bridge.cancelRead(key)
  val version=sequence.incrementAndGet();readVersions[key]=version
  state.update{it.copy(loading=it.loading+key,errors=it.errors-key)}
  scope.launch {
   try {
    if(readVersions[key]!=version)return@launch
    val r=bridge.reading(host,key){response(action,host,args)}
    if(readVersions[key]!=version)return@launch
    if(!r.optBoolean("ok"))throw Exception(r.optString("error","Operation failed"))
    val data=r.opt("data")?:JSONObject()
    state.update{current->current.copy(results=current.results+(key to data),nodes=current.nodes.map{n->
     if(n.host==host&&action in listOf("connect","metrics"))n.copy(online=true,reachable=true,error=null,lastSeen=System.currentTimeMillis(),
      metrics=if(action=="metrics")data as? JSONObject else n.metrics,
      identity=if(action=="connect")data as? JSONObject else n.identity,
      samples=if(action=="metrics"&&data is JSONObject)(n.samples+Sample(System.currentTimeMillis(),if(data.isNull("cpu_percent"))null else data.optDouble("cpu_percent").takeIf{it.isFinite()},data.optJSONObject("mem")?.optDouble("MemAvailable"),data)).takeLast(120)else n.samples)
     else n})}
    if(action=="connect"){connectionChanged(ConnectionState(host,port=bridge.sessionPort(host),phase=ConnectionPhase.CONNECTED));read("metrics",host=host,silent=true)}
    if(action=="inventory")importInventory(data)
    if(action=="discover")updateDiscovery(data)
    if(action=="boot_check"&&data is JSONObject)toast("Startup configured. Verified new boots: ${data.optInt("distinct_post_install_boots")} of 2")
   } catch(e:Exception) {
    if(readVersions[key]!=version)return@launch
    val message=e.message?:"No response from Deco"
    if(message.contains("Request cancelled")||e is CancellationException)return@launch
    state.update{it.copy(errors=it.errors+(key to message),nodes=it.nodes.map{n->
     if(n.host==host&&action in listOf("connect","metrics"))n.copy(online=bridge.connected(host),error=message)else n},message=if(silent)it.message else message)}
   } finally {
    if(action=="owner_check")bridge.releaseOwnerChannels()
    if(readVersions[key]==version)state.update{it.copy(loading=it.loading-key)}
   }
  }
 }
 private fun importInventory(data:Any){val v=data as? JSONObject?:return;for(id in v.keys()){val n=v.optJSONObject(id)?:continue;val host=n.optString("ip");if(!validHost(host)||n.optString("device_model")!="X50")continue;if(state.value.nodes.none{it.host==host})state.update{it.copy(nodes=it.nodes+Node(host,if(n.optString("role")=="AP")"Mesh controller" else "Deco · ${host.substringAfterLast('.')}"))}};profiles();toast("X50 mesh inventory refreshed")}
 private fun updateDiscovery(data:Any){val a=data as? JSONArray?:return;state.update{s->s.copy(nodes=s.nodes.map{node->val found=(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("host")==node.host};if(found!=null)node.copy(reachable=found.optJSONArray("ports")?.length()!=0)else node})}}
 fun discover(){read("discover",JSONObject().put("hosts",JSONArray(state.value.nodes.map{it.host})),host=state.value.selected,silent=true)}
 fun mutate(action:String,args:JSONObject=JSONObject(),host:String=state.value.selected,password:String?=null){
  if(state.value.loading.any{it.endsWith(":owner_check")}){toast("Wait for the owner access check to finish");return}
  if(state.value.operation?.running==true){toast("Another operation is still running");return};if(!validHost(host)){toast("Select a Deco first");return}
  password?.let{bridge.installPassword(host,it)}
  val intent=Intent(app,DecoService::class.java).putExtra("action",action).putExtra("host",host).putExtra("args",args.toString())
  state.update{it.copy(operation=Operation(host,action,true,"Preparing"))};try{ContextCompat.startForegroundService(app,intent)}catch(e:Exception){complete(host,action,null,e.message?:"Could not start the operation")}
 }
 fun complete(host:String,action:String,result:JSONObject?,error:String?){bridge.releaseOwnerChannels();bridge.clearInstallPassword(host);state.update{it.copy(operation=Operation(host,action,false,if(error==null){if(action.startsWith("ram_"))ramOperationTitle(action,result)else "Done"}else "Operation stopped",error,result),message=if(error!=null)error else it.message)};if(action.startsWith("ram_")){refreshRamStatus(host);if(action=="ram_start"){TerminalHub.close(host);bridge.disconnect(host);state.update{it.copy(nodes=it.nodes.map{n->if(n.host==host)n.copy(online=false)else n})}};if(action in setOf("ram_restore","ram_check_stock")&&result?.optBoolean("resolved")==true)bridge.closeRam(host)}else if(bridge.connected(host))read("metrics",host=host,silent=true)}
 fun clearOperation(){state.update{if(it.operation?.running!=true){File(app.filesDir,"active-ui.json").delete();it.copy(operation=null)}else it}}
 fun disconnect(host:String){if(state.value.operation?.let{it.running&&it.host==host}==true){toast("Wait for the operation to finish");return};TerminalHub.close(host);bridge.disconnect(host);connectionChanged(ConnectionState(host));state.update{it.copy(nodes=it.nodes.map{n->if(n.host==host)n.copy(online=false)else n})}}
 fun zipBackup(folder:File,done:(File)->Unit){scope.launch{try{require(folder.canonicalPath.startsWith(File(app.filesDir,"backups").canonicalPath+File.separator));val file=File(app.filesDir,"exports/Deco-${folder.parentFile?.name}-${folder.name}.zip");file.parentFile?.mkdirs();java.util.zip.ZipOutputStream(file.outputStream().buffered()).use{zip->folder.walkTopDown().filter{it.isFile}.forEach{f->zip.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(folder).path));f.inputStream().use{it.copyTo(zip,32768)};zip.closeEntry()}};withContext(Dispatchers.Main){done(file)}}catch(e:Exception){toast(e.message?:"Could not prepare ZIP")}}}
 fun saveExport(file:File,uri:Uri){scope.launch{try{app.contentResolver.openOutputStream(uri).use{out->requireNotNull(out);file.inputStream().use{it.copyTo(out,32768)}};toast("Copy saved")}catch(e:Exception){toast(e.message?:"Could not save file")}}}
 fun importFile(uri:Uri,done:(File)->Unit){scope.launch{
  var partial:File?=null
  try{
   val display=app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}.orEmpty()
   val name=display.take(200).takeIf{it.isNotBlank()&&it !in setOf(".","..")&&it.none{c->c=='/'||c=='\\'||c=='\u0000'}}?:"upload.bin"
   val dir=File(app.filesDir,"imports/"+java.util.UUID.randomUUID()).apply{mkdirs()};val f=File(dir,name);partial=f
   app.contentResolver.openInputStream(uri).use{input->requireNotNull(input);f.outputStream().use{out->val buffer=ByteArray(32768);var size=0L;while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=64*1024*1024L){"Files must be 64 MiB or smaller"};out.write(buffer,0,n)}}}
   withContext(Dispatchers.Main){done(f)}
  }catch(e:Exception){partial?.delete();toast(e.message?:"Could not open file")}
 }}
}
