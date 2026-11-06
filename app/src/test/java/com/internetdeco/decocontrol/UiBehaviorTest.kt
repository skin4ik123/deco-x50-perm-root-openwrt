package com.internetdeco.decocontrol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class UiBehaviorTest{
 @Test fun validatesAnyLocalNetworkWithoutPersonalAddressExceptions(){
  for(host in listOf("8.8.8.8","127.0.0.1","10.0.0.0","192.168.1.255","192.168.1.300","foo","192.168.1..20","192.168.1.foo.20","192.168.001.20"))assertFalse(host,Controller.validHost(host))
  for(host in listOf("192.168.1.1","192.168.68.20","10.20.30.40","172.16.5.9"))assertTrue(host,Controller.validHost(host))
 }
 @Test fun noPersonalRoutersAreCreatedOnFirstLaunch(){assertTrue(Controller.profilesFromStorage(null).isEmpty())}
 @Test fun savedUserProfilesSurviveUpgrade(){val a=org.json.JSONArray().put(JSONObject().put("host","10.20.30.40").put("name","My office").put("favorite",true));val n=Controller.profilesFromStorage(a).single();assertEquals("My office",n.name);assertTrue(n.favorite)}
 @Test fun iwClients(){val value=JSONObject().put("source","iw").put("raw","Station 02:01:02:03:04:05 (on wlan0)\n\tsignal: -61 dBm\n\ttx bitrate: 144.4 MBit/s\nStation 02:01:02:03:04:06 (on wlan0)\n\tsignal: -76 dBm\n");val result=parseClients(value);assertEquals(2,result.size);assertTrue(result[0].second.contains("-61"));assertTrue(result[0].second.contains("144.4"));assertEquals("02:01:02:03:04:06",result[1].first)}
 @Test fun vendorClientColumnIsReadFromHeader(){val value=JSONObject().put("source","wlanconfig").put("raw","ADDR AID CHAN TXRATE RXRATE RSSI IDLE\n02:01:02:03:04:05 1 36 144M 96M 44 3\n");assertEquals(listOf("02:01:02:03:04:05" to "RSSI 44"),parseClients(value))}
 @Test fun formattingNeverInventsMetrics(){assertEquals("—",bytes(Double.NaN));assertEquals("—",bytes(-1.0));assertEquals("1.0 KiB",bytes(1024.0));assertEquals("1d 1h",uptime(90000.0))}
 @Test fun rejectedNewLoginDoesNotHideAnExistingAuthenticatedSession(){val host="10.20.30.40";val state=ScreenState(nodes=listOf(Node(host,"Office",online=true)),connections=mapOf("$host:22" to ConnectionState(host,phase=ConnectionPhase.FAILED)));assertTrue(state.connection(host).connected);assertEquals(ConnectionPhase.FAILED,state.copy(nodes=listOf(Node(host,"Office"))).connection(host).phase)}
 @Test fun offlineDeviceDoesNotClaimThatRootIsAbsent(){val n=Node("10.20.30.40","Office");assertEquals(RootAccess.NOT_CHECKED,n.rootAccess());assertEquals(RootAccess.SAVED_LOGIN,n.copy(passwordSaved=true).rootAccess());assertEquals(RootAccess.CONFIGURED,n.copy(identity=JSONObject().put("password_ssh",true)).rootAccess())}
 @Test fun latestRootMetadataOverridesAnOldInstallationMarker(){val n=Node("10.20.30.40","Office",online=true,identity=JSONObject().put("uid",0).put("password_ssh",true),metrics=JSONObject().put("uid",0).put("password_ssh",false));assertEquals(RootAccess.CONNECTED,n.rootAccess())}
}
