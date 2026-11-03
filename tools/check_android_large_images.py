#!/usr/bin/env python3
"""Observe real release photo-viewer cycles with owned large PNGs; never a memory budget gate."""
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
from android_large_image import create_fixtures
from android_performance import AVD, PACKAGE, COMPONENT, validate_books, parse_memory
from capture_android_compatibility import capture


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True)
    parser.add_argument('--fixture',required=True,type=Path);parser.add_argument('--helper-dex',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path);parser.add_argument('--cycles',type=int,default=6)
    args=parser.parse_args()
    if not re.fullmatch(r'emulator-\d+',args.serial) or not 3<=args.cycles<=12:parser.error('Owned emulator and 3..12 cycles required')
    out=args.output.resolve()
    if out.exists() and any(out.iterdir()):parser.error('Output must be new or empty')
    out.mkdir(parents=True,exist_ok=True)
    base=[args.adb,'-s',args.serial];remote=f'/sdcard/Android/data/{PACKAGE}/files/compat-performance-v1'
    target=f'/data/user/0/{PACKAGE}/files/books/CompatPerfV1Book000.txt'
    report={'status':'running','cycles':[]};original=None;current=None;failure=None;created=[]
    def run(*a):return subprocess.check_output(base+list(a),stderr=subprocess.STDOUT,timeout=180)
    def books():
        raw=run('shell','content','query','--uri',f'content://{PACKAGE}.readerProvider/books/query').decode()
        data=json.loads(re.search(r'^Row: 0 result=(\{.*\})\s*$',raw,re.S)[1])
        if not data['isSuccess']:raise ValueError('Bookshelf query failed')
        return validate_books(data['data'])
    def ui(label,pixels=False):
        run('shell','uiautomator','dump',remote+'/large-image-ui.xml')
        raw=run('exec-out','cat',remote+'/large-image-ui.xml');(out/(label+'.xml')).write_bytes(raw)
        if pixels:(out/(label+'.png')).write_bytes(run('exec-out','screencap','-p'))
        return list(ET.fromstring(raw).iter('node'))
    def rectangle(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
    def tap(n):
        x1,y1,x2,y2=rectangle(n);run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    def cover(expected,next_value,label):
        token=uuid.uuid4().hex;path=out/(label+'-cover.json');path.write_text(json.dumps({'expected':expected,'next':next_value})+'\n')
        prefix='/data/local/tmp/legado-performance-'+token
        try:
            run('push',str(path),prefix+'.json');run('push',str(args.helper_dex),prefix+'.dex');run('shell','chmod','444',prefix+'.dex')
            data=run('shell','CLASSPATH='+shlex.quote(prefix+'.dex:/system/framework/content.jar')+' app_process /system/bin PerformanceFixture '+shlex.quote(prefix+'.json')+' cover')
            (out/(label+'-bridge.log')).write_bytes(data)
            if b'COMPAT_LARGE_IMAGE_COVER:1' not in data:raise ValueError('Owned cover update not completed')
            selected=next(b for b in books() if b['bookUrl']==target)
            if selected.get('customCoverUrl')!=next_value:raise ValueError('Cover readback mismatch')
        finally:run('shell','rm','-f',prefix+'.json',prefix+'.dex')
    def memory(label):
        pid=int(run('shell','pidof',PACKAGE).strip());data=run('shell','dumpsys','meminfo',str(pid))
        (out/(label+'-memory.txt')).write_bytes(data);return {'pid':pid,**parse_memory(data.decode(),pid)}
    try:
        if run('emu','avd','name').decode().splitlines()[0]!=AVD:raise ValueError('Not the owned performance AVD')
        fixture=json.loads(args.fixture.read_text());report['source']=capture(args.adb,args.serial)
        if report['source']['device']['api']!='35' or fixture['status']!='prepared':raise ValueError('API35 prepared fixture required')
        installed=run('shell','pm','path',PACKAGE).decode().strip().removeprefix('package:')
        if run('shell','sha256sum',installed).decode().split()[0]!=fixture['apk_sha256']:raise ValueError('Unexpected release APK')
        report['apk_sha256']=fixture['apk_sha256'];report['helper_dex_sha256']=hashlib.sha256(args.helper_dex.read_bytes()).hexdigest()
        run('shell','am','force-stop',PACKAGE)
        before=books();(out/'books-before.json').write_text(json.dumps(before,indent=2)+'\n')
        original=next(b for b in before if b['bookUrl']==target).get('customCoverUrl');current=original
        if original is not None:raise ValueError('Owned fixture must start with its original default cover')
        assets=create_fixtures(out/'assets');report['assets']=assets
        run('shell','mkdir','-p',remote)
        prior=set(run('shell','ls',remote).decode().splitlines())
        if prior-set([a['file'] for a in assets]+['ui.xml','fixture.zip','large-image-ui.xml']):raise ValueError('Unknown files in fixture directory')
        for asset in assets:
            if asset['file'] in prior:
                if run('shell','sha256sum',remote+'/'+asset['file']).decode().split()[0]!=asset['sha256']:raise ValueError('Existing fixture PNG identity differs')
            else:
                created.append(remote+'/'+asset['file'])
                run('push',str(out/'assets'/asset['file']),remote+'/'+asset['file'])
            if run('shell','sha256sum',remote+'/'+asset['file']).decode().split()[0]!=asset['sha256']:raise ValueError('Device PNG differs from fixture')
        for i in range(args.cycles):
            asset=assets[i%len(assets)];label=f'cycle-{i:02}';next_value='file://'+remote+'/'+asset['file']
            cover(current,next_value,label);current=next_value
            request=str(uuid.uuid4());route=json.dumps({'type':'io.legado.app.ui.navigation.BookInfoRoute','bookUrl':target,'requestId':request,'previewKey':request},separators=(',',':'))
            launch=run('shell','am start -W -f 0x10008000 -n '+COMPONENT+' --es initialDestination '+shlex.quote(route))
            (out/(label+'-launch.txt')).write_bytes(launch)
            if b'intent has been delivered' in launch:raise ValueError('Detail intent reused an existing instance')
            deadline=time.monotonic()+30
            while True:
                nodes=ui(label+'-detail');hits=[n for n in nodes if n.get('content-desc')=='CompatPerfV1Book000']
                if len(hits)==1:break
                if time.monotonic()>deadline:raise ValueError('Real detail cover not visible')
                time.sleep(.2)
            selected=next(b for b in books() if b['bookUrl']==target)
            (out/(label+'-detail-book.json')).write_text(json.dumps(selected,indent=2)+'\n')
            if selected.get('customCoverUrl')!=next_value:raise ValueError('Cover lost while loading book details')
            before_memory=memory(label+'-before')
            x1,y1,x2,y2=rectangle(hits[0]);start=time.monotonic()
            run('shell','input','swipe',str((x1+x2)//2),str((y1+y2)//2),str((x1+x2)//2),str((y1+y2)//2),'900')
            while True:
                nodes=ui(label+'-opened')
                images=[n for n in nodes if n.get('content-desc')=='Photo viewer']
                if images:break
                if time.monotonic()-start>30:raise ValueError('Real photo viewer did not become ready')
                time.sleep(.2)
            ready_ms=(time.monotonic()-start)*1000
            ui(label+'-image',pixels=True);opened=memory(label+'-open')
            run('shell','input','keyevent','70');run('shell','input','keyevent','22')
            ui(label+'-zoom',pixels=True);zoomed=memory(label+'-zoom')
            nodes=ui(label+'-close-target');close=[n for n in nodes if n.get('content-desc')=='Close']
            if len(close)!=1:raise ValueError('Close action is not uniquely visible')
            tap(close[0]);nodes=ui(label+'-closed')
            if any(n.get('content-desc')=='Photo viewer' for n in nodes):raise ValueError('Photo viewer did not close')
            closed=memory(label+'-closed')
            run('shell','input','keyevent','4')
            run('shell','am start -W -f 0x10008000 -n '+COMPONENT+' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER')
            nodes=ui(label+'-root')
            if not any(n.get('text')=='Bookshelf' for n in nodes):raise ValueError('Did not return to root shelf after detail close')
            root_memory=memory(label+'-root')
            report['cycles'].append({'cycle':i,'asset':asset['name'],'ready_observation_ms':ready_ms,
                'before':before_memory,'open':opened,'zoom':zoomed,'closed':closed,'root':root_memory,
                'entry':'physical 900ms long press on actual owned cover','zoom_commands_sent':[70,22],'viewer_ready':True,'normal_close_observed':True})
        report['status']='observed'
        report['limits']=['Software-emulator observations, no performance or memory-leak certification',
                         'Readiness includes shell/UI-automator observation overhead; not isolated decode latency',
                         'PNG dimensions/source hash and visible patterns verified; decoded bitmap dimensions not instrumented',
                         'Zoom commands and screenshots retained; no automated scale-state assertion',
                         'Glide/renderer caches are not cleared or forced-GC; private photo-request files not directly audited']
    except Exception as error:
        report.update(status='failed',error=f'{type(error).__name__}: {error}');failure=error
        try:
            ui('failed-screen',pixels=True)
            if 'before' in locals():report['target_at_failure']=next(b for b in books() if b['bookUrl']==target)
        except Exception as diagnostic:report['diagnostic_error']=str(diagnostic)
    finally:
        try:
            if 'before' in locals():
                selected=next(b for b in books() if b['bookUrl']==target)
                actual=selected.get('customCoverUrl')
                if actual!=original:
                    allowed={'file://'+remote+'/'+a['file'] for a in report.get('assets',[])}
                    if actual not in allowed:raise ValueError('Unexpected cover mutation; refusing to overwrite')
                    cover(actual,original,'restore')
                report['original_cover_restored']=True
                after=books();(out/'books-after.json').write_text(json.dumps(after,indent=2)+'\n')
                report['other_200_books_unchanged']={b['bookUrl']:b for b in before if b['bookUrl']!=target}=={b['bookUrl']:b for b in after if b['bookUrl']!=target}
                if not report['other_200_books_unchanged']:raise ValueError('Non-target fixture books changed')
                old=next(b for b in before if b['bookUrl']==target);new=next(b for b in after if b['bookUrl']==target)
                report['target_metadata_changes']=[k for k in old.keys()|new.keys() if old.get(k)!=new.get(k)]
        except Exception as error:
            report.update(status='failed',restore_error=str(error));failure=failure or error
        try:
            if 'prior' in locals() and 'large-image-ui.xml' not in prior:created.append(remote+'/large-image-ui.xml')
            if created:
                run('shell','rm','-f',*created)
                remaining=set(run('shell','ls',remote).decode().splitlines())
                if any(Path(name).name in remaining for name in created):raise ValueError('Owned PNG/UI files remain')
            report['owned_files_removed']=created
            if 'source' in report and capture(args.adb,args.serial)['source_sha256']!=report['source']['source_sha256']:
                raise ValueError('Source changed while observing images')
        except Exception as error:
            report.update(status='failed',cleanup_error=str(error));failure=failure or error
        (out/'evidence.json').write_text(json.dumps(report,indent=2)+'\n')
    if failure:raise failure
    print(report['status'],len(report['cycles']))


if __name__=='__main__':main()
