package com.internetdeco.decocontrol
import com.jcraft.jsch.Session

object SshPolicy {
 @JvmStatic fun configure(session:Session,owner:Boolean) {
  session.setConfig("StrictHostKeyChecking","yes")
  session.setConfig("PreferredAuthentications","password")
  session.setConfig("NumberOfPasswordPrompts","1")
  // OEM Dropbear uses RSA/SHA1 host signatures on some reviewed revisions.
  // Host keys are still pinned. The root server uses modern Ed25519 defaults.
  if(owner){append(session,"server_host_key","ssh-rsa");append(session,"kex","diffie-hellman-group14-sha1")}
 }
 private fun append(s:Session,key:String,value:String){val list=s.getConfig(key).split(',');if(value !in list)s.setConfig(key,(list+value).joinToString(","))}
}
