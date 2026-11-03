#!/usr/bin/env python3
"""Verify natural UIDT recovery after SIGKILL while detached from instrumentation."""
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

from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_export_timeout import validate_detached
from check_android_restore_safety import validate_instrumentation
from check_android_download_user_stop import PACKAGE, CLASS


def validate_automatic(prepared, running, done, requests, proof):
    if any(proof.get(k) is not True for k in ['old_pid_gone', 'http_second_before_reattach', 'active_job_before_reattach', 'not_instrumented_at_restart']):
        raise ValueError('Need actual detached OS restart before any instrumentation attachment')
    pid = proof.get('new_pid_before_reattach')
    if type(pid) is not int or pid <= 0 or pid == prepared.get('pid'):
        raise ValueError('No new process')
    for result in [running, done]:
        if result.get('id') != prepared['id'] or result.get('pid') != pid or result.get('old_pid') != prepared['pid']:
            raise ValueError('Mixed fixture/process identity')
        if result.get('exit_reason') != 2 or result.get('exit_status') != 9 or result.get('exit_time', 0) < prepared['wall_time_ms']:
            raise ValueError('Not the requested platform SIGKILL exit')
        for field in ['task', 'batch', 'dispatch']:
            if result.get(field) != prepared[field]: raise ValueError('Automatic retry changed '+field)
        if result.get('no_ui_or_manual_recovery') is not True or result.get('saved_chapter_preserved') is not True:
            raise ValueError('Manual UI recovery or cached text loss is not automatic recovery')
    if (running.get('phase') != 'running' or not running.get('owner') or running['owner'] == prepared['owner']
            or not running.get('process') or running['process'] == prepared['process'] or running['process'] != done.get('process')):
        raise ValueError('No fresh execution ownership in the same surviving batch')
    if done.get('phase') != 'completed' or done.get('owner') != '' or done.get('fresh_chapter_saved') is not True:
        raise ValueError('A reclaimed job is not yet completed content')
    if len(requests) != 2 or [r.get('attempt') for r in requests] != [1,2] or any(r.get('path') != '/'+prepared['id']+'/chapter/1' for r in requests):
        raise ValueError('Need exactly two uncached-chapter requests, with no saved-chapter re-fetch')


class PausedDownload:
    def __init__(self):
        self.first = threading.Event(); self.second = threading.Event()
        self.release_first = threading.Event(); self.release_second = threading.Event()
        self.lock = threading.Lock(); self.requests = []
    def snapshot(self):
        with self.lock: return [dict(x) for x in self.requests]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True); parser.add_argument('--serial',required=True)
    parser.add_argument('--expected-avd',required=True); parser.add_argument('--expected-api',type=int,required=True)
    parser.add_argument('--expected-abi',choices=['arm64-v8a','armeabi-v7a','x86_64'],required=True)
    parser.add_argument('--notifications',choices=['granted','denied'],action='append')
    parser.add_argument('--retry-wait-ms',type=int,default=2100000)
    parser.add_argument('--output',type=Path,required=True); add_apk_arguments(parser)
    args=parser.parse_args(); resolve_apks(parser,args)
    if not re.fullmatch(r'emulator-\d+',args.serial) or args.expected_api<34 or not 90000<=args.retry_wait_ms<=2400000:
        parser.error('Explicit API34+ emulator and 90s-40min natural retry observation required')
    out=args.output.resolve()
    if out.exists() and any(out.iterdir()): parser.error('Output must be new or empty')
    out.mkdir(parents=True,exist_ok=True); base=[args.adb,'-s',args.serial]
    def run(*words,timeout=120): return subprocess.check_output(base+list(words),stderr=subprocess.STDOUT,timeout=timeout)
    def permissions():
        rows=re.findall(r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]',run('shell','dumpsys','package',PACKAGE).decode())
        if len(rows)!=1: raise ValueError('Ambiguous notification permission')
        return {'granted':rows[0][0]=='true','flags':rows[0][1]}
    fixtures={}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*unused): pass
        def do_GET(self):
            case=self.path.split('/')[1] if self.path.startswith('/') else ''; f=fixtures.get(case)
            if f is None: self.send_error(404); return
            with f.lock:
                attempt=len(f.requests)+1; row={'attempt':attempt,'path':self.path}; f.requests.append(row)
            if attempt==1: f.first.set(); f.release_first.wait(args.retry_wait_ms/1000+180)
            else: f.second.set(); f.release_second.wait(180)
            body=f'<body>{"Late" if attempt==1 else "Fresh"} after process death {case} attempt={attempt}</body>'.encode()
            try:
                self.send_response(200); self.send_header('Content-Type','text/html'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body); row['response_written']=True
            except (BrokenPipeError,ConnectionResetError): row['client_closed']=True
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler); server.daemon_threads=True
    threading.Thread(target=server.serve_forever,daemon=True).start(); port=server.server_port; endpoint='tcp:'+str(port)
    previous=None; reverse_owned=False
    report={'status':'running','serial':args.serial,'cases':[],'installed_apks':[],
            'scope':'Real SIGKILL after detaching instrumentation; host waits for actual OS-started new PID and second HTTP before reattaching, no UI/recovery/job forcing until cleanup'}
    try:
        if run('emu','avd','name').decode().splitlines()[0]!=args.expected_avd: raise ValueError('Wrong owned AVD')
        validate_target(args.app_apk,int(run('shell','getprop','ro.build.version.sdk')),run('shell','getprop','ro.product.cpu.abilist').decode().strip().split(','),args.expected_api,args.expected_abi)
        for package,apk in [(PACKAGE,args.app_apk),(PACKAGE+'.test',args.test_apk)]:
            if b'Success' not in run('install','-r','-t',str(apk)): raise ValueError('APK install failed')
            path=run('shell','pm','path',package).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk',path): raise ValueError('Unexpected package path')
            actual=run('shell','sha256sum',path[8:]).decode().split()[0]
            if actual!=hashlib.sha256(apk.read_bytes()).hexdigest(): raise ValueError('Installed APK differs')
            report['installed_apks'].append({'package':package,'sha256':actual})
        previous=permissions(); report['permission_before']=previous
        windows=run('shell','dumpsys','window').decode(); (out/'window-readiness.txt').write_text(windows)
        if re.search(r'mCurrentFocus=.*Application Error:',windows): raise ValueError('System crash dialog covers the device')
        before_reverse=run('reverse','--list').decode(); report['reverse_before']=before_reverse
        if any(endpoint in line.split() for line in before_reverse.splitlines()): raise ValueError('Reverse endpoint already owned')
        run('reverse',endpoint,endpoint); reverse_owned=True; report['source']=capture(args.adb,args.serial)
        for permission in args.notifications or ['granted','denied']:
            case=str(uuid.uuid4()); f=fixtures[case]=PausedDownload(); item={'id':case,'notifications':permission,'status':'running'}; report['cases'].append(item)
            def pids(): return subprocess.run(base+['shell','pidof',PACKAGE],capture_output=True,timeout=10).stdout.decode().split()
            def probe(stage,may_launch=False):
                if not pids():
                    if not may_launch: raise ValueError('The OS must start and preserve the process before attachment')
                    (out/(permission+'-'+stage+'-launch.log')).write_bytes(run('shell','am','start','-W','-n',PACKAGE+'/io.legado.app.ui.AppActivity'))
                words=base+['shell','am','instrument','-w','-r','--no-restart','-e','finishActivities','false','-e','class',CLASS+'#exerciseOwnedDownload',
                    '-e','downloadUserStop','yes','-e','caseId',case,'-e','stage',stage,'-e','deathMode','sigkill','-e','serverPort',str(port),PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
                result=subprocess.run(words,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
                (out/(permission+'-'+stage+'.log')).write_bytes(result.stdout); result.check_returncode(); validate_instrumentation(result.stdout.decode(),1)
            def read(suffix):
                raw=run('exec-out','run-as',PACKAGE,'cat',f'files/compat-download-user-stop/{case}.{suffix}'); (out/(permission+'-'+suffix)).write_bytes(raw); return raw
            try:
                print('START '+permission+': detached SIGKILL and natural OS recovery',flush=True)
                run('shell','pm','grant' if permission=='granted' else 'revoke',PACKAGE,'android.permission.POST_NOTIFICATIONS')
                if permissions()['granted'] is not (permission=='granted'): raise ValueError('Notification permission not applied')
                probe('prepare',True); prepared=json.loads(read('state.json')); item['prepared']=prepared
                if not f.first.wait(20): raise ValueError('No actual first request')
                control=read('instrumented.txt').decode(); detached=run('shell','dumpsys','activity','-p',PACKAGE).decode(); (out/(permission+'-detached.txt')).write_text(detached)
                validate_detached(control,detached,prepared['pid'])
                proc=run('exec-out','run-as',PACKAGE,'cat',f'/proc/{prepared["pid"]}/status').decode(); (out/(permission+'-process-before.txt')).write_text(proc)
                uid=re.search(r'^Uid:\s+(\d+)',proc,re.M)
                if not uid or int(uid[1])!=prepared['uid']: raise ValueError('PID no longer belongs to prepared app UID')
                run('shell','input','keyevent','KEYCODE_HOME')
                active=run('shell','cmd','jobscheduler','get-job-state','-n','legado.book-transfer',PACKAGE,str(prepared['job'])).decode()
                (out/(permission+'-job-before.txt')).write_text(active)
                if 'active' not in active.split(): raise ValueError('No active detached UIDT before SIGKILL')
                run('shell','run-as',PACKAGE,'kill','-9',str(prepared['pid']))
                death_deadline=time.monotonic()+5
                while str(prepared['pid']) in pids():
                    if time.monotonic()>=death_deadline: raise ValueError('Old PID did not die')
                    time.sleep(.05)
                proof={'old_pid_gone':True}; item['proof']=proof; f.release_first.set()
                start=time.monotonic(); deadline=start+args.retry_wait_ms/1000; next_notice=start+30
                while not f.second.wait(.5):
                    if time.monotonic()>=deadline: raise TimeoutError('No natural OS retry/second request within observation allowance')
                    if time.monotonic()>=next_notice:
                        print('WAIT '+permission+': no forced job; elapsed '+str(round(time.monotonic()-start))+'s, live PIDs '+str(pids()),flush=True); next_notice+=30
                live=pids()
                if len(live)!=1 or live[0]==str(prepared['pid']): raise ValueError('Second request lacks a distinct actual new app PID')
                new_pid=int(live[0]); proof.update(new_pid_before_reattach=new_pid,http_second_before_reattach=True,automatic_restart_wait_ms=round((time.monotonic()-start)*1000))
                restart=run('shell','dumpsys','activity','-p',PACKAGE).decode(); (out/(permission+'-restart-before-attach.txt')).write_text(restart); validate_detached(control,restart,new_pid); proof['not_instrumented_at_restart']=True
                active=run('shell','cmd','jobscheduler','get-job-state','-n','legado.book-transfer',PACKAGE,str(prepared['job'])).decode(); (out/(permission+'-job-restarted.txt')).write_text(active)
                if 'active' not in active.split(): raise ValueError('No active original job at natural restart')
                proof['active_job_before_reattach']=True
                probe('auto-running'); running=json.loads(read('auto-running.json')); item['running']=running
                f.release_second.set(); probe('auto-complete'); done=json.loads(read('auto-complete.json')); item['completed']=done; item['requests']=f.snapshot()
                validate_automatic(prepared,running,done,item['requests'],proof); item['status']='passed'
            finally:
                f.release_first.set(); f.release_second.set(); item['requests']=f.snapshot()
                probe('cleanup',True); cleanup=json.loads(read('cleanup.json')); item['cleanup']=cleanup
                if cleanup.get('owned_records_removed') is not True or cleanup.get('baseline_managers_preserved') is not True: raise ValueError('Incomplete cleanup')
                for suffix in ['state.json','instrumented.txt','auto-running.json','auto-complete.json','cleanup.json']:
                    run('shell','run-as',PACKAGE,'rm','-f',f'files/compat-download-user-stop/{case}.{suffix}')
            print('PASS '+permission+': actual OS new process, same batch/new owner and fresh content',flush=True)
        after=capture(args.adb,args.serial)
        if after['source_sha256']!=report['source']['source_sha256'] or after['apks']!=report['source']['apks']: raise ValueError('Source/APK changed')
        report['checks_passed']=True
    except Exception as error: report.update(status='failed',error=f'{type(error).__name__}: {error}'); raise
    finally:
        try:
            if previous is not None:
                run('shell','pm','grant' if previous['granted'] else 'revoke',PACKAGE,'android.permission.POST_NOTIFICATIONS'); report['permission_after']=permissions(); report['permission_restored']=report['permission_after']==previous
                if not report['permission_restored']: raise ValueError('Notification permission flags not restored')
            if reverse_owned:
                run('reverse','--remove',endpoint); report['reverse_after']=run('reverse','--list').decode(); report['reverse_restored']=report['reverse_after']==before_reverse
                if not report['reverse_restored']: raise ValueError('Reverse mappings not restored')
            if report.get('checks_passed') is True: report['status']='passed'
        except Exception as error: report.update(status='failed',restoration_error=repr(error)); raise
        finally:
            for f in fixtures.values(): f.release_first.set(); f.release_second.set()
            server.shutdown(); server.server_close(); (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__': main()
