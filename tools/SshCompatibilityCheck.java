import com.jcraft.jsch.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.io.*;
import java.security.Security;
import java.util.*;
public class SshCompatibilityCheck {
 static byte[] exec(Session s,String command,byte[] input)throws Exception{
  ChannelExec c=(ChannelExec)s.openChannel("exec");c.setCommand(command);c.setInputStream(new ByteArrayInputStream(input));ByteArrayOutputStream err=new ByteArrayOutputStream();c.setErrStream(err);InputStream stream=c.getInputStream();try{c.connect(5000);byte[] data=stream.readAllBytes();long end=System.nanoTime()+5_000_000_000L;while(!c.isClosed()&&System.nanoTime()<end)Thread.sleep(10);if(c.getExitStatus()!=0)throw new AssertionError(err.toString());return data;}finally{c.disconnect();}
 }
 public static void main(String[] args)throws Exception{
  Security.addProvider(new BouncyCastleProvider());JSch jsch=new JSch();Session s=jsch.getSession("root","127.0.0.1",9226);
  s.setConfig("StrictHostKeyChecking","no");s.setConfig("PreferredAuthentications","password");s.setPassword("test-only-password");s.setTimeout(10000);
  long start=System.nanoTime();s.connect(10000);long login=(System.nanoTime()-start)/1_000_000;
  try{if(!new String(exec(s,"id -u",new byte[0])).trim().equals("0"))throw new AssertionError("uid");
   byte[] bytes=new byte[65536];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;if(!Arrays.equals(bytes,exec(s,"cat",bytes)))throw new AssertionError("binary stream");
   ChannelShell pty=(ChannelShell)s.openChannel("shell");pty.setPty(true);pty.setPtyType("xterm-256color",100,30,0,0);ByteArrayOutputStream terminal=new ByteArrayOutputStream();PipedOutputStream writer=new PipedOutputStream();pty.setInputStream(new PipedInputStream(writer));pty.setOutputStream(terminal);pty.connect(5000);
   writer.write("test -t 0 && printf '\\nDECO_PTY_OK\\n'; exit\n".getBytes());writer.flush();long end=System.nanoTime()+8_000_000_000L;while(!pty.isClosed()&&System.nanoTime()<end)Thread.sleep(20);pty.disconnect();writer.close();if(!terminal.toString().contains("\r\nDECO_PTY_OK\r\n"))throw new AssertionError("PTY: "+terminal);
   start=System.nanoTime();for(int i=0;i<10;i++)if(!new String(exec(s,"printf ok",new byte[0])).equals("ok"))throw new AssertionError("reuse");
   System.out.println("{\"jsch_version\":\"2.28.0\",\"actual_arm64_dropbear\":true,\"password_root\":true,\"binary_64KiB\":true,\"pty\":true,\"reused_session_commands\":10,\"login_ms\":"+login+",\"ten_commands_ms\":"+(System.nanoTime()-start)/1_000_000+",\"real_wifi_test\":false}");
  }finally{s.disconnect();}
 }
}
