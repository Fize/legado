#!/usr/bin/env python3
"""Validate API 26-33 WorkManager download, notification cancel and reconnect."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_restore_safety import validate_instrumentation

PACKAGE='com.xiaobaitu.reader.debug'
TEST_PACKAGE=PACKAGE+'.test'
TEST_CLASS='io.legado.app.ui.book.cache.LegacyDownloadWorkerUiTest'
NOTIFICATION='android.permission.POST_NOTIFICATIONS'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--expected-avd',required=True)
    parser.add_argument('--expected-api',type=int,required=True,choices=[26,33])
    parser.add_argument('--expected-abi',default='arm64-v8a',choices=['arm64-v8a','armeabi-v7a','x86_64'])
    parser.add_argument('--legacy-wifi-root',action='store_true')
    parser.add_argument('--output',type=Path,required=True)
    add_apk_arguments(parser)
    args=parser.parse_args();resolve_apks(parser,args)
    if not re.fullmatch(r'emulator-\d+',args.serial):parser.error('Explicit owned emulator required')
    if args.expected_api==26 and not args.legacy_wifi_root:parser.error('API26 userdebug Wi-Fi control requires --legacy-wifi-root')
    out=args.output.resolve()
    if out.exists() and any(out.iterdir()):parser.error('Output must be new or empty')
    out.mkdir(parents=True,exist_ok=True);base=[args.adb,'-s',args.serial]
    def run(*words,timeout=300):
        return subprocess.check_output(base+list(words),stderr=subprocess.STDOUT,timeout=timeout)
    def permission():
        rows=re.findall(r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]',run('shell','dumpsys','package',PACKAGE).decode())
        if len(rows)!=1:raise ValueError('Ambiguous notification permission state')
        return {'granted':rows[0][0]=='true','flags':rows[0][1]}
    report={'status':'running','serial':args.serial,'expected_avd':args.expected_avd,'expected_api':args.expected_api,
            'expected_abi':args.expected_abi,'installed_apks':[],
            'scope':'API26-33 production WorkManager path: real foreground Worker completion, notification cancellation with late-response rejection and explicit new-batch retry, disconnected enqueue and automatic completion after reconnect'}
    previous=None;previous_wifi=''
    try:
        if run('emu','avd','name').decode().splitlines()[0]!=args.expected_avd:raise ValueError('Wrong owned AVD')
        api=int(run('shell','getprop','ro.build.version.sdk').decode().strip())
        abis=run('shell','getprop','ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk,api,abis,args.expected_api,args.expected_abi)
        for package,apk in [(PACKAGE,args.app_apk),(TEST_PACKAGE,args.test_apk)]:
            if b'Success' not in run('install','-r','-t',str(apk)):raise RuntimeError('APK installation failed')
            path=run('shell','pm','path',package).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk',path):raise ValueError('Unexpected installed APK path')
            actual=run('shell','sha256sum',path[8:]).decode().split()[0]
            if actual!=hashlib.sha256(apk.read_bytes()).hexdigest():raise ValueError('Installed APK differs from selected artifact')
            report['installed_apks'].append({'package':package,'sha256':actual})
        previous_wifi=run('shell','settings','get','global','wifi_on').decode().strip()
        report['wifi_before']=previous_wifi
        wifi_prefix='su 0 ' if args.legacy_wifi_root else ''
        run('shell',*(wifi_prefix+'svc wifi enable').split())
        if api>=33:
            previous=permission();report['permission_before']=previous
            run('shell','pm','grant',PACKAGE,NOTIFICATION)
            if not permission()['granted']:raise ValueError('Notification permission did not apply')
        report['source']=capture(args.adb,args.serial)
        report['connectivity_before']=run('shell','dumpsys','connectivity').decode()
        result=run('shell','am','instrument','-w','-r','-e','class',TEST_CLASS,
                   '-e','compatLegacyWifiRoot','true' if args.legacy_wifi_root else 'false',
                   TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner')
        (out/'instrumentation.log').write_bytes(result)
        validate_instrumentation(result.decode(),3)
        report['connectivity_after']=run('shell','dumpsys','connectivity').decode()
        after=capture(args.adb,args.serial)
        if after['source_sha256']!=report['source']['source_sha256'] or after['apks']!=report['source']['apks']:
            raise ValueError('Source or APK identity changed during the Worker checks')
        report['status']='passed'
    except Exception as error:
        report.update(status='failed',error=f'{type(error).__name__}: {error}')
        raise
    finally:
        try:
            if previous is not None:
                run('shell','pm','grant' if previous['granted'] else 'revoke',PACKAGE,NOTIFICATION)
                report['permission_after']=permission();report['permission_restored']=report['permission_after']==previous
                if not report['permission_restored']:raise ValueError('Notification permission state did not restore')
            if previous_wifi:
                current=run('shell','settings','get','global','wifi_on').decode().strip()
                if current!=previous_wifi:run('shell',*(wifi_prefix+'svc wifi ' + ('enable' if previous_wifi!='0' else 'disable')).split())
                after_wifi=run('shell','settings','get','global','wifi_on').decode().strip()
                report['wifi_after']=after_wifi;report['wifi_restored']=after_wifi==previous_wifi
                if not report['wifi_restored']:raise ValueError('Wi-Fi setting did not restore')
        finally:(out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':main()
