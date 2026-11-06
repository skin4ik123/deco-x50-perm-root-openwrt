#!/usr/bin/env python3
"""Host JVM integration of the real Rust JNI bridge; synthetic responses only."""
import json,os,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
subprocess.run(['cargo','build','-q','--manifest-path',str(ROOT/'core/Cargo.toml'),'-j2'],check=True)
jar=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))
with tempfile.TemporaryDirectory(prefix='deco-jni-') as tmp:
 p=Path(tmp);src=p/'com/internetdeco/decocontrol';src.mkdir(parents=True)
 (src/'PlatformBridge.java').write_text('''package com.internetdeco.decocontrol;
import org.json.*;import java.util.Base64;
public class PlatformBridge {public int calls=0,writes=0;public String perform(String input){calls++;JSONObject r=new JSONObject(input);if(r.optString("command").contains("fw_setenv"))writes++;String data="0\\n";if(r.optString("command").equals("lua -"))data="{\\\"uid\\\":0,\\\"uptime\\\":120.0,\\\"cpu_raw\\\":\\\"cpu 100 0 50 900 1 0 0 0\\\",\\\"hostname\\\":\\\"Тестовый Deco ✓\\\",\\\"interfaces\\\":[]}";return new JSONObject().put("ok",true).put("exit",0).put("data",Base64.getEncoder().encodeToString(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toString();}}
''')
 (src/'Native.java').write_text('''package com.internetdeco.decocontrol;
import org.json.*;
public class Native {public static native String run(String input,PlatformBridge bridge);
 public static void main(String[] args){System.load(args[0]);PlatformBridge b=new PlatformBridge();String request=new JSONObject().put("host","192.168.99.101").put("action","metrics").put("home",args[1]).toString();for(int i=0;i<200;i++){JSONObject r=new JSONObject(run(request,b));if(!r.getBoolean("ok")||!r.getJSONObject("data").getString("hostname").equals("Тестовый Deco ✓"))throw new AssertionError(r.toString());}int before=b.calls;JSONObject status=new JSONObject(run(new JSONObject(request).put("action","ram_status").toString(),b));if(!status.getBoolean("ok")||!status.getJSONObject("data").getBoolean("can_prepare")||!status.getJSONObject("data").isNull("operation_id")||!status.getJSONObject("data").getString("image_sha256").equals("ce2c49c0fb6dadea9e6430cba9a39ca03f833bb6b20df7d63a9d0ddefeec4ee2")||b.calls!=before)throw new AssertionError("RAM local status contract failed");JSONObject prepare=new JSONObject(run(new JSONObject(request).put("action","ram_prepare").put("server_ip","192.168.1.2").toString(),b));if(prepare.getBoolean("ok")||b.writes!=0)throw new AssertionError("Unsupported RAM prepare was not stopped");JSONObject no=new JSONObject(run("{",b));if(no.getBoolean("ok"))throw new AssertionError("invalid JSON accepted");System.out.println("{\\\"real_rust_jni\\\":true,\\\"jvm_check_jni\\\":true,\\\"requests\\\":200,\\\"unicode_roundtrip\\\":true,\\\"malformed_request_rejected\\\":true,\\\"android_device_test\\\":false}");}}
''')
 subprocess.run(['javac','-cp',str(jar),'-d',str(p),*map(str,src.glob('*.java'))],check=True)
 done=subprocess.run(['java','-Xcheck:jni','-XX:ActiveProcessorCount=2','-Xmx128m','-cp',str(p)+':'+str(jar),'com.internetdeco.decocontrol.Native',str(ROOT/'core/target/debug/libdeco_core.so'),str(p/'app')],capture_output=True,text=True,timeout=30)
 assert done.returncode==0,done.stderr+done.stdout
 assert 'WARNING' not in done.stderr+done.stdout,done.stderr+done.stdout
 report=json.loads(done.stdout);report.update(version="1.1.0",ram_status_roundtrip=True,ram_status_has_no_platform_calls=True,unsupported_RAM_prepare_has_no_writer=True);(ROOT/'verification/jni.json').write_text(json.dumps(report,indent=2)+'\n');print(done.stdout,end='')
