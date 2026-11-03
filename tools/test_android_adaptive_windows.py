import copy
import unittest

from check_android_adaptive_windows import WIDTHS, verify_samples


def valid_samples():
    shelf = [{'requested_width_dp': w, 'configuration_width_dp': w, 'columns': 1,
              'task_id': 1, 'activity_identity': 11} for w in WIDTHS]

    def search(w, h, task, activity, requests):
        return {'requested_width_dp': w, 'requested_height_dp': h,
                'configuration_width_dp': w, 'configuration_height_dp': h,
                'navigation': 'bar' if w < 600 or h < 480 else 'rail',
                'requests': requests, 'route': 'SearchRoute',
                'task_id': task, 'activity_identity': activity, 'anchor': 'owned book'}

    results = [search(900, 600, 2, 22, 0)] + [search(w, 600, 2, 22, 1) for w in WIDTHS]
    results += [search(w, h, 2, 22, 1) for w, h in [(840, 360), (599, 600), (600, 600)]]
    draft = [search(w, h, 3, 33, 0) for w, h in [
        (360, 600), (599, 600), (600, 600), (839, 600), (840, 600), (900, 600),
        (360, 600), (600, 600), (840, 600), (900, 600), (360, 600), (840, 360)]]
    explore = [{'width_dp': w, 'height_dp': h, 'task_id': 4, 'activity_identity': 44,
                'content_left_px': 0, 'content_right_px': 1440,
                'list_left_px': 0, 'list_right_px': 1440,
                'row_left_px': 48, 'row_right_px': 1392, 'row_width_dp': 672}
               for w, h in [(1200, 800), (900, 850), (840, 600), (600, 600),
                            (360, 600), (840, 360), (1200, 800)]]
    return {'bookshelf': shelf, 'results': results, 'draft': draft, 'explore': explore}


class AdaptiveWindowEvidenceTest(unittest.TestCase):
    def test_complete_evidence_is_accepted(self):
        self.assertEqual({'bookshelf': 19, 'results': 23, 'draft': 12, 'explore': 7},
                         verify_samples(valid_samples()))

    def test_incomplete_or_reset_state_is_rejected(self):
        original = valid_samples()
        for mutate in [
            lambda s: s['explore'].pop(),
            lambda s: s['bookshelf'][3].update(task_id=999),
            lambda s: s['results'][3].update(anchor='different book'),
            lambda s: s['draft'][4].update(requests=1),
            lambda s: s['results'][7].update(navigation='bar'),
        ]:
            samples = copy.deepcopy(original)
            mutate(samples)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                verify_samples(samples)

    def test_discovery_overflow_or_off_center_is_rejected(self):
        for change in [{'row_width_dp': 900}, {'list_right_px': 1500}, {'list_left_px': 20}]:
            samples = valid_samples()
            samples['explore'][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                verify_samples(samples)


if __name__ == '__main__':
    unittest.main()
