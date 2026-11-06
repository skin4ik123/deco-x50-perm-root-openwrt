package com.internetdeco.decocontrol

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.security.SecureRandom
import java.util.Base64

class SshClientIdentity(val privatePem:ByteArray,val publicKey:String)
object SshClientKeys {
 @JvmStatic fun generate():SshClientIdentity {
  val key=Ed25519PrivateKeyParameters(SecureRandom())
  val blob=OpenSSHPrivateKeyUtil.encodePrivateKey(key)
  val text=StringWriter()
  try{PemWriter(text).use{it.writeObject(PemObject("OPENSSH PRIVATE KEY",blob))}}
  finally{blob.fill(0)}
  val public=OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey())
  return SshClientIdentity(text.toString().toByteArray(Charsets.US_ASCII),"ssh-ed25519 "+Base64.getEncoder().encodeToString(public))
 }
}
