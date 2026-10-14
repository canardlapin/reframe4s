import copy
import unittest

from order_study import DIRECTIONS, EPSILONS, summarize


class OrderVerdictTest(unittest.TestCase):
    def fixture(self):
        protocol = dict(directions=DIRECTIONS,epsilons_mm=EPSILONS,loss_tolerance=1e-12,
            gradient_absolute_tolerance=1e-11,gradient_relative_tolerance=1e-10)
        task = dict(level_index=0,shrink=2,expected_loss=.4)
        gradient = dict(maximum_absolute_sum=0.0,relative_l2_sum=0.0,original_l2=1.0,swapped_l2=1.0,passed=True)
        support = dict(nonzeroIntensityDerivatives=17,evaluated=17,unavailable=0,interpolationKnotVoxels=0)
        initial = dict(original_audit=dict(loss=.4),swapped_audit=dict(loss=.4),level_index=0,shrink=2,
            initial_loss_absolute_difference=0.0,production_antisymmetry=copy.deepcopy(gradient),
            spatial_antisymmetry=copy.deepcopy(gradient),support_swapped_exact=True,
            complete_spatial_support=True,production_validity_swapped_exact=True)
        for order in ['original','swapped']:
            for arm in ['fixed','moving']: initial[order+'_'+arm+'_support'] = copy.deepcopy(support)
        rows = [dict(direction=d,epsilon_mm=e,sign=s,original_loss=.4+e*s,
                     swapped_opposite_loss=.4+e*s,absolute_difference=0.0,passed=True)
                for d in DIRECTIONS for e in EPSILONS for s in [-1.0,1.0]]
        result = dict(protocol,rows=rows,maximum_candidate_loss_difference=0.0,
            original_current_loss_unchanged=True,swapped_current_loss_unchanged=True,passed=True)
        return initial,result,task,protocol

    def test_complete_accounting_and_legitimate_negative_outcome(self):
        initial,result,task,protocol = self.fixture()
        self.assertTrue(summarize(initial,result,task,protocol)['passed'])
        initial['spatial_antisymmetry'].update(maximum_absolute_sum=1e-6,passed=False)
        result['passed'] = False
        self.assertFalse(summarize(initial,result,task,protocol)['passed'])

    def test_duplicate_candidate_cannot_replace_missing_candidate(self):
        initial,result,task,protocol = self.fixture()
        result['rows'][-1] = copy.deepcopy(result['rows'][0])
        with self.assertRaisesRegex(ValueError,'candidate ledger'): summarize(initial,result,task,protocol)

    def test_recomputed_loss_rejects_false_pass(self):
        initial,result,task,protocol = self.fixture()
        result['rows'][0]['swapped_opposite_loss'] += .01
        with self.assertRaisesRegex(ValueError,'arithmetic'): summarize(initial,result,task,protocol)

    def test_missing_support_and_changed_threshold_cannot_pass(self):
        initial,result,task,protocol = self.fixture()
        initial['original_fixed_support']['unavailable'] = 1
        with self.assertRaisesRegex(ValueError,'Support'): summarize(initial,result,task,protocol)
        initial,result,task,protocol = self.fixture()
        result['loss_tolerance'] = .01
        with self.assertRaisesRegex(ValueError,'tolerances'): summarize(initial,result,task,protocol)


if __name__ == '__main__': unittest.main()
