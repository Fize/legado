import tempfile
from pathlib import Path
import unittest
from android_performance import (COMPONENT, PACKAGE, fixture_archive, fixture_files,
                                 parse_compilation_state, parse_frames, parse_graphics_summary, parse_memory, parse_startup, summary, validate_books, validate_preparation_resume, valid_initial_reader)


class ReaderFixtureTest(unittest.TestCase):
    def test_initial_title_in_header_or_page(self):
        body = 'Paragraph 000-000: deterministic local reading content for page rendering and progress checks.'
        self.assertTrue(valid_initial_reader(['Chapter 000'], body))
        self.assertTrue(valid_initial_reader([], 'Chapter 000\n\u3000\u3000' + body))
        self.assertFalse(valid_initial_reader([], 'Chapter 001\n' + body))
        self.assertFalse(valid_initial_reader([], 'Chapter 000\nother text'))
        self.assertFalse(valid_initial_reader(['Chapter 001'], 'Chapter 000\n' + body))


class CompilationStateTest(unittest.TestCase):
    def state(self, compiler_filter='verify', reason='install'):
        return f'Dexopt state:\n  [{PACKAGE}]\n    path: /data/app/owned/base.apk\n      arm64: [status={compiler_filter}] [reason={reason}] [primary-abi]\n        [location is /data/app/owned/oat/arm64/base.odex]\nCompiler stats:\n'

    def test_verified_and_uncompiled_runs_stay_distinct(self):
        self.assertEqual({'abi': 'arm64', 'filter': 'verify', 'reason': 'install'}, parse_compilation_state(self.state()))
        self.assertEqual('run-from-apk', parse_compilation_state(self.state('run-from-apk', 'unknown'))['filter'])
        self.assertEqual('cmdline', parse_compilation_state(self.state(reason='cmdline'))['reason'])

    def test_missing_foreign_and_ambiguous_states_are_rejected(self):
        for text in ['', self.state().replace(PACKAGE, 'another.package'),
                     self.state().replace(' [primary-abi]', ''),
                     self.state().replace('Compiler stats:', '      arm64: [status=verify] [reason=install] [primary-abi]\nCompiler stats:')]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_compilation_state(text)


class PerformanceParsingTest(unittest.TestCase):
    def startup(self, state='COLD', component=COMPONENT):
        return f'Status: ok\nLaunchState: {state}\nActivity: {component}\nTotalTime: 410\nWaitTime: 420\nComplete\n'

    def test_real_cold_launch(self):
        self.assertEqual(410, parse_startup(self.startup())['total_time_ms'])

    def test_reused_process_is_not_a_cold_sample(self):
        for state in ['HOT', 'WARM', 'UNKNOWN']:
            with self.subTest(state=state), self.assertRaises(ValueError):
                parse_startup(self.startup(state=state))

    def test_wrong_activity_and_incomplete_launch_fail(self):
        for text in [self.startup(component='other/.Activity'), self.startup().replace('Complete', '')]:
            with self.assertRaises(ValueError): parse_startup(text)

    def test_zero_and_inverted_launch_times_fail(self):
        for text in [self.startup().replace('410', '0'), self.startup().replace('420', '400')]:
            with self.assertRaises(ValueError): parse_startup(text)

    def test_memory_is_bound_to_pid_and_package(self):
        text = f'** MEMINFO in pid 42 [{PACKAGE}] **\nTOTAL PSS: 123456 TOTAL RSS: 200000'
        self.assertEqual(123456, parse_memory(text, 42)['total_pss_kib'])
        with self.assertRaises(ValueError): parse_memory(text, 43)
        with self.assertRaises(ValueError): parse_memory(text.replace(PACKAGE, 'other'), 42)

    def test_missing_and_duplicate_pss_fail(self):
        text = f'** MEMINFO in pid 42 [{PACKAGE}] **\n'
        for sample in [text, text + 'TOTAL PSS: 0', text + 'TOTAL PSS: 1\nTOTAL PSS: 2']:
            with self.assertRaises(ValueError): parse_memory(sample, 42)

    def frames(self, rows, header='Flags,FrameCompleted,Unused,IntendedVsync,'):
        return '---PROFILEDATA---\n' + header + '\n' + '\n'.join(rows) + '\n---PROFILEDATA---'

    def test_dynamic_columns_and_action_timestamp_fence(self):
        text = self.frames(['0,20000000,0,10000000,', '0,45000000,0,30000000,'])
        report = parse_frames(text, 25000000)
        self.assertEqual([15], report['durations_ms'])
        self.assertEqual(1, report['excluded']['before_action'])

    def test_flagged_and_incomplete_frames_are_counted(self):
        report = parse_frames(self.frames(['1,10,0,1,', '0,0,0,3,', '0,9223372036854775807,0,4,', '0,15000000,0,5000000,']), 0)
        self.assertEqual(1, report['frame_count'])
        self.assertEqual({'flagged': 1, 'incomplete': 2, 'before_action': 0}, report['excluded'])

    def test_missing_gpu_cannot_authorize_a_bogus_duration(self):
        raw=self.frames(['0,72057628414509191,0,100,0,'],
                        'Flags,FrameCompleted,Unused,IntendedVsync,GpuCompleted,')
        with self.assertRaises(ValueError): parse_frames(raw,0)

    def test_slow_completed_frames_are_retained(self):
        report=parse_frames(self.frames(['0,200000001,0,1,']),0)
        self.assertEqual([200],report['durations_ms'])

    def test_zero_frames_cannot_look_fast(self):
        for text in ['', self.frames(['1,10,0,1,']), self.frames(['0,0,0,1,'])]:
            with self.assertRaises(ValueError): parse_frames(text, 0)

    def test_reversed_or_overflowed_frame_times_fail(self):
        for row in ['0,10,0,20,', '0,70000000000,0,1,']:
            with self.assertRaises(ValueError): parse_frames(self.frames([row]), 0)

    def test_unknown_frame_schema_fails(self):
        with self.assertRaises(ValueError): parse_frames(self.frames(['0,1'], 'Flags,Other'), 0)

    def test_summary_retains_sample_count(self):
        self.assertEqual({'n': 3, 'min': 1, 'median': 2, 'max': 9}, summary([9, 1, 2]))
        with self.assertRaises(ValueError): summary([])


class NativeRenderingSummaryTest(unittest.TestCase):
    text='Stats since: 1000000000ns\nTotal frames rendered: 30\nJanky frames: 18 (60.00%)\n50th percentile: 53ms\n95th percentile: 69ms\n'
    def test_exact_counter_window_and_native_percentiles(self):
        result=parse_graphics_summary(self.text,1100000000)
        self.assertEqual(30,result['frames_rendered'])
        self.assertEqual(18,result['janky_frames'])
        self.assertEqual(69,result['native_p95_ms'])

    def test_window_details_are_not_counted_as_another_process_summary(self):
        result=parse_graphics_summary(self.text+'\nWindow: package/Activity\n'+self.text,1100000000)
        self.assertEqual(30,result['frames_rendered'])

    def test_zero_frames_and_stale_reset_are_rejected(self):
        for text,stamp in [(self.text.replace('rendered: 30','rendered: 0'),1100000000),(self.text,9000000000)]:
            with self.assertRaises(ValueError):parse_graphics_summary(text,stamp)

    def test_invalid_counters_and_ambiguous_output_are_rejected(self):
        for text in [self.text.replace('Janky frames: 18','Janky frames: 31'),self.text+self.text]:
            with self.assertRaises(ValueError):parse_graphics_summary(text,1100000000)


class PerformanceFixtureTest(unittest.TestCase):
    def test_archive_and_manifests_are_deterministic(self):
        with tempfile.TemporaryDirectory() as folder:
            one, two = Path(folder)/'one.zip', Path(folder)/'two.zip'
            self.assertEqual(fixture_archive(one), fixture_archive(two))
            self.assertEqual(one.read_bytes(), two.read_bytes())
            self.assertEqual(201, len(fixture_files()))

    def books(self):
        return [{'bookUrl': f'/data/user/0/{PACKAGE}/files/books/{name}', 'name': name, 'author': ''}
                for name in fixture_files()]

    def test_only_the_complete_owned_book_set_is_accepted(self):
        self.assertEqual(201, len(validate_books(self.books())))
        with self.assertRaises(ValueError): validate_books(self.books()[:-1])

    def test_foreign_books_are_rejected(self):
        books = self.books(); books[0]['bookUrl'] = 'https://user-book.invalid'
        with self.assertRaises(ValueError): validate_books(books)

    def test_duplicate_names_are_rejected(self):
        books = self.books(); books[0]['name'] = books[1]['name']
        with self.assertRaises(ValueError): validate_books(books)


class PreparationResumeTest(unittest.TestCase):
    def previous(self):
        return dict(status='failed', kind='prepare', serial='emulator-5566', apk_sha256='owned-apk',
                    source={'device': {'api': '35'}})

    def test_matching_failed_preparation_may_resume(self):
        validate_preparation_resume(self.previous(), 'emulator-5566', 'owned-apk')

    def test_other_device_package_or_report_cannot_authorize_resume(self):
        for key, value in [('serial','emulator-5554'), ('apk_sha256','other'), ('kind','baseline'), ('status','prepared')]:
            previous=self.previous(); previous[key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_preparation_resume(previous, 'emulator-5566', 'owned-apk')


if __name__ == '__main__': unittest.main()
