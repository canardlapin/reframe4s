import unittest
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
from run_isolated_reference import classify_processes, verify_schedule, sha


class IsolationMonitorTest(unittest.TestCase):
    def test_nested_worker_belongs_to_launcher(self):
        result = classify_processes('30 20 100 /jdk/java\n10 1 0 /bin/Python\n20 10 0 /bin/Python',10)
        self.assertEqual(result['external_active_numerical_processes'],[])
        self.assertTrue(all(p['owned'] for p in result['numerical_processes']))

    def test_external_work_and_idle_services_are_distinct(self):
        result = classify_processes('10 1 0 /bin/Python\n50 1 10 /jdk/java\n60 1 0 /bin/Python\n70 1 20 /R.framework/R',10)
        self.assertEqual([p['pid'] for p in result['external_active_numerical_processes']],[50,70])

    def test_bad_sample_cannot_imply_quiet(self):
        with self.assertRaises(ValueError): classify_processes('not a process table',10)

    def test_external_javascript_worker_also_prevents_isolation(self):
        result = classify_processes('10 1 0 /bin/Python\n90 1 80 /bin/node',10)
        self.assertEqual([p['pid'] for p in result['external_active_numerical_processes']],[90])


class SchedulingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.study = self.root/'timing';self.study.mkdir()
        self.diagnostics = self.root/'diagnostics';self.diagnostics.mkdir()
        self.exports = self.diagnostics/'offline-panel-oasis13-mmrr11-v1';self.exports.mkdir()
        self.amendment = self.root/'amendment.json'
        self.identity = self.root/'protocol.json';self.identity.write_text('{}')
        self.amendment.write_text(json.dumps(dict(schema='hfq-offline-before-timing-v1',
            timing_study=str(self.study),diagnostics=str(self.diagnostics),
            identities={str(self.identity):sha(self.identity)})))
        self.completion = self.root/'completion.json'
        self.completion.write_text(json.dumps(dict(amendment_sha256=sha(self.amendment),
            exit_code=0,worker_exit_codes={'exports':0,'analysis':0})))
        self.ledger = dict(ledger_complete=True,expected_event_count=162,expected_export_count=61)

    def test_original_guard_rejects_an_active_first_export_without_a_receipt(self):
        verify_schedule(self.study,self.diagnostics)
        (self.exports/'pair').mkdir()
        with self.assertRaisesRegex(ValueError,'already started'):
            verify_schedule(self.study,self.diagnostics)

    def test_amended_order_requires_exact_complete_accounting(self):
        with patch('run_isolated_reference.inspect',return_value=self.ledger):
            self.assertIn('second_offline_ledger',verify_schedule(self.study,self.diagnostics,self.amendment))
            for field, bad in [('ledger_complete',False),('expected_event_count',161),('expected_export_count',60)]:
                with patch('run_isolated_reference.inspect',return_value={**self.ledger,field:bad}):
                    with self.assertRaisesRegex(ValueError,'not exactly complete'):
                        verify_schedule(self.study,self.diagnostics,self.amendment)

    def test_complete_ledger_cannot_hide_a_failed_or_nonterminal_worker(self):
        with patch('run_isolated_reference.inspect',return_value=self.ledger):
            self.completion.unlink()
            with self.assertRaises(FileNotFoundError):verify_schedule(self.study,self.diagnostics,self.amendment)
            self.completion.write_text(json.dumps(dict(amendment_sha256=sha(self.amendment),
                exit_code=0,worker_exit_codes={'exports':0,'analysis':124})))
            with self.assertRaisesRegex(ValueError,'not verified terminal'):
                verify_schedule(self.study,self.diagnostics,self.amendment)

    def test_changed_protocol_rejected_before_ledger_inspection(self):
        self.identity.write_text('{"changed":true}')
        with patch('run_isolated_reference.inspect') as inspect:
            with self.assertRaisesRegex(ValueError,'identity changed'):
                verify_schedule(self.study,self.diagnostics,self.amendment)
            inspect.assert_not_called()


if __name__=='__main__':unittest.main()
