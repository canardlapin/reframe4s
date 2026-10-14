import copy
import unittest
from grid_study import summarize


def fixture():
    support=dict(unavailable=0,evaluated=5,nonzeroIntensityDerivatives=5,interpolationKnotVoxels=0)
    initial=dict(level_index=1,shrink=1,original_audit={'loss':.5},fixed_shape=[3,4,5],moving_shape=[6,7,8],
                 fixed_support=support,moving_support=support)
    task=dict(level_index=1,shrink=1,expected_loss=.5)
    protocol=dict(loss_tolerance=1e-12,gradient_absolute_tolerance=1e-11,gradient_relative_tolerance=1e-10,
                  epsilons_mm=[.01,.0001],directions=['translation','smooth-wave','negative-gradient'],
                  variants=['fixed-reindexed','moving-reindexed','both-reindexed'],
                  reindexing={'fixed':{'axes':[2,0,1]},'moving':{'axes':[1,0,2]}})
    variants=[]
    for name in protocol['variants']:
        v=dict(variant=name,audit={'loss':.5},initial_loss_absolute_difference=0,
               support_exact=True,complete_spatial_support=True,production_validity_exact=True,
               current_loss_unchanged=True,passed=True,fixed_support=support,moving_support=support)
        for arm in ['fixed','moving']:
            axes=protocol['reindexing'][arm]['axes'] if name in [arm+'-reindexed','both-reindexed'] else [0,1,2]
            v[arm+'_shape']=[initial[arm+'_shape'][i] for i in axes]
        for kind in ['production','spatial']:
            v[kind+'_agreement']=dict(maximum_absolute_difference=0,relative_l2_difference=0,
                                     original_l2=1,reindexed_l2=1,passed=True)
        v['rows']=[dict(variant=name,direction=d,epsilon_mm=e,sign=s,original_loss=.5,reindexed_loss=.5,
                        absolute_difference=0,passed=True)
                   for d in protocol['directions'] for e in protocol['epsilons_mm'] for s in [-1.,1.]]
        variants.append(v)
    result={k:protocol[k] for k in ['loss_tolerance','gradient_absolute_tolerance','gradient_relative_tolerance','epsilons_mm']}
    result.update(variants=variants,original_current_loss_unchanged=True,passed=True)
    return initial,result,task,protocol


class GridLedgerTests(unittest.TestCase):
    def test_exact_ledger_and_missing_or_duplicate_candidates(self):
        i,r,t,p=fixture()
        self.assertEqual(summarize(i,r,t,p)['candidate_pairs'],36)
        for rows in [r['variants'][0]['rows'][:-1],r['variants'][0]['rows'][:-1]+[r['variants'][0]['rows'][0]]]:
            bad=copy.deepcopy(r);bad['variants'][0]['rows']=rows
            with self.assertRaisesRegex(ValueError,'candidate ledger'):summarize(i,bad,t,p)

    def test_false_positive_arithmetic_or_support_is_rejected_and_negative_result_retained(self):
        i,r,t,p=fixture();bad=copy.deepcopy(r)
        bad['variants'][0]['rows'][0]['reindexed_loss']=.51
        with self.assertRaisesRegex(ValueError,'arithmetic'):summarize(i,bad,t,p)
        bad=copy.deepcopy(r);bad['variants'][0]['fixed_support']['unavailable']=1
        with self.assertRaisesRegex(ValueError,'Support'):summarize(i,bad,t,p)
        bad=copy.deepcopy(r);v=bad['variants'][0];row=v['rows'][0]
        row['reindexed_loss']=.51;row['absolute_difference']=abs(.51-.5);row['passed']=False
        v['passed']=False;bad['passed']=False
        self.assertFalse(summarize(i,bad,t,p)['passed'])


if __name__=='__main__':unittest.main()
