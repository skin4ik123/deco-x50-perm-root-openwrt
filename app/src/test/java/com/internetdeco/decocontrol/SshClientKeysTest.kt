package com.internetdeco.decocontrol
import com.jcraft.jsch.JSch
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
class SshClientKeysTest {
 @Test fun generated_key_survives_vault_encoding_and_jsch_reload(){
  val key=SshClientKeys.generate()
  assertTrue(String(key.privatePem).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
  val encryptedStorageInput=Base64.getDecoder().decode(Base64.getEncoder().encodeToString(key.privatePem))
  val jsch=JSch();jsch.addIdentity("deco-control",encryptedStorageInput,null,null);encryptedStorageInput.fill(0)
  val loaded=jsch.identityRepository.identities.single()
  assertArrayEquals(Base64.getDecoder().decode(key.publicKey.substringAfter(' ')),loaded.publicKeyBlob)
  assertNotNull(loaded.getSignature("test-only-message".toByteArray()))
  jsch.removeAllIdentity();key.privatePem.fill(0)
 }
 @Test fun independently_generated_keys_are_different(){
  val a=SshClientKeys.generate();val b=SshClientKeys.generate();assertNotEquals(a.publicKey,b.publicKey);a.privatePem.fill(0);b.privatePem.fill(0)
 }
}
