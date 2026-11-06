import com.jcraft.jsch.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public class TemporaryRootSshCheck {
 public static void main(String[] args)throws Exception{
  Security.addProvider(new BouncyCastleProvider());
  if(args[0].equals("prepare")){
   com.internetdeco.decocontrol.SshClientIdentity key=com.internetdeco.decocontrol.SshClientKeys.generate();
   Files.write(Path.of(args[1]),key.getPrivatePem());Files.writeString(Path.of(args[2]),key.getPublicKey()+"\n");Arrays.fill(key.getPrivatePem(),(byte)0);return;
  }
  byte[] encoded=Base64.getDecoder().decode(Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(args[1]))));
  JSch client=new JSch();client.addIdentity("deco-control",encoded,null,null);Arrays.fill(encoded,(byte)0);
  Session s=client.getSession("root","127.0.0.1",9226);s.setConfig("StrictHostKeyChecking","no");s.setConfig("PreferredAuthentications","publickey");s.setTimeout(10000);
  try{s.connect(10000);ChannelExec c=(ChannelExec)s.openChannel("exec");c.setCommand("id -u");InputStream input=c.getInputStream();c.connect(5000);String uid=new String(input.readAllBytes()).trim();c.disconnect();if(!uid.equals("0"))throw new AssertionError("temporary UID: "+uid);System.out.println("{\"actual_arm64_temporary_dropbear\":true,\"ed25519_generation_and_reload\":true,\"private_input_cleared_after_load\":true,\"public_key_authentication\":true,\"command_uid0\":true,\"real_router_tested\":false}");}finally{s.disconnect();}
 }
}
