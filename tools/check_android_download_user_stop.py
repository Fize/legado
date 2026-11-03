#!/usr/bin/env python3
"""Stop a real detached UIDT process, preserve cached text and retry through production UI."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET

from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_export_timeout import validate_detached
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
CLASS = 'io.legado.app.ui.book.cache.DownloadUserStopProbe'


def validate_system_ui_stop(proof, app_label):
    if (proof.get('app_label') != app_label or not app_label
            or proof.get('button_resource_id') != 'com.android.systemui:id/fgs_manager_app_item_stop_button'
            or proof.get('actual_pointer_tap') is not True):
        raise ValueError('Need the exact app row and actual native Task Manager Stop control')
    bounds = proof.get('bounds')
    if (not isinstance(bounds, list) or len(bounds) != 4 or any(type(x) is not int for x in bounds)
            or not (0 <= bounds[0] < bounds[2] and 0 <= bounds[1] < bounds[3])):
        raise ValueError('No usable actual Stop button bounds')


def validate_result(prepared, observed, retried, requests, absent_seconds):
    case = prepared.get('id')
    if not case or observed.get('id') != case or retried.get('id') != case:
        raise ValueError('Mixed fixture identities')
    if type(prepared.get('pid')) is not int or prepared['pid'] <= 0 or prepared.get('user_initiated') is not True:
        raise ValueError('Missing actual UIDT process')
    if absent_seconds < 10:
        raise ValueError('Missing bounded no-restart observation')
    for result in [observed, retried]:
        if type(result.get('pid')) is not int or result['pid'] <= 0 or result['pid'] == prepared['pid'] or result.get('old_pid') != prepared['pid']:
            raise ValueError('No actual new process after user stop')
        if result.get('exit_reason') != 10 or result.get('exit_time', 0) < prepared['wall_time_ms']:
            raise ValueError('Need a matching platform REASON_USER_REQUESTED exit')
        if result.get('task') != prepared.get('task') or result.get('old_batch') != prepared.get('batch'):
            raise ValueError('Retry lost the original task')
        if result.get('interrupted_owner_empty') is not True or result.get('saved_chapter_preserved') is not True:
            raise ValueError('Missing interrupted ownership/cache preservation proof')
    for new, old in [('new_batch', 'batch'), ('new_dispatch', 'dispatch'), ('new_process', 'process')]:
        if not retried.get(new) or retried[new] == prepared.get(old):
            raise ValueError('Explicit retry reused stale ownership: ' + new)
    if retried.get('phase') != 'completed' or any(retried.get(k) is not True for k in ['actual_retry_button', 'fresh_chapter_saved']):
        raise ValueError('Queueing or launching is not a completed UI retry')
    if retried['pid'] != observed['pid']:
        raise ValueError('Retry must stay in the reopened UI process')
    if len(requests) != 2 or [r.get('attempt') for r in requests] != [1, 2] or any(r.get('path') != f'/{case}/chapter/1' for r in requests):
        raise ValueError('Need exactly two requests for the uncached chapter; saved chapter must not be fetched')


class Fixture:
    def __init__(self):
        self.release = threading.Event()
        self.received = threading.Event()
        self.lock = threading.Lock()
        self.requests = []

    def snapshot(self):
        with self.lock:
            return [dict(r) for r in self.requests]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True); parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True); parser.add_argument('--expected-api', type=int, required=True)
    parser.add_argument('--expected-abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'], required=True)
    parser.add_argument('--notifications', action='append', choices=['granted', 'denied'])
    parser.add_argument('--output', type=Path, required=True); add_apk_arguments(parser)
    args = parser.parse_args(); resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial) or args.expected_api < 34:
        parser.error('Explicit owned API34+ emulator required')
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()): parser.error('Output must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]
    def run(*words, timeout=120):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=timeout)
    def permissions():
        rows = re.findall(r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]', run('shell','dumpsys','package',PACKAGE).decode())
        if len(rows) != 1: raise ValueError('Ambiguous notification permission')
        return {'granted': rows[0][0] == 'true', 'flags': rows[0][1]}
    fixtures = {}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_GET(self):
            case = self.path.split('/')[1] if self.path.startswith('/') else ''
            fixture = fixtures.get(case)
            if fixture is None:
                self.send_error(404); return
            with fixture.lock:
                attempt = len(fixture.requests) + 1
                record = {'path': self.path, 'attempt': attempt}
                fixture.requests.append(record)
            fixture.received.set()
            if attempt == 1: fixture.release.wait(240)
            body = f'<body>{"Late" if attempt == 1 else "Fresh"} after user stop {case} attempt={attempt}</body>'.encode()
            try:
                self.send_response(200); self.send_header('Content-Type', 'text/html'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
                record['response_written'] = True
            except (BrokenPipeError, ConnectionResetError): record['client_closed'] = True
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); server.daemon_threads = True
    port = server.server_port; endpoint = 'tcp:' + str(port)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    report = {'status': 'running', 'serial': args.serial, 'cases': [], 'installed_apks': [],
              'scope': 'Detached production UIDT, actual SystemUI Task Manager Stop button, host HTTP surviving process death, bounded no-restart observation, production interrupted UI and explicit retry; no forced job or manual recovery'}
    previous = None; reversed_port = False
    try:
        if run('emu','avd','name').decode().splitlines()[0] != args.expected_avd: raise ValueError('Wrong owned AVD')
        validate_target(args.app_apk, int(run('shell','getprop','ro.build.version.sdk')), run('shell','getprop','ro.product.cpu.abilist').decode().strip().split(','), args.expected_api,args.expected_abi)
        for pkg, apk in [(PACKAGE,args.app_apk),(PACKAGE+'.test',args.test_apk)]:
            if b'Success' not in run('install','-r','-t',str(apk)): raise RuntimeError('APK install failed')
            path = run('shell','pm','path',pkg).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk',path): raise ValueError('Unexpected package path')
            actual = run('shell','sha256sum',path[8:]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest(): raise ValueError('Installed APK differs')
            report['installed_apks'].append({'package':pkg,'sha256':actual})
        previous = permissions(); report['permission_before'] = previous
        windows = run('shell','dumpsys','window').decode()
        (out/'window-readiness.txt').write_text(windows)
        if re.search(r'mCurrentFocus=.*Application Error:', windows):
            raise ValueError('An active system crash dialog covers the device; resolve it before testing UI')
        before_reverse = run('reverse','--list').decode(); report['reverse_before'] = before_reverse
        if any(endpoint in row.split() for row in before_reverse.splitlines()): raise ValueError('Reverse endpoint already owned')
        run('reverse',endpoint,endpoint); reversed_port = True
        report['source'] = capture(args.adb,args.serial)
        for permission in args.notifications or ['granted','denied']:
            case = str(uuid.uuid4()); fixture = fixtures[case] = Fixture()
            item = {'id':case,'notifications':permission,'status':'running'}; report['cases'].append(item)
            def probe(stage):
                live = subprocess.run(base+['shell','pidof',PACKAGE],capture_output=True,timeout=10)
                if not live.stdout.strip():
                    # --no-restart attaches to an existing process. API34 throws
                    # in ActivityManager if no ProcessRecord exists yet.
                    (out/(permission+'-'+stage+'-launch.log')).write_bytes(run('shell','am','start','-W','-n',PACKAGE+'/io.legado.app.ui.AppActivity'))
                words = ['shell','am','instrument','-w','-r','--no-restart','-e','finishActivities','false']
                result = subprocess.run(base+words+['-e','class',CLASS+'#exerciseOwnedDownload','-e','downloadUserStop','yes',
                           '-e','caseId',case,'-e','stage',stage,'-e','serverPort',str(port),
                           PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
                (out/(permission+'-'+stage+'.log')).write_bytes(result.stdout)
                result.check_returncode(); validate_instrumentation(result.stdout.decode(),1)
            def read(suffix):
                data = run('exec-out','run-as',PACKAGE,'cat',f'files/compat-download-user-stop/{case}.{suffix}')
                (out/(permission+'-'+suffix)).write_bytes(data); return data
            remote = f'/sdcard/compat-task-manager-{case}.xml'
            def ui(stage):
                run('shell','uiautomator','dump',remote)
                raw = run('exec-out','cat',remote)
                (out/(permission+'-'+stage+'.xml')).write_bytes(raw)
                return ET.fromstring(raw)
            def tap(tree, node):
                parents = {child: parent for parent in tree.iter() for child in parent}
                while node.get('clickable') != 'true' and node in parents: node = parents[node]
                if node.get('clickable') != 'true' or node.get('enabled') != 'true': raise ValueError('SystemUI control is not enabled/clickable')
                bounds = list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
                if len(bounds) != 4 or not (0 <= bounds[0] < bounds[2] and 0 <= bounds[1] < bounds[3]): raise ValueError('SystemUI control has invalid bounds')
                run('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
                return bounds
            def task_manager_stop(prepared):
                run('shell','cmd','statusbar','expand-settings')
                for attempt in range(8):
                    tree = ui('quick-settings-'+str(attempt))
                    candidates = [n for n in tree.iter('node') if n.get('resource-id','').endswith('/fgs_manager_footer')]
                    if not candidates:
                        candidates = [n for n in tree.iter('node') if 'active' in (n.get('text','')+' '+n.get('content-desc','')).lower() and 'app' in (n.get('text','')+' '+n.get('content-desc','')).lower()]
                    if candidates: break
                    time.sleep(.25)
                else: raise ValueError('No observed Active apps Task Manager control')
                (out/(permission+'-quick-settings.png')).write_bytes(run('exec-out','screencap','-p'))
                tap(tree,candidates[0])
                for attempt in range(8):
                    tree = ui('task-manager-'+str(attempt)); parents={child: parent for parent in tree.iter() for child in parent}
                    labels=[n for n in tree.iter('node') if n.get('text')==prepared['app_label']]
                    buttons=[]
                    for label in labels:
                        parent=label
                        while parent is not None:
                            buttons=[n for n in parent.iter('node') if n.get('resource-id','').endswith('/fgs_manager_app_item_stop_button')]
                            if buttons: break
                            parent=parents.get(parent)
                        if buttons: break
                    if len(buttons)==1: break
                    time.sleep(.25)
                else: raise ValueError('No unique Task Manager Stop button for the exact app label')
                (out/(permission+'-task-manager.png')).write_bytes(run('exec-out','screencap','-p'))
                bounds=tap(tree,buttons[0])
                item['system_ui_stop']={'app_label':prepared['app_label'],'button_resource_id':buttons[0].get('resource-id'),'bounds':bounds,'actual_pointer_tap':True}
                validate_system_ui_stop(item['system_ui_stop'],prepared['app_label'])
            try:
                print('START '+permission+': detached platform user stop',flush=True)
                run('shell','pm','grant' if permission=='granted' else 'revoke',PACKAGE,'android.permission.POST_NOTIFICATIONS')
                if permissions()['granted'] is not (permission=='granted'): raise ValueError('Permission not applied')
                probe('prepare'); prepared = json.loads(read('state.json')); item['prepared'] = prepared
                if not fixture.received.wait(20): raise ValueError('No real request reached the surviving host server')
                before = read('instrumented.txt').decode(); detached = run('shell','dumpsys','activity','-p',PACKAGE).decode()
                (out/(permission+'-detached.txt')).write_text(detached); validate_detached(before,detached,prepared['pid'])
                if str(prepared['pid']) not in run('shell','pidof',PACKAGE).decode().split(): raise ValueError('Prepared process already died')
                job = run('shell','cmd','jobscheduler','get-job-state','-n','legado.book-transfer',PACKAGE,str(prepared['job'])).decode()
                (out/(permission+'-job-before.txt')).write_text(job)
                if 'active' not in job.split(): raise ValueError('Actual UIDT job not active before stop')
                run('shell','input','keyevent','KEYCODE_HOME')
                task_manager_stop(prepared)
                death_deadline = time.monotonic() + 5
                while True:
                    live = subprocess.run(base+['shell','pidof',PACKAGE],capture_output=True,timeout=10).stdout.decode().split()
                    if not live: break
                    if live != [str(prepared['pid'])] or time.monotonic() >= death_deadline:
                        raise ValueError('Old process did not die or a new process restarted')
                    time.sleep(.05)
                item['old_pid_gone'] = True
                (out/(permission+'-jobs-after-stop.txt')).write_bytes(run('shell','dumpsys','jobscheduler'))
                start = time.monotonic(); until = start + 10
                while time.monotonic() < until:
                    live = subprocess.run(base+['shell','pidof',PACKAGE],capture_output=True,timeout=10)
                    if live.stdout.strip(): raise ValueError('App remained alive or restarted during the no-retry window')
                    if len(fixture.snapshot()) != 1: raise ValueError('Automatic request occurred after user stop')
                    time.sleep(.4)
                item['no_restart_seconds'] = time.monotonic()-start; item['detached_before_stop'] = True
                fixture.release.set() # The old response belongs to the dead process and must not publish.
                (out/(permission+'-post-stop-window.txt')).write_bytes(run('shell','dumpsys','window'))
                run('shell','cmd','statusbar','collapse')
                run('shell','input','keyevent','KEYCODE_BACK')
                probe('observe'); observed = json.loads(read('observe.json')); item['observed'] = observed
                time.sleep(2)
                if len(fixture.snapshot()) != 1: raise ValueError('Opening cache UI silently restarted the interrupted transfer')
                if str(observed['pid']) not in run('shell','pidof',PACKAGE).decode().split(): raise ValueError('Observation must detach without killing the new UI process')
                probe('retry'); retried = json.loads(read('retry.json')); item['retried'] = retried
                if retried['pid'] != observed['pid']: raise ValueError('Explicit retry must remain in the reopened UI process')
                item['requests'] = fixture.snapshot()
                validate_result(prepared,observed,retried,item['requests'],item['no_restart_seconds']); item['status']='passed'
            finally:
                fixture.release.set()
                run('shell','cmd','statusbar','collapse')
                item['requests'] = fixture.snapshot()
                probe('cleanup'); cleanup = json.loads(read('cleanup.json')); item['cleanup']=cleanup
                if cleanup.get('owned_records_removed') is not True or cleanup.get('baseline_managers_preserved') is not True: raise ValueError('Incomplete UUID fixture cleanup')
                for suffix in ['state.json','instrumented.txt','observe.json','retry.json','cleanup.json']:
                    run('shell','run-as',PACKAGE,'rm','-f',f'files/compat-download-user-stop/{case}.{suffix}')
                run('shell','rm','-f',remote)
            print('PASS '+permission+': real stop, preserved cached chapter and completed explicit UI retry',flush=True)
        after = capture(args.adb,args.serial)
        if after['source_sha256'] != report['source']['source_sha256'] or after['apks'] != report['source']['apks']: raise ValueError('Source or APK changed')
        report['checks_passed']=True
    except Exception as error:
        report.update(status='failed',error=f'{type(error).__name__}: {error}'); raise
    finally:
        try:
            if previous is not None:
                run('shell','pm','grant' if previous['granted'] else 'revoke',PACKAGE,'android.permission.POST_NOTIFICATIONS')
                report['permission_after']=permissions(); report['permission_restored']=report['permission_after']==previous
                if not report['permission_restored']: raise ValueError('Notification flags did not restore')
            if reversed_port:
                run('reverse','--remove',endpoint)
                report['reverse_after']=run('reverse','--list').decode(); report['reverse_restored']=report['reverse_after']==before_reverse
                if not report['reverse_restored']: raise ValueError('Reverse mappings did not restore')
            if report.get('checks_passed') is True: report['status']='passed'
        except Exception as error:
            report.update(status='failed',restoration_error=f'{type(error).__name__}: {error}'); raise
        finally:
            for fixture in fixtures.values(): fixture.release.set()
            server.shutdown(); server.server_close()
            (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__ == '__main__': main()
