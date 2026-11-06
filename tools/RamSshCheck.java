import com.internetdeco.decocontrol.SshPolicy;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Local protocol check. The policy class comes from the actual Android compilation. */
public final class RamSshCheck {
    private static String authenticatedWith;
    private static int logEvents;
    private static boolean enteredAuthenticationMethodLoop;

    private static Session session(int port, byte[] publicKey) throws Exception {
        JSch jsch = new JSch();
        jsch.getHostKeyRepository().add(
            new HostKey("[127.0.0.1]:" + port, publicKey), null);
        Session session = jsch.getSession("root", "127.0.0.1", port);
        SshPolicy.configure(session, false);
        if (!"yes".equals(session.getConfig("StrictHostKeyChecking"))
                || !"password".equals(session.getConfig("PreferredAuthentications"))) {
            throw new AssertionError("Unexpected production SSH policy");
        }
        // This is exactly the RAM-only caller's credential. No UserInfo callback is installed.
        session.setPassword(new byte[0]);
        session.setTimeout(10000);
        return session;
    }

    private static byte[] exec(Session session, String command, byte[] input) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(new ByteArrayInputStream(input));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setErrStream(errors);
        InputStream stream = channel.getInputStream();
        try {
            channel.connect(5000);
            byte[] result = stream.readNBytes(131073);
            if (result.length > 131072) throw new AssertionError("Unexpected command output size");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!channel.isClosed() && System.nanoTime() < deadline) Thread.sleep(10);
            if (!channel.isClosed() || channel.getExitStatus() != 0) {
                throw new AssertionError("Local command failed: " + errors.toString(StandardCharsets.UTF_8));
            }
            return result;
        } finally {
            channel.disconnect();
        }
    }

    private static void verifyPty(Session session) throws Exception {
        ChannelShell shell = (ChannelShell) session.openChannel("shell");
        shell.setPty(true);
        shell.setPtyType("xterm-256color", 100, 30, 0, 0);
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        PipedOutputStream writer = new PipedOutputStream();
        shell.setInputStream(new PipedInputStream(writer));
        shell.setOutputStream(terminal);
        try {
            shell.connect(5000);
            shell.setPtySize(44, 16, 0, 0);
            writer.write("test -t 0 && printf '\\nRAM_PTY_OK\\n'; stty size; exit\n"
                .getBytes(StandardCharsets.UTF_8));
            writer.flush();
            long deadline = System.nanoTime() + 8_000_000_000L;
            while (!shell.isClosed() && System.nanoTime() < deadline) Thread.sleep(20);
            String output = terminal.toString(StandardCharsets.UTF_8);
            if (!shell.isClosed() || !output.contains("\r\nRAM_PTY_OK\r\n")
                    || !output.contains("\r\n16 44\r\n")) {
                throw new AssertionError("Local PTY or resize failed: " + output);
            }
        } finally {
            shell.disconnect();
            writer.close();
        }
    }

    private static void verifyChangedHostKeyRejected(int port, byte[] publicKey) throws Exception {
        byte[] differentKey = publicKey.clone();
        differentKey[differentKey.length - 1] ^= 1;
        Session session = session(port, differentKey);
        try {
            session.connect(10000);
            throw new AssertionError("Changed pinned host key was accepted");
        } catch (JSchException expected) {
            if (expected.getMessage() == null
                    || !expected.getMessage().contains("HostKey has been changed")) throw expected;
        } finally {
            session.disconnect();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        if (port < 9200 || port > 9299) throw new IllegalArgumentException("Local test port");
        byte[] publicKey = Base64.getDecoder().decode(Files.readString(Path.of(args[1])).trim());
        Security.addProvider(new BouncyCastleProvider());
        JSch.setLogger(new Logger() {
            @Override public boolean isEnabled(int level) { return true; }
            @Override public void log(int level, String message) {
                logEvents++;
                if (message.startsWith("Next authentication method: ")) enteredAuthenticationMethodLoop = true;
                if (message.contains("Authentication succeeded (none)")) authenticatedWith = "none";
                if (message.contains("Authentication succeeded (password)")) authenticatedWith = "password";
            }
        });
        verifyChangedHostKeyRejected(port, publicKey);
        Session session = session(port, publicKey);
        authenticatedWith = null;
        logEvents = 0;
        enteredAuthenticationMethodLoop = false;
        long start = System.nanoTime();
        try {
            try {
                session.connect(10000);
            } catch (JSchException rejected) {
                if (rejected.getMessage() == null || !rejected.getMessage().contains("Auth fail")) {
                    throw rejected;
                }
                System.out.println("{\"authenticated\":false,\"authentication_rejected\":true,"
                    + "\"key_exchange_completed\":true,\"changed_host_key_rejected\":true}");
                return;
            }
            long loginMs = (System.nanoTime() - start) / 1_000_000;
            if (!"0".equals(new String(exec(session, "id -u", new byte[0]),
                    StandardCharsets.UTF_8).trim())) throw new AssertionError("Namespace root UID");
            byte[] bytes = new byte[65536];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
            if (!Arrays.equals(bytes, exec(session, "cat", bytes))) {
                throw new AssertionError("Binary SSH channel");
            }
            verifyPty(session);
            start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                if (!Arrays.equals(new byte[]{'o', 'k'}, exec(session, "printf ok", new byte[0]))) {
                    throw new AssertionError("Session reuse after PTY");
                }
            }
            // JSch 2.28.0 tries UserAuthNone before its method loop. That early-success
            // branch has no success log; every later method emits the "Next ..." log.
            // Derive none only when logging is active and that loop was never entered.
            if (authenticatedWith == null && logEvents > 0 && !enteredAuthenticationMethodLoop) {
                authenticatedWith = "none";
            }
            if (authenticatedWith == null) throw new AssertionError("Unobserved authentication method");
            System.out.println("{\"authenticated\":true,\"authentication_method\":\""
                + authenticatedWith + "\",\"empty_password_byte_array\":true,"
                + "\"authentication_method_evidence\":\"JSch initial none success or explicit method success log\","
                + "\"credential_callback_installed\":false,\"namespace_root_uid\":0,"
                + "\"key_exchange_completed\":true,\"changed_host_key_rejected\":true,"
                + "\"binary_64KiB\":true,\"pty\":true,\"pty_resize\":true,"
                + "\"reused_session_commands_after_pty\":10,\"login_ms\":" + loginMs
                + ",\"ten_commands_ms\":" + (System.nanoTime() - start) / 1_000_000 + "}");
        } finally {
            session.disconnect();
        }
    }
}
