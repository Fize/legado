#!/usr/bin/env python3
"""Exercise the selected production Cronet transport, dynamic library, and explicit disabled socket path."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from check_android_native_pages import elf_errors

PACKAGE = 'com.xiaobaitu.reader.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
TEST = 'io.legado.app.lib.cronet.CronetNativeCompatibilityTest'
REPAIR = 'io.legado.app.lib.cronet.CronetNativePreferenceRepair'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fresh-download', action='store_true', help="Remove only this run's initially absent, hash-checked native dependency before testing")
    parser.add_argument('--initially-absent-evidence', type=Path)
    parser.add_argument('--extended', action='store_true', help='Also exercise pinned HTTPS/ALPN, untrusted TLS, refused connection, and explicit proxy')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def adb(*words, check=True, timeout=30):
        run = subprocess.run(base + list(words), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if check and run.returncode:
            raise RuntimeError((run.stdout + run.stderr).decode(errors='replace'))
        return run.stdout

    def folders():
        return {name for name in adb('shell', 'run-as', PACKAGE, 'ls', 'cache/cronet-native-compat', check=False).decode().splitlines()
                if re.fullmatch(r'[a-f0-9-]{36}', name)}

    def read(path):
        return adb('exec-out', 'run-as', PACKAGE, 'cat', path)

    def installed_hash(package):
        path = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
        return adb('shell', 'sha256sum', path).decode().split()[0]

    repo = Path(__file__).resolve().parent.parent
    asset = json.loads((repo / 'app/src/main/assets/cronet.json').read_text())
    library_name = 'libcronet.' + asset['version'] + '.so'
    library_path = 'app_cronet/arm64-v8a/' + library_name
    old = folders()
    previous_file = adb('shell', 'run-as', PACKAGE, 'ls', '-l', library_path, check=False).decode()
    result = {'status': 'running', 'serial': args.serial, 'version': asset['version'],
              'debug_apk_sha256': installed_hash(PACKAGE), 'test_apk_sha256': installed_hash(PACKAGE + '.test'),
              'library_present_before': 'No such file' not in previous_file and library_name in previous_file,
              'host_preference_repairs': [], 'scope': ('Production interceptor; native HTTPS/HTTP2, TLS errors and explicit HTTP proxy' if args.extended else 'Production interceptor; local HTTP/1 service; pinned official native download')}
    mode = re.search(r'(?:^|\s)(-[rwxstST-]{9})(?=\s)', previous_file)
    result['library_readonly_before'] = not any(mode.group(1)[i] == 'w' for i in [2, 5, 8]) if mode else None
    if args.fresh_download:
        assert args.initially_absent_evidence, "Fresh download requires this device's initial absence evidence"
        initial = json.loads(args.initially_absent_evidence.read_text())
        assert initial['serial'] == args.serial and initial['library_present_before'] is False
        current = read(library_path)
        assert hashlib.md5(current).hexdigest() == asset['arm64-v8a']
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'run-as', PACKAGE, 'rm', library_path)
        result['owned_test_dependency_removed_for_fresh_download'] = True
    basic_methods = ['nativeGetUsesDownloadedLibraryAndSelectedTransport', 'redirectAndGzipRemainOnNativeTransport',
        'largePostUsesTheRealNativeUploadProvider', 'cancellationDoesNotRetryViaTheSocketTransport',
        'serverErrorDoesNotSilentlyChangeTransport', 'disabledCronetKeepsTheExistingSocketPath']
    selected_test = TEST if args.extended else ','.join(TEST + '#' + method for method in basic_methods)
    expected_tests = 10 if args.extended else 6
    collected = []
    try:
        try:
            run = subprocess.run(base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', selected_test,
                '-e', 'nativeCronetCompatibility', 'yes', RUNNER], stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=300)
            log = run.stdout
        except subprocess.TimeoutExpired as error:
            log = error.stdout or b''
            (args.output / 'instrumentation.log').write_bytes(log)
            adb('shell', 'am', 'force-stop', PACKAGE)
            raise
        finally:
            for name in sorted(folders() - old):
                relative = 'cache/cronet-native-compat/' + name
                directory = args.output / name
                directory.mkdir(exist_ok=True)
                original = json.loads(read(relative + '/original-pref.json'))
                assert original['id'] == name
                (directory / 'original-pref.json').write_text(json.dumps(original, indent=2) + '\n')
                try:
                    restored = json.loads(read(relative + '/preference-restored.json'))
                except (ValueError, UnicodeDecodeError):
                    repaired = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', REPAIR,
                        '-e', 'cronetCompatibilityRepair', 'yes', '-e', 'cronetCompatibilityId', name, RUNNER, timeout=45)
                    (directory / 'repair.log').write_bytes(repaired)
                    assert b'OK (1 test)' in repaired
                    restored = json.loads(read(relative + '/preference-restored.json'))
                    result['host_preference_repairs'].append(name)
                assert restored['id'] == name and restored['restored']
                (directory / 'preference-restored.json').write_text(json.dumps(restored, indent=2) + '\n')
                try:
                    case = json.loads(read(relative + '/result.json'))
                except (ValueError, UnicodeDecodeError):
                    case = {'case': name, 'status': 'incomplete'}
                (directory / 'result.json').write_text(json.dumps(case, indent=2) + '\n')
                collected.append(case)
                # Only remove a generated probe directory once its original switch is restored.
                adb('shell', 'run-as', PACKAGE, 'rm', '-r', relative)
        (args.output / 'instrumentation.log').write_bytes(log)
        text = log.decode(errors='replace')
        assert f'OK ({expected_tests} tests)' in text and 'INSTRUMENTATION_STATUS_CODE: -2' not in text and 'INSTRUMENTATION_STATUS_CODE: -3' not in text, text[-3000:]
        assert len(collected) == expected_tests and all(c['status'] == 'passed' for c in collected)
        expected = {'native-get', 'native-redirect-gzip', 'native-large-post', 'native-cancel', 'native-server-error', 'cronet-disabled'}
        if args.extended:
            expected.update({'native-https-pinned', 'native-untrusted-tls', 'native-refused-connection', 'explicit-http-proxy'})
        assert {c['case'] for c in collected} == expected
        natives = [c for c in collected if c['case'] not in {'cronet-disabled', 'explicit-http-proxy'}]
        assert all(c['socketPasses'] == 0 and c['executableNativeMapping'] and c['libraryReadOnly'] and asset['version'] in c['version'] for c in natives)
        assert next(c for c in collected if c['case'] == 'cronet-disabled')['socketPasses'] == 1
        library = read(library_path)
        assert hashlib.md5(library).hexdigest() == asset['arm64-v8a']
        digest = hashlib.sha256(library).hexdigest()
        assert all(c['librarySHA256'] == digest for c in natives)
        errors = elf_errors(library)
        assert not errors, errors
        result.update(status='passed', tests=expected_tests, extended=args.extended,
            observed_protocols=sorted({c['protocol'] for c in collected if 'protocol' in c}), cases=collected, library_sha256=digest,
            library_md5=asset['arm64-v8a'], library_bytes=len(library), elf_16k_errors=errors,
            zip_alignment_applicable=False, probe_preferences_restored=True, owned_probe_files_removed=True)
        print(f'PASS: {expected_tests} transport cases; native executable mapping and ELF 16KB alignment verified; original Cronet switch restored')
    except BaseException as error:
        result['status'] = 'failed'
        result['error'] = str(error)
        raise
    finally:
        result['cases'] = collected
        (args.output / 'evidence.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
