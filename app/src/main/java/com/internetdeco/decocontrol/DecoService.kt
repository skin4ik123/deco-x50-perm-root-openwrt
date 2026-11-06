package com.internetdeco.decocontrol
import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class DecoService:Service(){
 private val worker=Executors.newSingleThreadExecutor();private var busy=false
 override fun onBind(intent:Intent?):IBinder?=null
 private fun notice(text:String)=NotificationCompat.Builder(this,"operations").setSmallIcon(com.internetdeco.decocontrol.R.drawable.ic_launcher).setContentTitle("Deco Control").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).build()
 override fun onCreate(){super.onCreate();Controller.initialize(this);getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("operations","Deco operations",NotificationManager.IMPORTANCE_LOW))}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent==null||busy)return START_NOT_STICKY
  val host=intent.getStringExtra("host")?:return START_NOT_STICKY;val action=intent.getStringExtra("action")?:return START_NOT_STICKY;val args=JSONObject(intent.getStringExtra("args")?:"{}")
  busy=true;startForeground(12,notice("$host · operation in progress"))
  worker.execute{
   val marker=File(filesDir,"active-ui.json");val wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"decocontrol:operation")
   var error:String?=null;var data:JSONObject?=null;var ramEndpoint:RamEndpoint?=null
   try{wake.acquire(15*60*1000L);marker.writeText(JSONObject().put("host",host).put("action",action).toString())
    if(action=="ram_check_openwrt")ramEndpoint=RamEndpoint(host,args.getString("operation_id"))
    if(action in listOf("install","continue_install","password")){
     val source=JSONObject().put("owner_host",Controller.ownerHost(host))
     val boot=Controller.response("bootstrap",host,source);if(!boot.optBoolean("ok"))throw Exception(boot.optString("error"))
    }
    if(action=="ram_image"){
     Controller.ramImageChanged(RamImageState(downloading=true))
     var lastPercent=-1
     val file=Controller.ramImages.download{received,total->
      val percent=(received*100/total).toInt()
      if(percent!=lastPercent){lastPercent=percent;Controller.ramImageChanged(RamImageState(downloading=true,received=received,total=total));Controller.event(host,"Downloading RAM image · $percent%");getSystemService(NotificationManager::class.java).notify(12,notice("RAM image · $percent%"))}
     }
     Controller.ramImageChanged(RamImageState(path=file.path,received=file.length()))
     data=JSONObject().put("image_verified",true).put("path",file.path)
    }else{
     val r=Controller.response(action,host,args);if(!r.optBoolean("ok"))throw Exception(r.optString("error","Operation stopped"));data=r.optJSONObject("data")?:JSONObject().put("result",r.opt("data"))
     if(action=="ram_check_openwrt"){
      val proof=requireNotNull(data)
      require(proof.optString("target")==host&&proof.optString("operation_id")==ramEndpoint?.operationId&&proof.optString("phase")=="openwrt_verified"&&proof.optJSONObject("openwrt")?.optBoolean("verified")==true){"OpenWrt identity was not verified for this operation"}
      Controller.bridge.confirmRamVerification(host,ramEndpoint!!.operationId)
     }
    }
    marker.delete()
   }catch(e:Exception){error=e.message?:"Operation interrupted";if(action=="ram_image")Controller.ramImageChanged(RamImageState(error=error))}finally{ramEndpoint?.let{runCatching{Controller.bridge.discardRamVerification(it.stockHost,it.operationId)}};if(wake.isHeld)wake.release();Controller.complete(host,action,data,error);busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
  };return START_NOT_STICKY
 }
 override fun onDestroy(){worker.shutdown();super.onDestroy()}
}
