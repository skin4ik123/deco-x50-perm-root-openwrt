-keep class com.internetdeco.decocontrol.Native { *; }
-keepclassmembers class com.internetdeco.decocontrol.PlatformBridge { public java.lang.String perform(java.lang.String); }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**

# JSch selects algorithms by class name from its configuration map.
-keep class com.jcraft.jsch.** { *; }
