import unittest
from matched_budget import select_limit, timed_prefixes


class MatchedBudgetTests(unittest.TestCase):
    def test_uses_measured_prefixes_and_overhead(self):
        selected=select_limit([(1,5.),(2,10.),(3,20.)],26.,17.)
        self.assertEqual(selected['iterations'],2)
        self.assertEqual(selected['predicted_seconds'],16.)
        self.assertEqual(selected['measured_noniteration_overhead_seconds'],6.)

    def test_does_not_invent_iterations_outside_observed_prefix(self):
        selected=select_limit([(1,5.),(2,10.),(3,20.)],26.,100.)
        self.assertEqual(selected['iterations'],3)
        self.assertLess(selected['predicted_reference_over_control'],.8)

    def test_extracts_only_iteration_and_clock_fields_from_final_level(self):
        log='\n'.join(['XXDIAGNOSTIC,Iteration',' 1DIAGNOSTIC,1,-.8,inf,2.,1.,',
                       'XXDIAGNOSTIC,Iteration',' 1DIAGNOSTIC,1,-.6,inf,7.,1.,',
                       'XXDIAGNOSTIC,Iteration',' 1DIAGNOSTIC,1,-.4,inf,15.,1.,',
                       ' 1DIAGNOSTIC,2,-.5,inf,24.,1.,'])
        self.assertEqual(timed_prefixes(log),[(1,15.),(2,24.)])


if __name__=='__main__':unittest.main()
