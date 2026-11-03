#!/usr/bin/env python3
"""Exercise a real damaged restore record, retry UI, repair, app close and fresh launch on emulators."""
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
PROBE = 'io.legado.app.help.storage.RestoreRecoveryUiProbe'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
RECOVERY = 'io.legado.app.ui.recovery.RestoreRecoveryActivity'
ENTRIES = {
    'main': 'io.legado.app.ui.AppActivity',
    'reader': 'io.legado.app.ui.book.read.ReadBookActivity',
    'audio': 'io.legado.app.ui.book.audio.AudioPlayActivity',
    'video': 'io.legado.app.ui.video.VideoPlayerActivity',
    'info': 'io.legado.app.ui.book.info.BookInfoActivity',
    'source-editor': 'io.legado.app.ui.book.source.edit.BookSourceEditActivity',
    'source-manager': 'io.legado.app.ui.book.source.manage.BookSourceActivity',
    'code': 'io.legado.app.ui.code.CodeEditActivity',
    'web': 'io.legado.app.ui.browser.WebViewActivity',
    'file': 'io.legado.app.ui.file.HandleFileActivity',
    'permission': 'io.legado.app.lib.permission.PermissionActivity',
}


def has_active_service(text, package, component):
    # dumpsys also prints historical Last ANR service records even when a filter is supplied.
    return bool(re.search(r'^\s*\* ServiceRecord\{[^\n]*' + re.escape(package + '/' + component)
                          + r'(?:\s|})', text, re.M))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--font-scale', type=float)
    p.add_argument('--density', type=int)
    p.add_argument('--size')
    p.add_argument('--entry', choices=list(ENTRIES), default='main')
    p.add_argument('--poison-editor-preferences', action='store_true')
    p.add_argument('--constructor-probe-with-main', action='store_true', help='Probe a cold editor constructor, then launch recovery through Main; does not launch the selected native lifecycle')
    services = p.add_mutually_exclusive_group()
    services.add_argument('--verify-blocked-read-aloud-service', choices=['tts', 'http-tts'], help='Cold start a blocked read-aloud foreground service before Main')
    services.add_argument('--verify-blocked-video-service', action='store_true', help='Cold start the blocked floating video foreground service before Main; requires existing root or su')
    services.add_argument('--verify-blocked-audio-service', action='store_true', help='Cold start the foreground audio service before Main and verify its recovery stop; requires existing root or su')
    p.add_argument('--use-existing-su', action='store_true', help='Only use an already-installed su to launch non-exported fixture components')
    close_mode = p.add_mutually_exclusive_group()
    close_mode.add_argument('--close-via-back', action='store_true')
    close_mode.add_argument('--close-via-gesture', action='store_true')
    a = p.parse_args()
    verify_service = a.verify_blocked_audio_service or a.verify_blocked_read_aloud_service is not None or a.verify_blocked_video_service
    if verify_service and a.entry != 'main':
        p.error('Blocked service checks use Main for the subsequent recovery UI')
    if a.constructor_probe_with_main and (not a.poison_editor_preferences or a.entry not in ('code', 'source-manager')):
        p.error('--constructor-probe-with-main requires a poisoned code or source-manager constructor probe')
    if not re.fullmatch(r'emulator-\d+', a.serial):
        p.error('Only emulators are permitted')
    if a.size and not re.fullmatch(r'\d{3,4}x\d{3,4}', a.size):
        p.error('Unexpected display size')
    if a.output.exists() and any(a.output.iterdir()):
        p.error('Output must be new or empty')
    a.output.mkdir(parents=True, exist_ok=True)
    case = str(uuid.uuid4())
    relative = 'cache/compat-recovery-ui/' + case
    remote_xml = '/sdcard/legado-recovery-' + case + '.xml'
    base = [a.adb, '-s', a.serial]

    def adb(*words, check=True):
        r = subprocess.run(base + list(words), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=40)
        if check and r.returncode:
            raise RuntimeError((r.stdout + r.stderr).decode(errors='replace'))
        return r

    def read(path):
        return adb('exec-out', 'run-as', PACKAGE, 'cat', path, check=False).stdout

    def instrument(method):
        return base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PROBE + '#' + method,
                       '-e', 'restoreRecoveryUi', 'yes', '-e', 'restoreRecoveryUiId', case,
                       '-e', 'restoreRecoveryUiBadPreferences', 'yes' if a.poison_editor_preferences else 'no',
                       '-e', 'restoreRecoveryUiNativeEntry', ENTRIES[a.entry], RUNNER]

    def run_probe(method, label):
        r = subprocess.run(instrument(method), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        (a.output / (label + '.log')).write_bytes(r.stdout)
        text = r.stdout.decode()
        assert 'OK (1 test)' in text and re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', text) == ['1', '0'], label

    def snapshot(label):
        adb('shell', 'uiautomator', 'dump', remote_xml)
        raw = adb('exec-out', 'cat', remote_xml).stdout
        (a.output / (label + '.xml')).write_bytes(raw)
        pixels = adb('exec-out', 'screencap', '-p').stdout
        assert pixels.startswith(b'\x89PNG')
        (a.output / (label + '.png')).write_bytes(pixels)
        return ET.fromstring(raw)

    def bounds(node):
        return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def node(tree, name):
        return next((n for n in tree.iter('node') if n.get('resource-id') == PACKAGE + ':id/restore_recovery_' + name), None)

    def scroll_top():
        adb('shell', 'uiautomator', 'dump', remote_xml)
        tree = ET.fromstring(adb('exec-out', 'cat', remote_xml).stdout)
        containers = [bounds(n) for n in tree.iter('node') if 'ScrollView' in n.get('class','')]
        if not containers:
            return
        x1,y1,x2,y2 = max(containers,key=lambda b:(b[2]-b[0])*(b[3]-b[1]))
        for _ in range(3):
            adb('shell','input','swipe',str((x1+x2)//2),str(y1+(y2-y1)//5),
                str((x1+x2)//2),str(y1+(y2-y1)*4//5),'250')

    def tap_action(name, label):
        for index in range(8):
            tree = snapshot(label + '-' + str(index))
            button = node(tree, name)
            if button is not None and button.get('enabled') == 'true':
                box = bounds(button)
                if len(box) == 4 and box[2] > box[0] and box[3] - box[1] >= minimum_touch_height:
                    width_dp = (box[2]-box[0])*160/int(density_values[-1])
                    assert width_dp <= 721, 'Recovery actions stretched beyond the wide-window limit'
                    report.setdefault('action_bounds',[]).append({'action':name,'bounds':box,'width_dp':width_dp,
                                                                'minimum_touch_height_px':minimum_touch_height})
                    adb('shell', 'input', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2))
                    return tree
            boxes = [bounds(n) for n in tree.iter('node') if n.get('scrollable') == 'true']
            assert boxes, 'Action is inaccessible and no scroll container exists'
            x1, y1, x2, y2 = max(boxes, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
            adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str(y1 + (y2-y1)*4//5),
                str((x1+x2)//2), str(y1+(y2-y1)//5), '300')
        raise AssertionError('Recovery action inaccessible: ' + name)

    def launch_recovery():
        entry = ENTRIES['main' if a.constructor_probe_with_main else a.entry]
        words = ['shell'] + (['su', '0'] if via_su else []) + ['am', 'start', '-W', '-n', PACKAGE + '/' + entry]
        result = adb(*words)
        (a.output/'entry-launch.log').write_bytes(result.stdout)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            lines = adb('shell', 'dumpsys', 'activity', 'activities').stdout.decode().splitlines()
            if any(RECOVERY in line and 'resumedactivity' in line.lower() for line in lines):
                return
            time.sleep(0.2)
        raise AssertionError(a.entry + ' entry did not enter the recovery Activity')

    via_su = False
    if (verify_service or (not a.constructor_probe_with_main and a.entry not in ('main', 'reader'))) and adb('shell', 'id', '-u').stdout.strip() != b'0':
        if not a.use_existing_su:
            p.error('Non-exported host entry requires an already-rooted emulator or explicit --use-existing-su')
        if adb('shell', 'su', '0', 'id', '-u', check=False).stdout.strip() != b'0':
            p.error('Existing su is unavailable; this tool never enables root')
        via_su = True

    original_font = adb('shell', 'settings', 'get', 'system', 'font_scale').stdout.decode().strip()
    original_density = adb('shell', 'wm', 'density').stdout.decode()
    original_size = adb('shell', 'wm', 'size').stdout.decode()
    process = None
    ready = None
    verified_pid = None
    completed = False
    report = {'status': 'running', 'case_id': case, 'serial': a.serial, 'installed_apks': [],
              'verify_blocked_audio_service': a.verify_blocked_audio_service,
              'verify_blocked_read_aloud_service': a.verify_blocked_read_aloud_service,
              'verify_blocked_video_service': a.verify_blocked_video_service,
              'entry': a.entry, 'entry_uses_existing_su': via_su,
              'poison_editor_preferences': a.poison_editor_preferences,
              'constructor_probe_with_main': a.constructor_probe_with_main,
              'profile': {'font_scale': a.font_scale, 'density': a.density, 'size': a.size},
              'scope': ('Cold native constructor followed by Main recovery UI; selected native lifecycle not launched' if a.constructor_probe_with_main else 'Cold '+a.entry+' entry, backup/staging barrier, retry, close and fresh main Activity')}
    try:
        if a.font_scale is not None:
            adb('shell', 'settings', 'put', 'system', 'font_scale', str(a.font_scale))
        if a.density is not None:
            adb('shell', 'wm', 'density', str(a.density))
        if a.size is not None:
            adb('shell', 'wm', 'size', a.size)
        actual_density = adb('shell','wm','density').stdout.decode()
        density_values = re.findall(r'(?:Physical|Override) density:\s*(\d+)',actual_density)
        minimum_touch_height = int(48*int(density_values[-1])/160)-3
        for pkg, apk in [(PACKAGE, ROOT/'app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk'),
                         (PACKAGE+'.test', ROOT/'app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk')]:
            assert b'Success' in adb('install', '-r', '-t', str(apk)).stdout
            paths = [s[8:] for s in adb('shell', 'pm', 'path', pkg).stdout.decode().splitlines() if s.endswith('/base.apk')]
            assert len(paths) == 1 and re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0])
            actual = adb('shell', 'sha256sum', paths[0]).stdout.decode().split()[0]
            assert actual == hashlib.sha256(apk.read_bytes()).hexdigest()
            report['installed_apks'].append({'package': pkg, 'sha256': actual})
        with (a.output/'prepare.log').open('wb') as log:
            process = subprocess.Popen(instrument('holdCorruptOwnedRecordBeforeHostKill'), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                assert process.poll() is None, 'Probe ended before preparation'
                try:
                    candidate = json.loads(read(relative+'/ready.json'))
                    if candidate.get('id') == case:
                        ready = candidate
                        break
                except (ValueError, UnicodeDecodeError):
                    pass
                time.sleep(0.1)
            assert ready is not None
            pid, uid = int(ready['pid']), int(ready['uid'])
            assert pid > 1 and uid >= 10000
            assert read(f'/proc/{pid}/cmdline').split(b'\0')[0].decode() == PACKAGE
            assert re.search(r'^Uid:\s+'+str(uid)+r'\s', read(f'/proc/{pid}/status').decode(), re.M)
            assert int(adb('shell','run-as',PACKAGE,'id','-u').stdout) == uid
            verified_pid = pid
            if int(adb('shell','id','-u').stdout) == 0:
                adb('shell','kill','-9',str(pid))
                report['signal_sender'] = 'existing_adb_root'
            else:
                owner = subprocess.run(base + ['shell', 'run-as', PACKAGE, 'sh', '-c',
                                               shlex.quote('kill -9 '+str(pid))],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                if owner.returncode == 0:
                    report['signal_sender'] = 'run_as_owner'
                else:
                    adb('shell','su','0','kill','-9',str(pid))
                    report['signal_sender'] = 'existing_su'
            process.wait(timeout=15)
        report.update({'killed_pid': pid, 'normal_app_uid': uid, 'signal': 'SIGKILL'})
        run_probe('verifyBlockedBackupBeforeRepair', 'blocked-backup')
        if a.poison_editor_preferences and a.entry in ('code', 'source-manager'):
            run_probe('verifySelectedConstructorDoesNotReadRecoveryPreferences', 'native-constructor')
        if verify_service:
            service = 'io.legado.app.service.VideoPlayService' if a.verify_blocked_video_service else {'tts': 'io.legado.app.service.TTSReadAloudService', 'http-tts': 'io.legado.app.service.HttpReadAloudService'}.get(a.verify_blocked_read_aloud_service, 'io.legado.app.service.AudioPlayService')
            adb('shell', 'am', 'force-stop', PACKAGE)
            words = ['shell'] + (['su', '0'] if via_su else []) + ['am', 'start-foreground-service', '-n', PACKAGE + '/' + service, '-a', 'play']
            result = adb(*words)
            (a.output/'blocked-service-launch.log').write_bytes(result.stdout)
            assert b'Error' not in result.stdout, 'Service command rejected by the framework'
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                raw_pid = adb('shell', 'pidof', PACKAGE, check=False).stdout.decode().strip()
                logs = adb('shell', 'logcat', '-d', '-s', 'RestoreRecovery:I').stdout.decode()
                if raw_pid and any(re.search(r'\s'+raw_pid.split()[0]+r'\s', line) and
                    ('Stopping '+service.rsplit('.', 1)[1]+' until restore recovery completes') in line for line in logs.splitlines()):
                    break
                time.sleep(.1)
            else:
                raise AssertionError('Cold service did not report its recovery stop')
            service_pid = int(raw_pid.split()[0])
            assert re.search(r'^Uid:\s+'+str(uid)+r'\s', read(f'/proc/{service_pid}/status').decode(), re.M)
            (a.output/'blocked-service-stop.log').write_text(logs)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                state = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE + '/' + service).stdout
                if not has_active_service(state.decode(), PACKAGE, service): break
                time.sleep(.1)
            else: raise AssertionError('Blocked service remained registered')
            (a.output/'blocked-service-stopped.txt').write_bytes(state)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                current = adb('shell', 'pidof', PACKAGE, check=False).stdout.decode().split()
                assert current and int(current[0]) == service_pid, 'Cold foreground service process died'
                errors = adb('shell', 'logcat', '-d', '-s', 'AndroidRuntime:E').stdout.decode()
                failures = [line for line in errors.splitlines() if re.search(r'\s'+str(service_pid)+r'\s', line) and
                    ('FATAL EXCEPTION' in line or 'Process: '+PACKAGE in line)]
                assert not failures, 'Cold foreground service crashed: '+str(failures)
                time.sleep(.5)
            report.update({'cold_service_stopped': True, 'cold_service_component': service, 'cold_service_pid': service_pid,
                           'foreground_handshake_survival_seconds': 20})
            if a.verify_blocked_audio_service:
                report.update({'cold_audio_service_stopped': True, 'cold_audio_service_pid': service_pid})
        launch_recovery()
        ui_pid = int(adb('shell','pidof',PACKAGE).stdout.decode().split()[0])
        initial = snapshot('initial-required')
        assert node(initial,'title') is not None
        initial_title = node(initial,'title').get('text')
        initial_message = node(initial,'message').get('text')
        tap_action('retry','bad-retry')
        time.sleep(0.3)
        scroll_top()
        failed = snapshot('retry-failed')
        assert node(failed,'message') is not None and node(failed,'message').get('text') != initial_message
        assert node(failed,'title').get('text') == initial_title
        assert hashlib.sha256(read('files/restore-settings-v1.bin')).hexdigest() == ready['corrupt_sha256']
        assert hashlib.sha256(read(relative+'/valid.bin')).hexdigest() == ready['valid_sha256']
        current_size = adb('shell','wm','size').stdout.decode()
        effective_sizes = re.findall(r'(?:Physical|Override) size:\s*(\d+x\d+)',current_size)
        profile_size = effective_sizes[-1]
        w,h = map(int,profile_size.split('x'))
        adb('shell','wm','size',str(w)+'x'+str(h+120))
        scroll_top()
        resized = snapshot('failed-state-resized')
        assert node(resized,'message').get('text') == node(failed,'message').get('text')
        prior_override = re.search(r'Override size:\s*(\S+)',current_size)
        adb('shell','wm','size',prior_override.group(1) if prior_override else 'reset')
        scroll_top()
        restored = snapshot('failed-state-restored')
        assert node(restored,'message').get('text') == node(failed,'message').get('text')
        assert int(adb('shell','pidof',PACKAGE).stdout.decode().split()[0]) == ui_pid
        report['activity_resize_preserved_failed_state'] = True
        # Only this fixture's exact saved before-image replaces its deliberately damaged copy.
        adb('shell','run-as',PACKAGE,'cp',relative+'/valid.bin','files/restore-settings-v1.bin.repair')
        adb('shell','run-as',PACKAGE,'mv','files/restore-settings-v1.bin.repair','files/restore-settings-v1.bin')
        assert hashlib.sha256(read('files/restore-settings-v1.bin')).hexdigest() == ready['valid_sha256']
        tap_action('retry','repaired-retry')
        deadline = time.monotonic()+20
        done = None
        while time.monotonic()<deadline:
            scroll_top()
            done = snapshot('completion')
            heading = node(done,'title')
            if heading is not None and heading.get('text') != initial_title:
                break
            time.sleep(0.2)
        else:
            raise AssertionError('Recovery never completed')
        assert int(adb('shell','pidof',PACKAGE).stdout.decode().split()[0]) == ui_pid
        prefs = ET.fromstring(read('shared_prefs/'+PACKAGE+'_preferences.xml'))
        assert next(n.text for n in prefs if n.get('name') == ready['key']) == 'old'
        assert adb('shell','run-as',PACKAGE,'ls','files/restore-settings-v1.bin',check=False).returncode != 0
        assert hashlib.sha256(read(ready['stage_relative'])).hexdigest() == ready['stage_sha256']
        report['staging_protected_until_reopen'] = True
        if a.close_via_back:
            adb('shell','input','keyevent','KEYCODE_BACK')
            report['close_method'] = 'back_key'
        elif a.close_via_gesture:
            assert adb('shell','settings','get','secure','navigation_mode').stdout.strip() == b'2', 'Gesture navigation required'
            box = bounds(next(done.iter('node')))
            adb('shell','input','swipe','1',str((box[1]+box[3])//2),str(box[2]*2//5),str((box[1]+box[3])//2),'300')
            report['close_method'] = 'back_gesture'
        else:
            tap_action('close','explicit-close')
            report['close_method'] = 'button'
        deadline=time.monotonic()+10
        while time.monotonic()<deadline:
            if str(ui_pid) not in adb('shell','pidof',PACKAGE,check=False).stdout.decode().split():
                break
            time.sleep(0.1)
        else:
            raise AssertionError('Close action did not end the quarantined process')
        run_probe('verifyNewApplicationAfterUiClose','fresh-main-application')
        state=json.loads(read(relative+'/verified.json'))
        assert state['id']==case and state['pid']!=ui_pid and state['barrier_released_after_reopen']
        assert state['font_and_locale_restored'] and state['existing_archive_preserved']
        adb('shell','run-as',PACKAGE,'rm','-r',relative)
        completed=True
        report.update({'status':'passed','ui_pid':ui_pid,'failed_retry_retained_record':True,
                       'explicit_retry_completed':True,'explicit_close_ended_process':True,'fresh_application':state,
                       'fixture_files_removed':True})
    except BaseException as error:
        report.update({'status':'failed','error':str(error)})
        raise
    finally:
        if process is not None and process.poll() is None:
            adb('shell','am','force-stop',PACKAGE,check=False)
            process.wait(timeout=15)
        if ready is not None and not completed:
            try:
                result=subprocess.run(instrument('cleanupOwnedFixtureAfterHostFailure'),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60)
                (a.output/'failure-teardown.log').write_bytes(result.stdout)
                report['failure_teardown_passed']='OK (1 test)' in result.stdout.decode()
            except subprocess.SubprocessError as error:
                report['failure_teardown_error']=str(error)
        adb('shell','am','force-stop',PACKAGE,check=False)
        for name, original in [('density',original_density),('size',original_size)]:
            override=re.search(r'Override '+name+r':\s*(\S+)',original)
            adb('shell','wm',name,override.group(1) if override else 'reset')
        if original_font in ('null',''):
            adb('shell','settings','delete','system','font_scale')
        else:
            adb('shell','settings','put','system','font_scale',original_font)
        adb('shell','rm',remote_xml,check=False)
        report['display_profile_restored']=True
        (a.output/'evidence.json').write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS {a.serial}: damaged-record UI blocked backups; failed retry retained data; repair, explicit retry/close and fresh app verified')


if __name__=='__main__':
    main()
