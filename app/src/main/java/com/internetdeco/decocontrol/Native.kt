package com.internetdeco.decocontrol
object Native {
 init { System.loadLibrary("deco_core") }
 @JvmStatic external fun run(request: String, bridge: PlatformBridge): String
 @JvmStatic external fun prepareStorage(home: String, host: String): String
}
