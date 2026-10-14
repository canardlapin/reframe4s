import unittest
from summarize_study import syn_stopping


class StoppingEvidenceTests(unittest.TestCase):
    def test_cap_is_not_convergence_and_disabled_level_stays_visible(self):
        log = 'XXDIAGNOSTIC,Iteration\n 1DIAGNOSTIC,20,-.8,-1e-5,3,1\nXXDIAGNOSTIC,Iteration\n 1DIAGNOSTIC,20,-.7,8e-4,27,1\n'
        result = syn_stopping(log, [40,20,0])
        self.assertEqual([r['status'] for r in result['levels']], ['native_convergence_rule_before_cap','iteration_cap','disabled'])
        self.assertFalse(result['all_enabled_levels_stop_by_native_rule'])
        self.assertFalse(result['full_resolution_enabled'])

    def test_missing_log_level_is_unresolved(self):
        result = syn_stopping('XXDIAGNOSTIC,Iteration\n 1DIAGNOSTIC,20,-.8,-1e-5,3,1\n', [100,70,40])
        self.assertFalse(result['interpretable'])


if __name__ == '__main__':
    unittest.main()
