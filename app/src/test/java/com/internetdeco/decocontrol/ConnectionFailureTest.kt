package com.internetdeco.decocontrol
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test
class ConnectionFailureTest {
 @Test fun errors_keep_endpoint_and_failed_stage(){
  val e=connectionFailure("10.20.30.40",20001,"ssh_banner",SocketTimeoutException("Read timed out"))
  assertEquals("timeout",e.code);assertTrue(e.message!!.contains("10.20.30.40:20001"));assertTrue(e.message!!.contains("ssh banner"))
 }
 @Test fun auth_and_algorithms_have_different_actions(){
  assertEquals("authentication",connectionFailure("10.1.1.2",20001,"authentication",JSchException("Auth fail")).code)
  assertEquals("algorithms",connectionFailure("10.1.1.2",20001,"key_exchange",JSchException("Algorithm negotiation fail")).code)
 }
 @Test fun raw_failure_details_cannot_echo_the_supplied_password(){
  val e=connectionFailure("10.1.1.2",20001,"authentication",JSchException("unexpected test-only-password"),"test-only-password")
  assertFalse(e.message!!.contains("test-only-password"));assertTrue(e.message!!.contains("[redacted]"))
 }
 @Test fun oem_legacy_policy_is_scoped_to_owner_access(){
  val normal=JSch().getSession("root","10.1.1.2",22);val before=normal.getConfig("kex");SshPolicy.configure(normal,false);assertEquals(before,normal.getConfig("kex"))
  val owner=JSch().getSession("owner","10.1.1.2",20001);SshPolicy.configure(owner,true)
  assertTrue(owner.getConfig("kex").split(',').contains("diffie-hellman-group14-sha1"));assertTrue(owner.getConfig("server_host_key").split(',').contains("ssh-rsa"));assertEquals("yes",owner.getConfig("StrictHostKeyChecking"))
 }
}
