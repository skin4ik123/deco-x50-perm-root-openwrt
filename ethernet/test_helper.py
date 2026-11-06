"""Unprivileged shell fixtures. Module commands are mocks, never real kernel calls."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,tempfile,unittest

HERE=Path(__file__).resolve().parent
SCRIPT=HERE/'InternetDeco_X50_Ethernet_RAM_v1.sh'
NATIVE=HERE/'tests/fixtures/rtl8367b-original.ko'
CANDIDATE=HERE/'rtl8367b-familyc.ko'
MOCK=r'''#!/usr/bin/python3
from pathlib import Path
import json,os,shutil,sys
r=Path(os.environ['ETH_FIXTURE']);case=(r/'case').read_text();name=Path(sys.argv[0]).name
module=r/'sys/module/rtl8367b'
if name=='id':print('0');sys.exit(0)
if name=='uname':print('6.18.54'if case=='wrong-kernel' else'6.18.55'if sys.argv[1]=='-r'else'aarch64');sys.exit(0)
if name=='dmesg':print('rtl8367b test fixture');sys.exit(0)
if name=='swconfig':
 if (r/'registered').exists():print('Found: switch0 - RTL8367B')
 sys.exit(0)
if name not in ('rmmod','insmod'):raise SystemExit('Unexpected mock')
with (r/'events').open('a')as f:f.write(json.dumps([name,*sys.argv[1:]])+'\n')
if name=='rmmod':shutil.rmtree(module);sys.exit(0)
if case=='load-fails':sys.exit(1)
module.mkdir();(module/'initstate').write_text('live\n');(module/'refcnt').write_text('0\n')
if case!='no-probe':
 (r/'registered').touch();(r/'sys/bus/platform/drivers/rtl8367b/switch').symlink_to(module)
'''

class HelperTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='eth-shell-fixture-');self.r=Path(self.tmp.name)
  for p in ['tmp','bin','lib/modules/6.18.55','sys/bus/platform/drivers/rtl8367b']:(self.r/p).mkdir(parents=True,exist_ok=True)
  for m in ['rtl8367b','rtl8366_smi','swconfig']:
   p=self.r/'sys/module'/m;p.mkdir(parents=True);(p/'initstate').write_text('live\n');(p/'refcnt').write_text('0\n')
  (self.r/'sys/module/rtl8367b/taint').write_text('')
  shutil.copyfile(NATIVE,self.r/'lib/modules/6.18.55/rtl8367b.ko');shutil.copyfile(CANDIDATE,self.r/'tmp/rtl8367b-familyc.ko')
  for n in ['id','uname','dmesg','swconfig','rmmod','insmod']:
   p=self.r/'bin'/n;p.write_text(MOCK);p.chmod(0o700)
  text=SCRIPT.read_text()
  text=re.sub(r'/(?:sys/|lib/modules/|tmp/)',lambda m:str(self.r)+m.group(0),text)
  text=text.replace('PATH=/usr/sbin:/usr/bin:/sbin:/bin','PATH='+str(self.r/'bin')+':/usr/bin:/bin')
  self.script=self.r/'fixture.sh';self.script.write_text(text)
  (self.r/'case').write_text('ok')
 def tearDown(self):self.tmp.cleanup()
 def run_helper(self,action='--apply',case='ok'):
  (self.r/'case').write_text(case)
  # Host BusyBox has standalone applets that bypass PATH mocks. The production
  # script is separately parsed by ash; run its POSIX body in dash for fixtures.
  return subprocess.run(['/bin/sh',str(self.script),action],env={**os.environ,'ETH_FIXTURE':str(self.r)},capture_output=True,text=True,timeout=8)
 def events(self):return [json.loads(s)for s in (self.r/'events').read_text().splitlines()]if(self.r/'events').exists()else[]
 def test_success_registers_switch_without_network_or_vlan_mutation(self):
  p=self.run_helper();self.assertEqual(p.returncode,0,p.stderr);self.assertIn('FAMILYC_DRIVER_REGISTERED=1',p.stdout);self.assertIn('VLAN_APPLY_NOT_RUN=1',p.stdout)
  self.assertEqual([e[0]for e in self.events()],['rmmod','insmod'])
 def test_repeat_never_replays_module_commands(self):
  self.assertEqual(self.run_helper().returncode,0);before=self.events();p=self.run_helper();self.assertNotEqual(p.returncode,0);self.assertEqual(self.events(),before)
 def test_wrong_kernel_stops_before_any_module_action(self):
  self.assertNotEqual(self.run_helper(case='wrong-kernel').returncode,0);self.assertEqual(self.events(),[])
 def test_corrupt_candidate_stops_before_any_module_action(self):
  p=self.r/'tmp/rtl8367b-familyc.ko';d=bytearray(p.read_bytes());d[0]^=1;p.write_bytes(d)
  self.assertNotEqual(self.run_helper().returncode,0);self.assertEqual(self.events(),[])
 def test_active_references_or_bound_device_are_not_unloaded(self):
  (self.r/'sys/module/rtl8367b/refcnt').write_text('1\n');self.assertNotEqual(self.run_helper().returncode,0);self.assertEqual(self.events(),[])
  (self.r/'sys/module/rtl8367b/refcnt').write_text('0\n');(self.r/'sys/bus/platform/drivers/rtl8367b/switch').symlink_to(self.r/'sys/module/rtl8367b')
  self.assertNotEqual(self.run_helper().returncode,0);self.assertEqual(self.events(),[])
 def test_zero_insmod_exit_without_probe_is_not_success(self):
  p=self.run_helper(case='no-probe');self.assertNotEqual(p.returncode,0);self.assertNotIn('FAMILYC_DRIVER_REGISTERED=1',p.stdout);self.assertIn('OUTCOME_UNCONFIRMED',p.stderr)
 def test_load_failure_is_not_retried_or_automatically_rolled_back(self):
  p=self.run_helper(case='load-fails');self.assertNotEqual(p.returncode,0);self.assertEqual([e[0]for e in self.events()],['rmmod','insmod'])
  before=self.events();self.assertNotEqual(self.run_helper().returncode,0);self.assertEqual(self.events(),before)
 def test_status_is_read_only_and_symlinked_candidate_refused(self):
  p=self.run_helper('--status');self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(self.events(),[])
  p=self.r/'tmp/rtl8367b-familyc.ko';data=p.read_bytes();p.unlink();target=self.r/'candidate-copy';target.write_bytes(data);p.symlink_to(target)
  self.assertNotEqual(self.run_helper().returncode,0);self.assertEqual(self.events(),[])

if __name__=='__main__':unittest.main()
