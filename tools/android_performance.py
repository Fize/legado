"""Strict parsers and owned data for reproducible, observational device measurements."""
import csv
import hashlib
import io
import json
import math
import re
import statistics
import zipfile

PACKAGE = 'com.xiaobaitu.reader.release'
COMPONENT = PACKAGE + '/io.legado.app.ui.AppActivity'
AVD = 'Legado_CI_Fresh35'
PREFIX = 'CompatPerfV1'


def valid_initial_reader(headers, body):
    """Accept the same chapter whether its title is in the header or the page."""
    marker = 'Paragraph 000-000: deterministic local reading content for page rendering and progress checks.'
    body = body.replace('\u3000', ' ')
    return marker in body and (headers == ['Chapter 000'] or
                               (not headers and body.lstrip().startswith('Chapter 000\n')))


def parse_compilation_state(text):
    """Read the primary ABI state without confusing secondary dex or another package."""
    section = text.split('Dexopt state:', 1)
    if len(section) != 2:
        raise ValueError('Missing dexopt state')
    section = section[1].split('Compiler stats:', 1)[0]
    packages = re.findall(r'^\s*\[([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\]\s*$', section, re.M)
    if packages != [PACKAGE]:
        raise ValueError('Ambiguous or foreign dexopt package')
    rows = re.findall(r'^\s*(\S+): \[status=([^\]]+)\] \[reason=([^\]]+)\] \[primary-abi\]\s*$', section, re.M)
    if len(rows) != 1:
        raise ValueError('Missing or ambiguous primary ABI compilation state')
    abi, compiler_filter, reason = rows[0]
    return {'abi': abi, 'filter': compiler_filter, 'reason': reason}


def fixture_files():
    files = {f'{PREFIX}Book{i:03}.txt':
             (f'{PREFIX} bookshelf item {i:03}\n\n' + (f'Local deterministic item {i:03}.\n' * 30)).encode()
             for i in range(200)}
    files[f'{PREFIX}Reader.txt'] = '\n'.join(
        f'Chapter {chapter:03}\n' + '\n'.join(
            f'Paragraph {chapter:03}-{line:03}: deterministic local reading content for page rendering and progress checks.'
            for line in range(120)) for chapter in range(100)).encode()
    return files


def fixture_archive(path):
    files = fixture_files()
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, content)
    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}
    return manifest


def validate_books(books):
    expected = {f'/data/user/0/{PACKAGE}/files/books/{name}' for name in fixture_files()}
    if len(books) != len(expected) or {b.get('bookUrl') for b in books} != expected:
        raise ValueError('Book set differs from the 201 owned performance fixtures')
    if len({(b.get('name'), b.get('author')) for b in books}) != len(expected):
        raise ValueError('Duplicate fixture book identities')
    return books


def parse_startup(text):
    values = dict(re.findall(r'^([A-Za-z]+):\s*(.*?)\s*$', text, re.M))
    if (values.get('Status') != 'ok' or values.get('LaunchState') != 'COLD'
            or values.get('Activity') != COMPONENT or not re.search(r'^Complete$', text, re.M)):
        raise ValueError('Expected a complete cold launch of the real application shell')
    total, wait = int(values['TotalTime']), int(values['WaitTime'])
    if total <= 0 or wait < total:
        raise ValueError('Invalid startup timing')
    return {'total_time_ms': total, 'wait_time_ms': wait}


def parse_memory(text, pid):
    header = re.search(r'\*\* MEMINFO in pid (\d+) \[([^]]+)\] \*\*', text)
    values = re.findall(r'TOTAL PSS:\s*(\d+)', text)
    if not header or int(header[1]) != pid or header[2] != PACKAGE or len(values) != 1:
        raise ValueError('Memory snapshot is missing or belongs to a different process')
    pss = int(values[0])
    if pss <= 0:
        raise ValueError('Empty memory snapshot')
    return {'total_pss_kib': pss}


def parse_frames(text, since_ns):
    durations, excluded = [], {'flagged': 0, 'before_action': 0, 'incomplete': 0}
    for block in text.split('---PROFILEDATA---'):
        lines = block.strip().splitlines()
        if not lines or not lines[0].startswith('Flags,'):
            continue
        reader = csv.DictReader(lines)
        if not {'Flags', 'IntendedVsync', 'FrameCompleted'}.issubset(reader.fieldnames or []):
            raise ValueError('Unknown frame statistics columns')
        for row in reader:
            if not row.get('Flags') or not re.fullmatch(r'\d+', row['Flags']):
                raise ValueError('Malformed frame statistics row')
            if int(row['Flags']) != 0:
                excluded['flagged'] += 1
                continue
            start, end = int(row['IntendedVsync']), int(row['FrameCompleted'])
            if start < since_ns:
                excluded['before_action'] += 1
                continue
            if end in (0, 9223372036854775807):
                excluded['incomplete'] += 1
                continue
            if not 0 < end - start < 60_000_000_000:
                raise ValueError('Invalid frame timestamp range')
            durations.append((end - start) / 1_000_000)
    if not durations:
        raise ValueError('No completed, unflagged frames in the measured action window')
    return {'frame_count': len(durations), 'durations_ms': durations,
            'median_ms': statistics.median(durations), 'max_ms': max(durations),
            'p95_ms': sorted(durations)[math.ceil(len(durations) * .95) - 1], 'excluded': excluded}


def summary(values):
    if not values:
        raise ValueError('No observations')
    return {'n': len(values), 'min': min(values), 'median': statistics.median(values), 'max': max(values)}


def validate_preparation_resume(previous, serial, apk_sha256):
    if (previous.get('status') != 'failed' or previous.get('kind') != 'prepare'
            or previous.get('serial') != serial or previous.get('apk_sha256') != apk_sha256
            or previous.get('source', {}).get('device', {}).get('api') != '35'):
        raise ValueError('Resume requires the matching failed owned-device preparation report')


def parse_graphics_summary(text, action_since_ns):
    # Android35 repeats these fields per Window; use only the process aggregate.
    text=text.split('\nWindow:',1)[0]
    def one(pattern):
        values=re.findall(pattern,text,re.M)
        if len(values)!=1: raise ValueError('Missing or ambiguous native rendering summary')
        return int(values[0])
    reset=one(r'^Stats since: (\d+)ns$')
    count=one(r'^Total frames rendered: (\d+)$')
    janky=one(r'^Janky frames: (\d+) \(')
    median=one(r'^50th percentile: (\d+)ms$')
    p95=one(r'^95th percentile: (\d+)ms$')
    if count<=0 or janky>count or median>p95 or not 0<=action_since_ns-reset<5_000_000_000:
        raise ValueError('Invalid or stale native rendering summary')
    return {'frames_rendered':count,'janky_frames':janky,'native_median_ms':median,'native_p95_ms':p95,
            'statistics_reset_ns':reset,'scope':'process aggregate','meaning':'Platform gfxinfo counters/quantized percentiles; not the rejected raw FrameCompleted calculation'}
