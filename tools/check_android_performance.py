#!/usr/bin/env python3
"""Prepare or measure an owned, isolated release-APK performance fixture (not a device budget gate)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import uuid
import subprocess
import time
import xml.etree.ElementTree as ET

from android_performance import (AVD, COMPONENT, PACKAGE, PREFIX, fixture_archive, fixture_files, parse_compilation_state,
                                 parse_frames, parse_graphics_summary, parse_memory, parse_startup, summary, validate_books, validate_preparation_resume, valid_initial_reader)
from capture_android_compatibility import ROOT, capture


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--prepare', action='store_true', help='Requires target package absent; installs only in the owned AVD')
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--helper-dex', type=Path)
    parser.add_argument('--resume-preparation', type=Path)
    parser.add_argument('--iterations', type=int, default=5)
    parser.add_argument('--actions', type=int, default=8)
    parser.add_argument('--require-compiler-filter', help='Reject measurements with a different primary ABI compiler filter; never changes compilation state')
    parser.add_argument('--app-apk', type=Path, default=ROOT/'app/build/outputs/apk/app/release/app-app-arm64-v8a-release.apk')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial): parser.error('Only the owned emulator is supported')
    if not 3 <= args.iterations <= 30 or not 4 <= args.actions <= 20: parser.error('Invalid sample counts')
    if args.resume_preparation and not args.prepare: parser.error('Resume only applies to preparation')
    if args.prepare and args.require_compiler_filter: parser.error('Compiler filter checks apply to measurements')
    if not args.prepare and (not args.helper_dex or not args.helper_dex.is_file()): parser.error('--helper-dex is required for measurement')
    if not args.prepare and not args.fixture: parser.error('--fixture is required for measurement')
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()): parser.error('Output must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]
    remote = f'/sdcard/Android/data/{PACKAGE}/files/compat-performance-v1'
    report = {'status': 'running', 'kind': 'prepare' if args.prepare else 'observational-baseline', 'serial': args.serial,
              'scope': 'Owned 201-book shelf, process-cold startup, local reader and shelf interaction; emulator diagnostics, no pass/fail performance budget'}

    def run(*words, check=True):
        r = subprocess.run(base+list(words), capture_output=True, timeout=180)
        if check and r.returncode:
            raise RuntimeError(f'ADB exit {r.returncode}: '+(r.stdout+r.stderr).decode(errors='replace'))
        return r.stdout

    def books():
        raw = run('shell', 'content', 'query', '--uri', f'content://{PACKAGE}.readerProvider/books/query').decode()
        match = re.search(r'^Row: 0 result=(\{.*\})\s*$', raw, re.S)
        if not match: raise ValueError('Missing bookshelf provider result: '+raw)
        data = json.loads(match[1])
        if data['isSuccess'] is not True or not isinstance(data['data'], list):
            raise ValueError('No imported bookshelf data: '+raw)
        return validate_books(data['data'])

    def ui(label, pixels=False):
        run('shell', 'uiautomator', 'dump', remote+'/ui.xml')
        raw = run('exec-out', 'cat', remote+'/ui.xml'); (out/(label+'.xml')).write_bytes(raw)
        if pixels: (out/(label+'.png')).write_bytes(run('exec-out', 'screencap', '-p'))
        return list(ET.fromstring(raw).iter('node'))

    def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def reader_body(nodes):
        found = [n for n in nodes if n.get('resource-id') == PACKAGE+':id/content_text_view' and n.get('content-desc')]
        if len(found) != 1: raise ValueError('Real reader content is not visible')
        return found[0], found[0].get('content-desc')

    def shelf_names(nodes):
        names = sorted({(n.get('text') or n.get('content-desc')) for n in nodes
                        if (n.get('text') or n.get('content-desc') or '').startswith(PREFIX)})
        if not names: raise ValueError('Owned shelf entries are not visible')
        return names

    try:
        avd = run('emu', 'avd', 'name').decode().splitlines()[0]
        if avd != AVD: raise ValueError('Refusing a non-owned AVD: '+avd)
        if run('shell', 'getprop', 'ro.boot.qemu').strip() != b'1' and run('shell', 'getprop', 'ro.kernel.qemu').strip() != b'1':
            raise ValueError('Not an emulator')
        report['source'] = capture(args.adb, args.serial)
        if report['source']['device']['api'] != '35': raise ValueError('This fixture currently supports API35 only')
        report['apk_sha256'] = sha(args.app_apk)
        report['avd'] = avd
        if args.prepare:
            present = b'package:' in run('shell', 'pm', 'path', PACKAGE, check=False)
            if present:
                if not args.resume_preparation:
                    raise ValueError('Preparation requires the release package absent or a matching failed preparation report')
                validate_preparation_resume(json.loads(args.resume_preparation.read_text()), args.serial, report['apk_sha256'])
                report['resumed_from_sha256'] = sha(args.resume_preparation)
            else:
                if args.resume_preparation: raise ValueError('Cannot resume an absent application')
                if b'Success' not in run('install', str(args.app_apk)): raise ValueError('Installation failed')
        installed = run('shell', 'pm', 'path', PACKAGE).decode().strip().removeprefix('package:')
        if run('shell', 'sha256sum', installed).decode().split()[0] != report['apk_sha256']:
            raise ValueError('Installed APK differs from selected APK')
        run('shell', 'mkdir', '-p', remote)
        report['display'] = {key: run('shell', 'wm', key).decode() for key in ['size', 'density']}
        report['font_scale'] = run('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        if args.prepare:
            archive = out/'fixture.zip'; files = fixture_archive(archive)
            if args.resume_preparation:
                if run('shell','sha256sum',remote+'/fixture.zip').decode().split()[0] != sha(archive):
                    raise ValueError('Resume source archive differs from the owned fixture')
            else:
                run('push', str(archive), remote+'/fixture.zip')
                run('shell', 'am', 'start', '-W', '-n', COMPONENT, '-a', 'android.intent.action.VIEW', '-d', 'file://'+remote+'/fixture.zip', '-t', 'application/zip')
            deadline = time.monotonic()+120
            actions = []
            confirmed = set()
            while True:
                try: imported = books(); break
                except ValueError:
                    if time.monotonic() > deadline: raise
                nodes = ui('import-progress')
                for stage, labels in [('import', ['Add to Bookshelf','加入书架']),
                                      ('directory', ['App default folder','应用默认文件夹'])]:
                    hits = [n for n in nodes if n.get('text') in labels]
                    if len(hits)==1 and stage not in confirmed:
                        x1,y1,x2,y2=bounds(hits[0]);run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                        confirmed.add(stage);actions.append({'stage':stage,'label':hits[0].get('text'),'bounds':hits[0].get('bounds')})
                time.sleep(.5)
            report['import_actions'] = actions
            if run('shell','sha256sum',remote+'/fixture.zip').decode().split()[0] != sha(archive):
                raise ValueError('Device source archive changed')
            run('shell','am','start','-W','-n',COMPONENT,'-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER')
            nodes = ui('prepared-shelf', pixels=True); shelf_names(nodes)
            report.update(status='prepared', avd=avd, files=files, archive_sha256=sha(archive),
                          book_urls=sorted(b['bookUrl'] for b in imported), book_count=len(imported),
                          remote_archive=remote+'/fixture.zip', dataset_retained=True)
        else:
            fixture = json.loads(args.fixture.read_text())
            if fixture.get('status') != 'prepared' or fixture.get('avd') != avd or fixture['files'] != {
                name: hashlib.sha256(data).hexdigest() for name,data in sorted(fixture_files().items())}:
                raise ValueError('Invalid prepared fixture manifest')
            if fixture['apk_sha256'] != report['apk_sha256']:
                raise ValueError('Use the same release APK for this initial baseline')
            if run('shell','sha256sum',fixture['remote_archive']).decode().split()[0] != fixture['archive_sha256']:
                raise ValueError('Owned source archive identity changed')
            # Resolve the real local chapter list before fixing the logical data baseline.
            run('shell','am','force-stop',PACKAGE)
            run('shell','am','start','-W','-n',PACKAGE+'/io.legado.app.ui.book.read.ReadBookActivity',
                '--es','bookUrl',f'/data/user/0/{PACKAGE}/files/books/{PREFIX}Reader.txt','--ei','index','0','--ei','chapterPos','0')
            deadline=time.monotonic()+30
            while True:
                try:
                    _,body=reader_body(ui('warmup-reader'))
                    if 'Paragraph' not in body: raise ValueError('Warmup content not ready')
                    break
                except ValueError:
                    if time.monotonic()>deadline: raise
                    time.sleep(.3)
            run('shell','am','force-stop',PACKAGE)
            imported = books()
            reader=next(b for b in imported if b['bookUrl'].endswith('/'+PREFIX+'Reader.txt'))
            if reader['totalChapterNum']<=0: raise ValueError('Local chapter fixture has not been prepared')
            report['warmup_chapters']=reader['totalChapterNum']
            # Normalize only the verified, owned book records through the existing production API.
            for book in imported:
                book.update(durChapterIndex=0, durChapterPos=0, durChapterTime=0, durChapterTitle='', canUpdate=False, lastCheckTime=0, latestChapterTime=0, syncTime=0)
            payload=out/'reset-owned-books.json';payload.write_text(json.dumps(imported)+'\n')
            token=uuid.uuid4().hex
            remote_json='/data/local/tmp/legado-performance-'+token+'.json'
            remote_dex='/data/local/tmp/legado-performance-'+token+'.dex'
            try:
                run('push',str(payload),remote_json);run('push',str(args.helper_dex),remote_dex)
                run('shell','chmod','444',remote_dex)
                raw=run('shell','CLASSPATH='+shlex.quote(remote_dex+':/system/framework/content.jar')+' app_process /system/bin PerformanceFixture '+shlex.quote(remote_json))
                (out/'normalization.log').write_bytes(raw)
                if 'COMPAT_PERFORMANCE_RESET:201' not in raw.decode(): raise ValueError('Owned reset did not complete')
                report['helper_dex_sha256']=sha(args.helper_dex)
            finally:
                run('shell','rm','-f',remote_json,remote_dex)
            imported = books()
            if not all(b['durChapterIndex']==0 and b['durChapterPos']==0 and b['durChapterTime']==0 and b['canUpdate'] is False and b['lastCheckTime']==0 and b['latestChapterTime']==0 and b['syncTime']==0 for b in imported):
                raise ValueError('Owned initial progress was not normalized')
            report['fixture_manifest_sha256'] = sha(args.fixture)
            report['initial_books_sha256'] = hashlib.sha256(json.dumps(imported,sort_keys=True).encode()).hexdigest()
            (out/'initial-books.json').write_text(json.dumps(imported,indent=2)+'\n')
            (out/'surfaceflinger.txt').write_bytes(run('shell','dumpsys','SurfaceFlinger'))
            package_state = run('shell','dumpsys','package',PACKAGE)
            (out/'package.txt').write_bytes(package_state)
            try:
                report['compilation_state'] = parse_compilation_state(package_state.decode())
            except ValueError as unavailable:
                report['compilation_state'] = {'status': 'unavailable', 'error': str(unavailable)}
            if args.require_compiler_filter and report['compilation_state'].get('filter') != args.require_compiler_filter:
                raise ValueError('Primary ABI compiler filter differs from the requested comparison condition')
            (out/'services.txt').write_bytes(run('shell','dumpsys','activity','services',PACKAGE))
            cold=[]
            for i in range(args.iterations):
                run('shell','am','force-stop',PACKAGE)
                if run('shell','pidof',PACKAGE,check=False).strip(): raise ValueError('Process survived force-stop')
                raw=run('shell','am','start','-W','-n',COMPONENT);(out/f'cold-{i:02}.txt').write_bytes(raw)
                sample=parse_startup(raw.decode());pid=int(run('shell','pidof',PACKAGE).strip());sample['pid']=pid
                time.sleep(1)
                nodes=ui(f'cold-{i:02}');shelf_names(nodes)
                memory=run('shell','dumpsys','meminfo',str(pid));(out/f'cold-{i:02}-memory.txt').write_bytes(memory)
                sample.update(parse_memory(memory.decode(),pid));cold.append(sample)
            report['cold_starts']=cold
            scenarios={}
            for scenario in ['shelf','reader']:
                if scenario=='reader':
                    run('shell','am','start','-W','-n',PACKAGE+'/io.legado.app.ui.book.read.ReadBookActivity','--es','bookUrl',f'/data/user/0/{PACKAGE}/files/books/{PREFIX}Reader.txt')
                time.sleep(1)
                nodes=ui(scenario+'-initial',pixels=True)
                if scenario=='reader':
                    node,body=reader_body(nodes);region=bounds(node)
                    titles = [n.get('text') for n in nodes if n.get('resource-id') == PACKAGE+':id/tv_header_left']
                    if not valid_initial_reader(titles, body):
                        raise ValueError('Reader chapter/body does not match the owned long-text fixture')
                    report['reader_initial_headers']=titles
                    report['reader_initial_footers']=[n.get('text') for n in nodes if n.get('resource-id') in [PACKAGE+':id/tv_footer_left',PACKAGE+':id/tv_footer_right']]
                    previous=hashlib.sha256(body.encode()).hexdigest()
                else:
                    groups = [n for n in nodes if n.get('text') in ['全部', 'All']]
                    if len(groups)==1:
                        gx1,gy1,gx2,gy2=bounds(groups[0])
                        run('shell','input','tap',str((gx1+gx2)//2),str((gy1+gy2)//2))
                        nodes=ui('shelf-open-group',pixels=True)
                    previous=shelf_names(nodes)
                    scroll=[n for n in nodes if n.get('scrollable')=='true' and n.get('package')==PACKAGE]
                    if not scroll: raise ValueError('Shelf scroll container missing')
                    region=bounds(max(scroll,key=lambda n:(bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1])))
                samples=[]
                for i in range(args.actions):
                    run('shell','dumpsys','gfxinfo',PACKAGE,'reset')
                    since=int(float(run('shell','cat','/proc/uptime').split()[0])*1_000_000_000)
                    x1,y1,x2,y2=region
                    if scenario=='reader': coords=[x1+(x2-x1)*8//10,(y1+y2)//2,x1+(x2-x1)*2//10,(y1+y2)//2]
                    else: coords=[(x1+x2)//2,y1+(y2-y1)*8//10,(x1+x2)//2,y1+(y2-y1)*2//10]
                    run('shell','input','swipe',*map(str,coords),'250');time.sleep(.5)
                    raw=run('shell','dumpsys','gfxinfo',PACKAGE,'framestats');(out/f'{scenario}-{i:02}-frames.txt').write_bytes(raw)
                    sample={'platform_renderer':parse_graphics_summary(raw.decode(),since),'device_uptime_floor_ns':since}
                    try: sample['raw_timestamp_timings']={'status':'available',**parse_frames(raw.decode(),since)}
                    except ValueError as unavailable:
                        sample['raw_timestamp_timings']={'status':'unavailable','error':str(unavailable)}
                    nodes=ui(f'{scenario}-{i:02}',pixels=i==args.actions-1)
                    current=hashlib.sha256(reader_body(nodes)[1].encode()).hexdigest() if scenario=='reader' else shelf_names(nodes)
                    if current==previous: raise ValueError(f'{scenario} interaction did not change real visible content')
                    sample['visible_content']=current;samples.append(sample);previous=current
                pid=int(run('shell','pidof',PACKAGE).strip());memory=run('shell','dumpsys','meminfo',str(pid));(out/(scenario+'-memory.txt')).write_bytes(memory)
                scenarios[scenario]={'samples':samples,'memory':parse_memory(memory.decode(),pid)}
            unavailable=sum(s['raw_timestamp_timings']['status']=='unavailable' for value in scenarios.values() for s in value['samples'])
            report.update(status='captured_with_raw_frame_gaps' if unavailable else 'captured',raw_frame_gap_samples=unavailable,scenarios=scenarios,cold_start_ms=summary([r['total_time_ms'] for r in cold]),
                          cold_pss_kib=summary([r['total_pss_kib'] for r in cold]),book_count=len(books()),
                          measurement='am start -W TotalTime; renderer completion minus IntendedVsync (not presentation latency); exact PID PSS',
                          limitations=['Emulator observations only; no device performance certification or regression thresholds',
                                       'Process cold, filesystem/page caches and ART profiles not cleared; no TTFD metric',
                                       'Owned progress normalized; reading records/runtime caches can accumulate between runs',
                                       'No large-image, physical device, memory-leak or full performance acceptance yet',
                                       'Raw completed-frame timings are unavailable for an action if any invalid completed timestamp is present; original errors and native counters remain separate'])
        if capture(args.adb,args.serial)['source_sha256'] != report['source']['source_sha256']:
            raise ValueError('Source changed during preparation/measurement')
    except Exception as failure:
        report.update(status='failed',error=f'{type(failure).__name__}: {failure}')
        raise
    finally:
        (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(report['status'],report.get('cold_start_ms',report.get('book_count')))


if __name__=='__main__': main()
