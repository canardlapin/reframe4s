import copy
import unittest
from grid_zero_followup import summarize


def fixture():
    initial=dict(work_shape=[2,2,2],original_audit={'loss':.5},fixed_support={'nonzeroIntensityDerivatives':2},moving_support={'nonzeroIntensityDerivatives':2})
    p=dict(variants=['fixed-reindexed','moving-reindexed','both-reindexed'],gradient_absolute_tolerance=1e-11,gradient_relative_tolerance=1e-10)
    old=dict(passed=False,variants=[])
    for name in p['variants']:
        changed=name!='moving-reindexed'
        old['variants'].append(dict(variant=name,rows=[{'old_candidate':True}],passed=not changed,
            fixed_support={'nonzeroIntensityDerivatives':1 if changed else 2},moving_support={'nonzeroIntensityDerivatives':2}))
    new=copy.deepcopy(old);new.update(intensity_only=True,original_current_loss_unchanged=True)
    for v in new['variants']:
        v['rows']=[]
        for arm in ['fixed','moving']:
            changed=arm=='fixed' and v['variant']!='moving-reindexed'
            changes=[dict(index=0,original=1e-23,reindexed=0.,absolute_difference=1e-23)] if changed else []
            v[arm+'_intensity_agreement']=dict(original_nonzero=2,reindexed_nonzero=1 if changed else 2,
                zero_status_changes=changes,zero_status_change_count=len(changes),maximum_absolute_difference=1e-23,
                relative_l2_difference=1e-23,original_l2=1.,reindexed_l2=1.,passed=True)
    return initial,new,copy.deepcopy(initial),old,p


class ZeroStatusTests(unittest.TestCase):
    def test_small_magnitude_does_not_relabel_original_failure(self):
        args=fixture();r=summarize(*args)
        self.assertEqual(r['zero_status_changes'],2)
        self.assertTrue(r['magnitude_agreement_pass'])
        self.assertTrue(r['original_grid_verdict_unchanged'])
        self.assertFalse(args[1]['passed'])

    def test_missing_mismatch_changed_baseline_and_nonfinite_measurements_fail(self):
        args=list(fixture());args[1]['variants'][0]['fixed_intensity_agreement']['zero_status_changes']=[]
        with self.assertRaisesRegex(ValueError,'ledger'):summarize(*args)
        args=list(fixture());args[1]['variants'][0]['fixed_support']['nonzeroIntensityDerivatives']=2
        with self.assertRaisesRegex(ValueError,'Prior variant'):summarize(*args)
        args=list(fixture());args[1]['variants'][0]['fixed_intensity_agreement']['maximum_absolute_difference']=float('nan')
        with self.assertRaisesRegex(ValueError,'Invalid intensity'):summarize(*args)
        args=list(fixture());args[1]['passed']=True
        with self.assertRaisesRegex(ValueError,'Original grid verdict'):summarize(*args)


if __name__=='__main__':unittest.main()
