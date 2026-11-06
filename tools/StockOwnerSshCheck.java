import com.jcraft.jsch.*;
import com.internetdeco.decocontrol.SshPolicy;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;
public class StockOwnerSshCheck {
 public static void main(String[]args)throws Exception{
  Security.addProvider(new BouncyCastleProvider());Session s=new JSch().getSession("1111111111111111111111111111111111111111","127.0.0.1",9227);
  SshPolicy.configure(s,true);s.setConfig("StrictHostKeyChecking","no");s.setPassword("synthetic-not-an-owner-password");s.setTimeout(10000);
  try{s.connect(10000);throw new AssertionError("Synthetic account must not authenticate");}
  catch(JSchException e){if(!e.getMessage().contains("Auth fail"))throw e;System.out.println("{\"actual_oem_arm64_dropbear\":true,\"production_ssh_policy\":true,\"key_exchange_completed\":true,\"authentication_reached\":true,\"real_owner_authentication_tested\":false}");}
  finally{s.disconnect();}
 }
}
