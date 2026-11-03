#!/usr/bin/env python3
"""Exercise real manifest broadcast delivery after automatic restore recovery on emulators."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.xiaobaitu.reader.debug'
COMPONENT = 'io.legado.app.receiver.MediaButtonReceiver'
PROBE = 'io.legado.app.help.storage.RestoreReceiverContextProbe'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'


def context_cast_failure(text, pid, component=COMPONENT):
    lines = '\n'.join(line for line in text.splitlines()
                      if re.match(r'^\S+\s+\S+\s+' + str(pid) + r'\s+', line))
    return ('Unable to instantiate receiver ' + component in lines
            and 'MutableContextWrapper cannot be cast to android.app.ContextImpl' in lines)


def optional_file_bytes(output, exit_code, path):
    # Older adb exec-out can return status 0 and put remote cat errors on stdout.
    missing = ('cat: ' + path + ': No such file or directory').encode()
    if output.strip() == missing:
        return b''
    if exit_code:
        raise RuntimeError(output.decode(errors='replace'))
    return output


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--blocked-record', action='store_true', help='Keep a checksum-invalid owned record during the first broadcast')
    p.add_argument('--expect-context-crash', action='store_true', help='Explicit old-candidate failure baseline; never counts as a passing candidate')
    a = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial): p.error('Only emulators are allowed')
    if a.output.exists() and any(a.output.iterdir()): p.error('Output must be new or empty')
    a.output.mkdir(parents=True, exist_ok=True)
    case = str(uuid.uuid4()); relative = 'cache/compat-receiver/' + case
    base = [a.adb, '-s', a.serial]
    def adb(*args, check=True):
        r = subprocess.run(base + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
        if check and r.returncode: raise RuntimeError(r.stdout.decode(errors='replace'))
        return r
    def read(path):
        r = adb('exec-out', 'run-as', PACKAGE, 'cat', path, check=False)
        return optional_file_bytes(r.stdout, r.returncode, path)
    def command(method, restore_crash=False):
        return base + ['shell','am','instrument','-w','-r','-e','class',PROBE+'#'+method,
                       '-e','receiverContextProbe','yes','-e','receiverContextId',case,
                       '-e','restoreOwnedCrashFlag','yes' if restore_crash else 'no',
                       '-e','receiverContextBlocked','yes' if a.blocked_record else 'no',RUNNER]
    def probe(method, label, restore_crash=False):
        r = subprocess.run(command(method, restore_crash), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=70)
        (a.output/(label+'.log')).write_bytes(r.stdout)
        text = r.stdout.decode(errors='replace')
        assert 'OK (1 test)' in text and re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)',text)==['1','0'], label
    def defaults():
        raw=read('shared_prefs/'+PACKAGE+'_preferences.xml');root=ET.fromstring(raw)
        return {n.get('name'):(n.tag,n.get('value'),n.text) for n in root}
    report={'status':'running','case_id':case,'serial':a.serial,'installed_apks':[],
            'blocked_record':a.blocked_record, 'scope':'Real non-instrumented manifest receiver after valid automatic recovery; missing media key prevents playback commands'}
    process=None;ready=None;owned_crash=False
    try:
        for pkg,apk in [(PACKAGE,ROOT/'app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk'),
                        (PACKAGE+'.test',ROOT/'app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk')]:
            assert b'Success' in adb('install','-r','-t',str(apk)).stdout
            path=adb('shell','pm','path',pkg).stdout.decode().strip()[8:]
            actual=adb('shell','sha256sum',path).stdout.decode().split()[0]
            assert actual==hashlib.sha256(apk.read_bytes()).hexdigest()
            report['installed_apks'].append({'package':pkg,'sha256':actual})
        stream=(a.output/'prepare.log').open('wb')
        process=subprocess.Popen(command('holdValidSettingsForAutomaticRecovery'),stdout=stream,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+35
        while time.monotonic()<deadline:
            raw=read(relative+'/ready.json')
            if raw.startswith(b'{'):
                ready=json.loads(raw);break
            if process.poll() is not None: raise AssertionError('Fixture preparation ended before ready')
            time.sleep(.1)
        assert ready and ready['id']==case
        assert hashlib.sha256(read('files/restore-settings-v1.bin')).hexdigest()==ready.get('pending_sha256',ready['valid_sha256'])
        pid,uid=ready['pid'],ready['uid'];assert pid>1 and uid>=10000
        assert read('/proc/'+str(pid)+'/cmdline').split(b'\0')[0].decode()==PACKAGE
        assert re.search(r'^Uid:\s+'+str(uid)+r'\s',read('/proc/'+str(pid)+'/status').decode(),re.M)
        old_events=set(adb('shell','logcat','-b','events','-d','-v','threadtime').stdout.decode().splitlines())
        old_errors=set(adb('shell','logcat','-d','-v','threadtime','-s','AndroidRuntime:E').stdout.decode().splitlines())
        if adb('shell','id','-u').stdout.strip()==b'0':
            adb('shell','kill','-9',str(pid))
            report['signal_sender']='existing_adb_root'
        else:
            adb('shell','run-as',PACKAGE,'sh','-c',shlex.quote('kill -9 '+str(pid)))
            report['signal_sender']='run_as_owner'
        process.wait(timeout=15);stream.close()
        assert hashlib.sha256(read('files/restore-settings-v1.bin')).hexdigest()==ready.get('pending_sha256',ready['valid_sha256'])
        broadcast=adb('shell','am','broadcast','--include-stopped-packages','-n',PACKAGE+'/'+COMPONENT,
                      '-a','android.intent.action.MEDIA_BUTTON','--es','receiverProbeCase',case)
        (a.output/'broadcast.log').write_bytes(broadcast.stdout)
        assert b'Broadcast completed' in broadcast.stdout
        deadline=time.monotonic()+10;target_pid=None;new_errors='';events=[]
        while time.monotonic()<deadline:
            events=[line for line in adb('shell','logcat','-b','events','-d','-v','threadtime').stdout.decode().splitlines()
                    if line not in old_events and 'am_proc_start' in line and PACKAGE+',' in line]
            if events:
                match=re.search(r'\[\d+,(\d+),(\d+),'+re.escape(PACKAGE)+',',events[0])
                assert match and int(match[2])==uid
                target_pid=int(match[1]);assert target_pid!=pid
            new_errors='\n'.join(line for line in adb('shell','logcat','-d','-v','threadtime','-s','AndroidRuntime:E').stdout.decode().splitlines()
                                 if line not in old_errors)
            if target_pid and context_cast_failure(new_errors,target_pid):owned_crash=True;break
            time.sleep(.2)
        assert target_pid is not None,'No new ordinary application process was observed'
        relevant='\n'.join(line for line in new_errors.splitlines() if re.match(r'^\S+\s+\S+\s+'+str(target_pid)+r'\s+',line))
        (a.output/'receiver-runtime.log').write_text(relevant+'\n')
        (a.output/'receiver-process-events.log').write_text('\n'.join(events)+'\n')
        report.update({'normal_app_uid':uid,'writer_pid':pid,'receiver_pid':target_pid,'context_cast_crash_reproduced':owned_crash})
        if a.expect_context_crash:
            assert owned_crash,'Expected context cast failure was not reproduced'
            report['status']='expected_failure_reproduced'
        else:
            assert not owned_crash,'Manifest receiver crashed after automatic recovery'
            assert not ('FATAL EXCEPTION' in relevant or 'Process: '+PACKAGE in relevant),relevant
            assert adb('shell','pidof',PACKAGE).stdout.decode().split()[0]==str(target_pid)
            values=defaults()
            if a.blocked_record:
                assert hashlib.sha256(read('files/restore-settings-v1.bin')).hexdigest()==ready['pending_sha256']
                assert values[ready['key']]==('string',None,'pending')
                assert values['language']==('string',None,'en') and values['fontScale']==('int','16',None)
                adb('shell','am','start','-W','-n',PACKAGE+'/io.legado.app.ui.AppActivity')
                deadline=time.monotonic()+15
                while time.monotonic()<deadline:
                    state=adb('shell','dumpsys','activity','activities').stdout.decode()
                    if any('io.legado.app.ui.recovery.RestoreRecoveryActivity' in line and
                           'resumedactivity' in line.lower() for line in state.splitlines()):break
                    time.sleep(.2)
                else:raise AssertionError('Blocked broadcast process could not show recovery')
                assert adb('shell','pidof',PACKAGE).stdout.decode().split()[0]==str(target_pid)
                report.update({'blocked_record_retained_after_broadcast':True,'recovery_activity_resumed_same_pid':True})
            else:
                assert values[ready['key']]==('string',None,'old')
                assert values['language']==('string',None,'zh') and values['fontScale']==('int','12',None)
            adb('shell','am','force-stop',PACKAGE)
            existing=read('files/restore-settings-v1.bin')
            assert not existing or (len(existing)>44 and existing[8:44].decode()==ready['transaction'])
            adb('shell','run-as',PACKAGE,'cp',relative+'/valid.bin','files/restore-settings-v1.bin')
            probe('verifyApplicationAndActivityResourcesAfterAutomaticRecovery','resource-verification')
            verified=json.loads(read(relative+'/verified.json'));assert verified['id']==case
            report.update({'status':'passed','broadcast_survived_same_pid':True,'automatic_before_images_restored':True,
                           'resource_verification':verified})
    finally:
        if process is not None and process.poll() is None:
            adb('shell','am','force-stop',PACKAGE,check=False);process.wait(timeout=15)
        if ready is not None:
            try:
                adb('shell','am','force-stop',PACKAGE)
                probe('cleanupOwnedReceiverFixture','cleanup',owned_crash)
                report['owned_fixture_cleanup_passed']=True
            except Exception as error:
                report['cleanup_error']=str(error);report['status']='failed'
        adb('shell','am','force-stop',PACKAGE,check=False)
        if report['status']=='running':report['status']='failed'
        (a.output/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert report['status'] in ('passed','expected_failure_reproduced'),report
    print(report['status'],a.serial)


if __name__=='__main__':main()
