import unittest
from verify_offline_ledger import match_events


class OfflineLedgerTests(unittest.TestCase):
    def fixture(self):
        events = {('pair', 'policy', i): dict(serial=i, directory=f'state-{i}') for i in [1, 2]}
        rows = [dict(pair=key[0], policy=key[1], event=value.copy()) for key, value in events.items()]
        return events, rows

    def test_complete_event_set_and_missing_event_are_distinguished(self):
        events, rows = self.fixture()
        self.assertEqual(match_events(events, rows)[1], [])
        self.assertEqual(match_events(events, rows[:1])[1], [('pair', 'policy', 2)])

    def test_equal_count_with_duplicate_event_is_rejected(self):
        events, rows = self.fixture()
        with self.assertRaisesRegex(ValueError, 'Duplicate'): match_events(events, [rows[0], rows[0]])

    def test_equal_count_with_substituted_event_is_rejected(self):
        events, rows = self.fixture(); rows[1]['event']['serial'] = 3
        with self.assertRaisesRegex(ValueError, 'Unexpected'): match_events(events, rows)

    def test_matching_keys_do_not_hide_mutated_capture_metadata(self):
        events, rows = self.fixture(); rows[1]['event']['directory'] = 'other-state'
        with self.assertRaisesRegex(ValueError, 'committed capture'): match_events(events, rows)


if __name__ == '__main__': unittest.main()
