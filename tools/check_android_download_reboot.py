#!/usr/bin/env python3
"""Check the current non-persisted UIDT reboot policy: durable interrupted UI and explicit retry."""
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
from check_android_download_user_stop import PACKAGE, CLASS, Fixture
from check_android_export_timeout import validate_detached
from check_android_restore_safety import validate_instrumentation

CAPTIVE_PORTAL_SETTINGS=('captive_portal_server','captive_portal_http_url','captive_portal_https_url','captive_portal_fallback_url')


def validate_reboot(prepared, observed, retried, requests, proof):
    before=proof.get('boot_before'); after=proof.get('boot_after')
    try:
        if str(uuid.UUID(before))!=before or str(uuid.UUID(after))!=after or before==after: raise ValueError('No kernel reboot')
    except (TypeError,AttributeError,ValueError) as e: raise ValueError('Distinct canonical kernel boot IDs required') from e
    if prepared.get('job_persisted') is not False or prepared.get('boot_before')!=before:
        raise ValueError('This gate describes the actual non-persisted job policy')
    if any(proof.get(k) is not True for k in ['boot_completed','user_unlocked','network_connected','internet_validated','fixture_reachable','reverse_reconnected','old_job_missing_before_ui']):
        raise ValueError('Incomplete reboot/readiness proof')
    if proof.get('post_boot_idle_seconds',0)<10 or proof.get('requests_before_open')!=1 or proof.get('requests_before_retry')!=1:
        raise ValueError('No usable post-boot no-request observation')
    for result in [observed,retried]:
        if result.get('id')!=prepared['id'] or result.get('boot_before')!=before or result.get('boot_after')!=after:
            raise ValueError('Mixed fixture or boot identity')
        if result.get('task')!=prepared['task'] or result.get('old_batch')!=prepared['batch']:
            raise ValueError('Durable original task was lost')
        if any(result.get(k) is not True for k in ['old_job_absent','interrupted_owner_empty','saved_chapter_preserved']):
            raise ValueError('Missing safe interrupted state/cache proof')
    if type(retried.get('pid')) is not int or retried['pid']<=0 or retried['pid']!=observed.get('pid'):
        raise ValueError('Retry changed the reopened UI process')
    # PID numbers may legitimately be reused after a real kernel reboot.
    for new,old in [('new_batch','batch'),('new_dispatch','dispatch'),('new_process','process')]:
        if not retried.get(new) or retried[new]==prepared.get(old): raise ValueError('Retry reused stale '+new)
    if retried.get('phase')!='completed' or any(retried.get(k) is not True for k in ['actual_retry_button','fresh_chapter_saved']):
        raise ValueError('Interrupted display/queue alone is not completed retry')
    if len(requests)!=2 or [r.get('attempt') for r in requests]!=[1,2] or any(r.get('path')!='/'+prepared['id']+'/chapter/1' for r in requests):
        raise ValueError('Need two uncached-chapter requests, without cached chapter download')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True)
    parser.add_argument('--expected-avd',required=True);parser.add_argument('--expected-api',type=int,required=True)
    parser.add_argument('--expected-abi',choices=['arm64-v8a','armeabi-v7a','x86_64'],required=True)
    parser.add_argument('--notifications',action='append',choices=['granted','denied'])
    parser.add_argument('--boot-wait-seconds',type=int,default=240)
    parser.add_argument('--output',type=Path,required=True);add_apk_arguments(parser)
    args=parser.parse_args();resolve_apks(parser,args)
    if not re.fullmatch(r'emulator-\d+',args.serial) or args.expected_api<34 or not 120<=args.boot_wait_seconds<=600:
        parser.error('Explicit owned API34+ emulator and 120-600s boot allowance required')
    out=args.output.resolve()
    if out.exists() and any(out.iterdir()):parser.error('Output must be new or empty')
    out.mkdir(parents=True,exist_ok=True);base=[args.adb,'-s',args.serial]
    def run(*words,timeout=120):return subprocess.check_output(base+list(words),stderr=subprocess.STDOUT,timeout=timeout)
    def soft(*words):
        try:return subprocess.run(base+list(words),capture_output=True,timeout=8).stdout.decode().strip()
        except subprocess.TimeoutExpired:return ''
    def permissions():
        rows=re.findall(r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]',run('shell','dumpsys','package',PACKAGE).decode())
        if len(rows)!=1:raise ValueError('Ambiguous notification permission')
        return {'granted':rows[0][0]=='true','flags':rows[0][1]}
    def window_ready(prefix):
        deadline=time.monotonic()+20
        while True:
            windows=run('shell','dumpsys','window').decode()
            if re.search(r'mCurrentFocus=Window\{',windows):break
            if time.monotonic()>=deadline:raise TimeoutError('No focused window after boot')
            time.sleep(.3)
        (out/(prefix+'-window.txt')).write_text(windows)
        if not re.search(r'mCurrentFocus=.*Application Error:',windows):return
        if not re.search(r'mCurrentFocus=.*Application Error: com.google.android.bluetooth',windows):raise ValueError('App/system crash dialog blocks the UI')
        (out/(prefix+'-bluetooth-crash.log')).write_bytes(run('logcat','-d','-b','crash'))
        (out/(prefix+'-bluetooth-crash.png')).write_bytes(run('exec-out','screencap','-p'))
        remote='/sdcard/compat-reboot-ui-'+str(uuid.uuid4())+'.xml'
        try:
            run('shell','uiautomator','dump',remote);raw=run('exec-out','cat',remote);(out/(prefix+'-bluetooth-crash.xml')).write_bytes(raw)
            nodes=[n for n in ET.fromstring(raw).iter('node') if n.get('text')=='Close app'];
            if len(nodes)!=1:raise ValueError('No unique observed Bluetooth Close app control')
            a=list(map(int,re.findall(r'-?\d+',nodes[0].get('bounds',''))))
            if len(a)!=4:raise ValueError('Invalid observed system button bounds')
            run('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2));time.sleep(.3)
        finally:run('shell','rm','-f',remote)
    fixtures={}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*unused):pass
        def do_GET(self):
            if self.path=='/generate_204':
                self.send_response(204);self.end_headers();return
            if self.path=='/ready/'+ready_token:
                body=ready_token.encode();self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
            case=self.path.split('/')[1] if self.path.startswith('/') else '';f=fixtures.get(case)
            if f is None:self.send_error(404);return
            with f.lock:
                attempt=len(f.requests)+1;record={'path':self.path,'attempt':attempt};f.requests.append(record)
            f.received.set()
            if attempt==1:f.release.wait(args.boot_wait_seconds+240)
            body=f'<body>{"Late" if attempt==1 else "Fresh"} after reboot {case} attempt={attempt}</body>'.encode()
            try:
                self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);record['response_written']=True
            except (BrokenPipeError,ConnectionResetError):record['client_closed']=True
    ready_token=str(uuid.uuid4())
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True;threading.Thread(target=server.serve_forever,daemon=True).start()
    port=server.server_port;endpoint='tcp:'+str(port);previous=None;reverse_owned=False
    previous_captive={};previous_wifi=''
    report={'status':'running','serial':args.serial,'cases':[],'installed_apks':[],
            'scope':'Actual owned emulator kernel reboot during UIDT; current non-persisted policy, durable interrupted UI and native explicit retry, not automatic cross-boot continuation'}
    try:
        if run('emu','avd','name').decode().splitlines()[0]!=args.expected_avd:raise ValueError('Wrong owned AVD')
        validate_target(args.app_apk,int(run('shell','getprop','ro.build.version.sdk')),run('shell','getprop','ro.product.cpu.abilist').decode().strip().split(','),args.expected_api,args.expected_abi)
        for package,apk in [(PACKAGE,args.app_apk),(PACKAGE+'.test',args.test_apk)]:
            if b'Success' not in run('install','-r','-t',str(apk)):raise ValueError('Install failed')
            path=run('shell','pm','path',package).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk',path):raise ValueError('Unexpected package path')
            actual=run('shell','sha256sum',path[8:]).decode().split()[0]
            if actual!=hashlib.sha256(apk.read_bytes()).hexdigest():raise ValueError('Installed APK differs')
            report['installed_apks'].append({'package':package,'sha256':actual})
        previous=permissions();report['permission_before']=previous;window_ready('initial')
        before_reverse=run('reverse','--list').decode();report['reverse_before']=before_reverse
        if before_reverse.strip():raise ValueError('Reboot gate requires no preexisting reverse mappings')
        previous_captive={key:run('shell','settings','get','global',key).decode().strip() for key in CAPTIVE_PORTAL_SETTINGS}
        report['captive_portal_settings_before']=previous_captive
        previous_wifi=run('shell','settings','get','global','wifi_on').decode().strip()
        report['wifi_setting_before']=previous_wifi
        captive_url=f'http://127.0.0.1:{port}/generate_204'
        for key in CAPTIVE_PORTAL_SETTINGS[1:]:run('shell','settings','put','global',key,captive_url)
        run('reverse',endpoint,endpoint);reverse_owned=True
        run('shell','svc','wifi','disable');time.sleep(2);run('shell','svc','wifi','enable')
        deadline=time.monotonic()+120
        while True:
            connectivity=run('shell','dumpsys','connectivity').decode()
            if re.search(r'NetworkAgentInfo\{[^\n]*IS_VALIDATED[^\n]*',connectivity):break
            if time.monotonic()>=deadline:raise TimeoutError('Owned network validation endpoint did not become VALIDATED')
            time.sleep(1)
        (out/'preflight-network.txt').write_text(connectivity);report['network_preflight_validated']=True
        report['source']=capture(args.adb,args.serial)
        for permission in args.notifications or ['granted','denied']:
            case=str(uuid.uuid4());f=fixtures[case]=Fixture();item={'id':case,'notifications':permission,'status':'running'};report['cases'].append(item)
            boot_before=run('shell','cat','/proc/sys/kernel/random/boot_id').decode().strip();uuid.UUID(boot_before);boot_after=''
            def probe(stage):
                if stage in ['reboot-observe','reboot-retry','cleanup'] or not soft('shell','pidof',PACKAGE):
                    (out/(permission+'-'+stage+'-launch.log')).write_bytes(run('shell','am','start','-W','-n',PACKAGE+'/io.legado.app.ui.AppActivity'))
                window_ready(permission+'-'+stage)
                words=base+['shell','am','instrument','-w','-r','--no-restart','-e','finishActivities','false','-e','class',CLASS+'#exerciseOwnedDownload',
                    '-e','downloadUserStop','yes','-e','caseId',case,'-e','stage',stage,'-e','deathMode','reboot','-e','serverPort',str(port),
                    '-e','bootBefore',boot_before,'-e','bootAfter',boot_after or boot_before,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
                r=subprocess.run(words,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180);(out/(permission+'-'+stage+'.log')).write_bytes(r.stdout);r.check_returncode();validate_instrumentation(r.stdout.decode(),1)
            def read(suffix):
                raw=run('exec-out','run-as',PACKAGE,'cat',f'files/compat-download-user-stop/{case}.{suffix}');(out/(permission+'-'+suffix)).write_bytes(raw);return raw
            try:
                print('START '+permission+': actual kernel reboot and explicit retry',flush=True)
                run('shell','pm','grant' if permission=='granted' else 'revoke',PACKAGE,'android.permission.POST_NOTIFICATIONS')
                if permissions()['granted'] is not (permission=='granted'):raise ValueError('Permission not applied')
                if not any(endpoint in line.split() for line in run('reverse','--list').decode().splitlines()):
                    run('reverse',endpoint,endpoint);reverse_owned=True
                probe('prepare');prepared=json.loads(read('state.json'));item['prepared']=prepared
                if prepared.get('job_persisted') is not False:raise ValueError('Observed job policy differs; update reboot acceptance explicitly')
                if not f.received.wait(20):raise ValueError('No real paused request')
                control=read('instrumented.txt').decode();detached=run('shell','dumpsys','activity','-p',PACKAGE).decode();(out/(permission+'-detached.txt')).write_text(detached);validate_detached(control,detached,prepared['pid'])
                job=run('shell','cmd','jobscheduler','get-job-state','-n','legado.book-transfer',PACKAGE,str(prepared['job'])).decode();(out/(permission+'-job-before.txt')).write_text(job)
                if 'active' not in job.split():raise ValueError('No active original UIDT')
                run('shell','input','keyevent','KEYCODE_HOME');run('reboot',timeout=30)
                start=time.monotonic();deadline=start+args.boot_wait_seconds;next_notice=start+20
                while True:
                    boot_after=soft('shell','cat','/proc/sys/kernel/random/boot_id');completed=soft('shell','getprop','sys.boot_completed');users=soft('shell','dumpsys','user')
                    unlocked=bool(re.search(r'Started users state: \[[^\]\n]*\b0=RUNNING_UNLOCKED\b',users))
                    if boot_after and boot_after!=boot_before and completed=='1' and unlocked:break
                    if time.monotonic()>=deadline:raise TimeoutError('No distinct completed, unlocked kernel boot within allowance')
                    if time.monotonic()>=next_notice:print('WAIT '+permission+': same reboot, elapsed '+str(round(time.monotonic()-start))+'s',flush=True);next_notice+=20
                    time.sleep(1)
                uuid.UUID(boot_after)
                (out/(permission+'-post-boot-users.txt')).write_text(users)
                if run('emu','avd','name').decode().splitlines()[0]!=args.expected_avd:raise ValueError('Device identity changed across reboot')
                proof={'boot_before':boot_before,'boot_after':boot_after,'boot_completed':True,'user_unlocked':True,'boot_wait_ms':round((time.monotonic()-start)*1000)};item['proof']=proof
                run('shell','input','keyevent','KEYCODE_WAKEUP');run('shell','wm','dismiss-keyguard');window_ready(permission+'-post-boot')
                mappings=run('reverse','--list').decode()
                if any(endpoint in line.split() for line in mappings.splitlines()):run('reverse','--remove',endpoint)
                run('reverse',endpoint,endpoint);proof['reverse_reconnected']=True;f.release.set()
                deadline=time.monotonic()+120;retoggled=False
                while True:
                    connectivity=run('shell','dumpsys','connectivity').decode()
                    if re.search(r'NetworkAgentInfo\{[^\n]*IS_VALIDATED[^\n]*',connectivity):break
                    if not retoggled and time.monotonic()>deadline-100:
                        run('shell','svc','wifi','disable');time.sleep(2);run('shell','svc','wifi','enable');retoggled=True
                    if time.monotonic()>=deadline:raise TimeoutError('No connected network after boot')
                    time.sleep(1)
                (out/(permission+'-post-boot-network.txt')).write_text(connectivity);proof['network_connected']=True
                proof['internet_validated']=True;proof['network_revalidated']=retoggled
                request=f'GET /ready/{ready_token} HTTP/1.0\r\nHost: localhost\r\n\r\n'.encode()
                # Keep stdin open until the server closes HTTP/1.0. Immediate EOF
                # makes Android toybox nc exit before forwarding buffered input.
                with subprocess.Popen(base+['shell','toybox','nc','-w','5','-W','5','127.0.0.1',str(port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT) as connection:
                    try:
                        connection.stdin.write(request);connection.stdin.flush();connection.wait(timeout=15)
                        response=connection.stdout.read()
                    except BaseException:
                        connection.kill();connection.wait();raise
                (out/(permission+'-post-boot-fixture-http.txt')).write_bytes(response)
                if b'200 OK' not in response or not response.rstrip().endswith(ready_token.encode()):raise ValueError('Reconnected fixture HTTP route is not usable')
                proof['fixture_reachable']=True
                job=subprocess.run(base+['shell','cmd','jobscheduler','get-job-state','-n','legado.book-transfer',PACKAGE,str(prepared['job'])],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
                (out/(permission+'-post-boot-job.txt')).write_bytes(job.stdout)
                if b'Could not find job' not in job.stdout:raise ValueError('Old non-persisted job not demonstrably absent')
                proof['old_job_missing_before_ui']=True;start=time.monotonic()
                while time.monotonic()-start<10:
                    if len(f.snapshot())!=1:raise ValueError('Unexpected automatic request before UI reopening')
                    time.sleep(.3)
                proof['post_boot_idle_seconds']=time.monotonic()-start;proof['requests_before_open']=len(f.snapshot())
                window_ready(permission+'-before-open')
                probe('reboot-observe');observed=json.loads(read('reboot-observe.json'));item['observed']=observed;time.sleep(2);proof['requests_before_retry']=len(f.snapshot())
                if proof['requests_before_retry']!=1:raise ValueError('Opening UI silently restarted the old transfer')
                probe('reboot-retry');retried=json.loads(read('reboot-retry.json'));item['retried']=retried;item['requests']=f.snapshot()
                validate_reboot(prepared,observed,retried,item['requests'],proof);item['status']='passed'
            finally:
                f.release.set();item['requests']=f.snapshot();probe('cleanup');cleanup=json.loads(read('cleanup.json'));item['cleanup']=cleanup
                if cleanup.get('owned_records_removed') is not True or cleanup.get('baseline_managers_preserved') is not True:raise ValueError('Incomplete UUID cleanup')
                timeout=subprocess.run(base+['exec-out','run-as',PACKAGE,'cat',f'files/compat-download-user-stop/{case}.reboot-retry-timeout.json'],capture_output=True,timeout=30)
                if timeout.returncode==0:(out/(permission+'-reboot-retry-timeout.json')).write_bytes(timeout.stdout)
                for suffix in ['state.json','instrumented.txt','reboot-observe.json','reboot-retry.json','cleanup.json']:
                    run('shell','run-as',PACKAGE,'rm','-f',f'files/compat-download-user-stop/{case}.{suffix}')
                run('shell','run-as',PACKAGE,'rm','-f',f'files/compat-download-user-stop/{case}.reboot-retry-timeout.json')
                mappings=run('reverse','--list').decode()
                if any(endpoint in line.split() for line in mappings.splitlines()):run('reverse','--remove',endpoint)
                reverse_owned=False
            print('PASS '+permission+': real reboot, durable interrupted task/cache, completed native retry',flush=True)
        after=capture(args.adb,args.serial)
        if after['source_sha256']!=report['source']['source_sha256'] or after['apks']!=report['source']['apks']:raise ValueError('Source/APK changed')
        report['checks_passed']=True
    except Exception as error:report.update(status='failed',error=f'{type(error).__name__}: {error}');raise
    finally:
        try:
            if previous is not None:
                run('shell','pm','grant' if previous['granted'] else 'revoke',PACKAGE,'android.permission.POST_NOTIFICATIONS');report['permission_after']=permissions();report['permission_restored']=report['permission_after']==previous
                if not report['permission_restored']:raise ValueError('Permission flags not restored')
            if previous_captive:
                for key,value in previous_captive.items():
                    if value=='null':run('shell','settings','delete','global',key)
                    else:run('shell','settings','put','global',key,value)
                report['captive_portal_settings_after']={key:run('shell','settings','get','global',key).decode().strip() for key in CAPTIVE_PORTAL_SETTINGS}
                report['captive_portal_settings_restored']=report['captive_portal_settings_after']==previous_captive
                if not report['captive_portal_settings_restored']:raise ValueError('Captive portal settings did not restore')
            if previous_wifi:
                current_wifi=run('shell','settings','get','global','wifi_on').decode().strip()
                if current_wifi!=previous_wifi:run('shell','svc','wifi','disable' if previous_wifi=='0' else 'enable')
                report['wifi_setting_after']=run('shell','settings','get','global','wifi_on').decode().strip()
                report['wifi_setting_restored']=report['wifi_setting_after']==previous_wifi
                if not report['wifi_setting_restored']:raise ValueError('Wi-Fi setting did not restore')
            if reverse_owned:run('reverse','--remove',endpoint)
            if 'reverse_before' in report:
                report['reverse_after']=run('reverse','--list').decode();report['reverse_restored']=report['reverse_after']==before_reverse
                if not report['reverse_restored']:raise ValueError('Reverse mappings not restored')
            if report.get('checks_passed') is True:report['status']='passed'
        except Exception as error:report.update(status='failed',restoration_error=repr(error));raise
        finally:
            for f in fixtures.values():f.release.set()
            server.shutdown();server.server_close();(out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':main()
