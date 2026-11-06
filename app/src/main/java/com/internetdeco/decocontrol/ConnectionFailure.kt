package com.internetdeco.decocontrol

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ConnectionFailure(val host:String,val port:Int,val stage:String,val code:String,detail:String):
 IOException("$host:$port · ${stage.replace('_',' ')}: $detail")

fun connectionFailure(host:String,port:Int,stage:String,error:Exception,secret:String=""):ConnectionFailure {
 val causes=generateSequence<Throwable>(error){it.cause}.take(8).toList()
 val message=causes.mapNotNull{it.message}.joinToString(" · ")
 val (code,detail)=when {
  message.contains("HostKey",true)||message.contains("host key changed",true)||message.contains("reject HostKey",true)->"host_key" to "SSH host key was not accepted. Verify this device key."
  message.contains("Auth fail",true)||message.contains("Auth cancel",true)->"authentication" to (if(port==20001)"Owner username or password was not accepted. Check the TP-Link account or SSH username."else"The root SSH password was not accepted.")
  message.contains("Algorithm negotiation",true)||error.javaClass.simpleName.contains("AlgoNego")->"algorithms" to "The SSH server offered incompatible key exchange, host key or cipher algorithms."
  causes.any{it is SocketTimeoutException}||message.contains("timeout",true)||message.contains("timed out",true)->"timeout" to "Timed out during ${stage.replace('_',' ')}."
  causes.any{it is ConnectException}->"refused" to "The device refused the connection; this port may be closed."
  causes.any{it is NoRouteToHostException}->"route" to "No route to this address on the selected Wi-Fi network."
  causes.any{it is UnknownHostException}->"address" to "Could not resolve the device address."
  else->"transport" to message.ifBlank{"The connection closed unexpectedly."}
 }
 val safe=if(secret.isNotEmpty())detail.replace(secret,"[redacted]")else detail
 return ConnectionFailure(host,port,stage,code,safe.take(350))
}
