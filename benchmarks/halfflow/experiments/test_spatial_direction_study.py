import copy
import unittest
from spatial_direction_study import GATES, require_closed_gates, summarize


class SpatialScreenTests(unittest.TestCase):
    def setUp(self):
        self.protocol = dict(design=dict(cases=[dict(id=str(i)) for i in range(6)],
            expansion_rule=dict(mean_dice_change_gt=0.,mean_hd95_change_mm_lt=0.,
                maximum_pair_dice_loss=.005,maximum_pair_hd95_increase_mm=.1)),
            attempts=[dict(pair=str(i),lane=lane) for i in range(6)
                for lane in ['step-scaled-baseline','spatial-chain-direction']])
        self.rows = [dict(**r,exit_code=0,native_export_accepted=True,audit_completed=True,
            geometry_pass=True,cells_pass=True,anatomy_complete=True,baseline_replay_pass=True,
            mean_dice=.42 if r['lane']=='step-scaled-baseline' else .43,
            mean_hd95_mm=5. if r['lane']=='step-scaled-baseline' else 4.9)
            for r in self.protocol['attempts']]

    def test_complete_positive_screen_is_development_only(self):
        result = summarize(self.protocol,self.rows)
        self.assertTrue(result['expansion_allowed'])
        self.assertFalse(result['qualification_claim'])
        self.assertFalse(result['qualifying_convergence_established'])
        self.assertEqual(result['terminal_full_fits'],12)

    def test_missing_attempt_cannot_be_hidden_in_complete_case_average(self):
        result = summarize(self.protocol,self.rows[:-1])
        self.assertFalse(result['expansion_allowed'])
        self.assertIsNone(result['mean_dice_change'])
        self.assertEqual(result['missing_attempts'],[['5','spatial-chain-direction']])

    def test_mean_gain_cannot_hide_a_dice_or_distance_tail_failure(self):
        for field,value in [('mean_dice',.414),('mean_hd95_mm',5.11)]:
            rows = copy.deepcopy(self.rows);rows[1][field] = value
            result = summarize(self.protocol,rows)
            self.assertGreater(result['mean_dice_change'],0)
            self.assertLess(result['mean_hd95_change_mm'],0)
            self.assertFalse(result['expansion_allowed'])

    def test_geometry_admission_baseline_parity_and_finite_metrics_all_required(self):
        for index,field,value in [(1,'native_export_accepted',False),(1,'cells_pass',False),
                (1,'geometry_pass',False),(0,'baseline_replay_pass',False),(1,'exit_code',124),
                (1,'anatomy_complete',False),(1,'mean_dice',float('nan')),(1,'mean_hd95_mm',None)]:
            rows = copy.deepcopy(self.rows);rows[index][field] = value
            result = summarize(self.protocol,rows)
            self.assertFalse(result['expansion_allowed'])
            self.assertIsNone(result['mean_dice_change'])

    def test_no_improvement_does_not_pass_strict_mean_rules(self):
        rows = copy.deepcopy(self.rows)
        for r in rows:r['mean_dice'] = .42
        self.assertFalse(summarize(self.protocol,rows)['expansion_allowed'])

    def test_duplicate_or_substituted_receipts_rejected(self):
        with self.assertRaises(ValueError):summarize(self.protocol,self.rows+[self.rows[0]])
        rows = copy.deepcopy(self.rows);rows[0]['pair'] = 'outside-panel'
        with self.assertRaises(ValueError):summarize(self.protocol,rows)

    def test_all_three_evidence_gates_required(self):
        gates = {name:dict(status='closed') for name in GATES}
        require_closed_gates(gates)
        for name in GATES:
            with self.assertRaises(ValueError):require_closed_gates({**gates,name:dict(status='doing')})
        with self.assertRaises(ValueError):require_closed_gates({})


if __name__ == '__main__':unittest.main()
