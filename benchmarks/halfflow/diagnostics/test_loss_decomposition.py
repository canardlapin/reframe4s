import copy
import unittest

from plot_trajectories import scalar_loss_decomposition


class LossDecompositionTest(unittest.TestCase):
    def audit(self,loss,positive=8):
        return dict(loss=loss,support=dict(count=10,positive=positive,sum=float(positive)),
            neighborhood=dict(activeWindows=7,variance=dict(fixedGlobalVariance=.2,movingGlobalVariance=.3)),
            stabilization=dict(sum=.01))

    def events(self):
        a,b,c = self.audit(.5),self.audit(.48,7),self.audit(.49,6)
        info = [dict(kind='LevelStart',before=a,after=a,trial=None),
            dict(kind='Accepted',before=a,after=b,trial=dict(accepted=True,currentLoss=.5,candidateLoss=.45)),
            dict(kind='Rejected',before=b,after=b,trial=dict(accepted=False,currentLoss=.48,candidateLoss=.51)),
            dict(kind='Accepted',before=b,after=c,trial=dict(accepted=True,currentLoss=.48,candidateLoss=.47)),
            dict(kind='LevelEnd',before=c,after=c,trial=None)]
        return [dict(serial=i+1,info=dict(row,levelIndex=0,shrink=1,
            termination='AcceptedStepBudget' if row['kind']=='LevelEnd' else None)) for i,row in enumerate(info)]

    def test_rebuild_penalty_and_increase_are_not_hidden(self):
        row = scalar_loss_decomposition(self.events())[0]
        self.assertAlmostEqual(row['sum_frozen_trial_drop'],.06)
        self.assertAlmostEqual(row['sum_rebuild_loss_change'],.05)
        self.assertAlmostEqual(row['net_rebuilt_loss_drop'],.01)
        self.assertEqual(row['accepted_steps_with_rebuilt_loss_increase'],1)
        self.assertEqual(row['accepted_steps_with_positive_support_count_decrease'],2)
        self.assertEqual(row['rejected_attempts'],1)
        self.assertEqual(row['termination'],'AcceptedStepBudget')

    def test_partial_level_is_explicit(self):
        row = scalar_loss_decomposition(self.events()[:-1])[0]
        self.assertFalse(row['terminal']); self.assertIsNone(row['termination'])
        self.assertEqual(row['accepted_steps'],2)

    def test_missing_event_and_false_state_continuity_fail(self):
        events = self.events(); del events[2]
        with self.assertRaisesRegex(ValueError,'contiguous'): scalar_loss_decomposition(events)
        events = copy.deepcopy(self.events()); events[3]['info']['before']['loss'] += .1
        with self.assertRaisesRegex(ValueError,'continuity|current loss'): scalar_loss_decomposition(events)


if __name__=='__main__': unittest.main()
