#!/usr/bin/env python3
"""Exercise the real JNI storage repair and RO RAM status on an isolated local tree."""
from pathlib import Path
import subprocess, tempfile

root = Path(__file__).resolve().parents[1]
lib = root / "core/target/debug/libdeco_core.so"
source = '''package com.internetdeco.decocontrol;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
class Native {
  static native String prepareStorage(String home, String host);
  static native String run(String request, PlatformBridge bridge);
}
class PlatformBridge {
  int calls;
  public String perform(String request) { calls++; throw new AssertionError("No router calls allowed"); }
}
public class StorageProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    Path root = Files.createTempDirectory("deco-storage-jni-");
    try {
      Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx--x"));
      String error = Native.prepareStorage(root.toString(), "10.20.30.40");
      if (!error.isEmpty()) throw new AssertionError(error);
      if (!Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))) throw new AssertionError("root permissions");
      Path journal = root.resolve("nodes/10.20.30.40/untouched.txt");
      Files.writeString(journal, "keep existing local state");
      PlatformBridge bridge = new PlatformBridge();
      String request = "{\\"action\\":\\"ram_status\\",\\"host\\":\\"10.20.30.40\\",\\"home\\":\\"" + root + "\\"}";
      for (int i=0; i<200; i++) {
        if (!Native.prepareStorage(root.toString(), "10.20.30.40").isEmpty()) throw new AssertionError("repeat repair");
        String answer=Native.run(request, bridge);
        if (!answer.contains("\\"ok\\":true")) throw new AssertionError(answer);
      }
      if (bridge.calls != 0 || !Files.readString(journal).equals("keep existing local state")) throw new AssertionError("side effect");
      if (Native.prepareStorage(root.toString(), "../outside").isEmpty()) throw new AssertionError("path traversal accepted");
      System.out.println("CHECKJNI_STORAGE_PASS=200;RO_RAM_STATUS=200;ROUTER_CALLS=0");
    } finally {
      try (var paths=Files.walk(root)) { for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
  }
}
'''
with tempfile.TemporaryDirectory(prefix="deco-storage-java-") as temp:
    p=Path(temp)/"StorageProbe.java"; p.write_text(source)
    subprocess.run(["javac","-d",temp,str(p)],check=True)
    subprocess.run(["java","-Xcheck:jni","-cp",temp,"com.internetdeco.decocontrol.StorageProbe",str(lib)],check=True)
