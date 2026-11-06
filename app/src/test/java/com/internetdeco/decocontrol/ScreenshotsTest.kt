package com.internetdeco.decocontrol
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
class ScreenshotsTest{
 @get:Rule val paparazzi=Paparazzi(deviceConfig=DeviceConfig.PIXEL_6.copy(locale="en"))
 private fun fixture():Pair<ScreenState,Node>{val interfaces=JSONArray().put(JSONObject().put("name","eth0").put("rx",183512412).put("tx",693512412).put("rx_per_second",121344.0).put("tx_per_second",340112.0).put("state","up"));val metrics=JSONObject().put("uid",0).put("cpu_percent",26.0).put("uptime",93600.0).put("kernel","4.4.60").put("password_ssh",true).put("mem",JSONObject().put("MemTotal",474800128.0).put("MemAvailable",268435456.0)).put("temperatures",JSONArray().put(JSONObject().put("celsius",52.5))).put("interfaces",interfaces).put("filesystems","Filesystem 1K-blocks Used Available\n/tmp 232324 41232 191092");val samples=listOf(18.0,22.0,21.0,36.0,25.0,27.0,26.0).mapIndexed{i,v->Sample(i.toLong(),v,268435456.0,metrics)};val node=Node("192.168.99.101","Deco · Office",online=true,metrics=metrics,identity=JSONObject().put("uid",0),samples=samples);return ScreenState(nodes=listOf(node),selected=node.host,tab=1) to node}
 @Test fun dashboardDark(){val (s,n)=fixture();paparazzi.snapshot("dashboard-dark"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){Dashboard(s,n,{},{},{})}}}}
 @Test fun dashboardLightLargeText(){val (s,n)=fixture();paparazzi.snapshot("dashboard-light-large"){MaterialTheme(colorScheme=Light){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,1.4f)){Surface(Modifier.fillMaxSize(),color=Light.background){Dashboard(s,n,{},{},{})}}}}}
 @Test fun deviceSwitcherList(){val (s,n)=fixture();val list=listOf(n,n.copy(host="192.168.99.102",name="Deco · A room with a long name",online=false,metrics=null),n.copy(host="192.168.99.103",name="Deco · Workshop",favorite=true));paparazzi.snapshot("devices"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){NodesPage(s.copy(nodes=list),{},{},{})}}}}
 @Test fun emptyFirstLaunch(){paparazzi.snapshot("empty-first-launch"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){NodesPage(ScreenState(),{},{},{})}}}}
 @Test fun connectionStatusCards(){paparazzi.snapshot("connection-status"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){Text("Connection status",style=MaterialTheme.typography.titleLarge);Panel{ConnectionIndicator(ConnectionState("10.20.30.40",phase=ConnectionPhase.CONNECTED),passwordSaved=true)};Panel{ConnectionIndicator(ConnectionState("10.20.30.41",phase=ConnectionPhase.FAILED));ConnectionProblem("Timed out waiting for SSH. Your saved password has been kept.")};Panel{ConnectionIndicator(ConnectionState("10.20.30.42"),passwordSaved=true)}}}}}}
 @Test fun firstDeviceRootSetup(){val n=Node("10.20.30.40","Office");paparazzi.snapshot("first-device-root-setup"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){Dashboard(ScreenState(nodes=listOf(n),selected=n.host,tab=1),n,{},{},{})}}}}
 @Test fun rootEntryOnDeviceCard(){val n=Node("10.20.30.40","Office");paparazzi.snapshot("root-entry-device-card"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){NodesPage(ScreenState(nodes=listOf(n),selected=n.host),{},{},{})}}}}
 @Test fun rootEntryWithLargeText(){val n=Node("10.20.30.40","Office");paparazzi.snapshot("root-entry-large-text"){MaterialTheme(colorScheme=Light){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,1.4f)){Surface(Modifier.fillMaxSize(),color=Light.background){Dashboard(ScreenState(nodes=listOf(n),selected=n.host,tab=1),n,{},{},{})}}}}}
 private val resultOwner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?)=Unit}}
 @Test fun openWrtWizard(){val n=Node("10.20.30.40","Office",passwordSaved=true);paparazzi.snapshot("openwrt-wizard"){MaterialTheme(colorScheme=Dark){CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultOwner){Surface(Modifier.fillMaxSize(),color=Dark.background){OpenWrtPage(ScreenState(nodes=listOf(n),selected=n.host),n,{},autoRefresh=false)}}}}}
 @Test fun ramBootProgressWithLargeText(){val n=Node("10.20.30.40","Office");val ram=RamState(JSONObject().put("operation_id","0123456789abcdef0123456789abcdef").put("phase","reboot_requested").put("resolved",false).put("started",true));paparazzi.snapshot("openwrt-progress-large-text"){MaterialTheme(colorScheme=Light){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,1.4f)){Surface(Modifier.fillMaxSize(),color=Light.background){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){Text("OpenWrt RAM boot",style=MaterialTheme.typography.titleLarge);RamEntryCard(n,ram){};Panel{Text("Waiting for OpenWrt",style=MaterialTheme.typography.titleLarge);Text("Join X50-OpenWrt Wi-Fi, then verify the running system over SSH.");Text("Reboot requested. OpenWrt has not been verified.",color=Light.onSurfaceVariant)}}}}}}}
 @Test fun interruptedRamBootRemainsVisibleOnOfflineDashboard(){val n=Node("10.20.30.40","Office",passwordSaved=true);val saved=JSONObject().put("operation_id","0123456789abcdef0123456789abcdef").put("phase","reboot_unconfirmed").put("resolved",false).put("started",true);val s=ScreenState(nodes=listOf(n),selected=n.host,results=mapOf("${n.host}:ram_status" to saved));paparazzi.snapshot("offline-ram-recovery"){MaterialTheme(colorScheme=Dark){Surface(Modifier.fillMaxSize(),color=Dark.background){Dashboard(s,n,{},{},{})}}}}
}
