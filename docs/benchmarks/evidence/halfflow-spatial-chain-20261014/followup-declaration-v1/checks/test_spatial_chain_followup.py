import unittest
from spatial_chain_followup import summarize


class SpatialFollowupTests(unittest.TestCase):
    def fixture(self, errors):
        eps=[.1,.05,.02]
        rows=[]
        for epsilon,error in zip(eps,errors):
            derivative=(1-error)/(1+error)
            rows.append(dict(direction='translation',epsilon_mm=epsilon,plus_loss=2*epsilon,minus_loss=0.,
                finite_difference=1.,analytic=derivative,intensity_chain_derivative=derivative,
                spatial_chain_derivative=derivative,relative_error=error,intensity_chain_relative_error=error,
                spatial_chain_relative_error=error))
        result=dict(directions=[dict(direction='translation',rows=rows)],current_loss_unchanged=True)
        support=dict(nonzeroIntensityDerivatives=1,evaluated=1,unavailable=0,interpolationKnotVoxels=0)
        initial=dict(spatial_chain_fixed=support.copy(),spatial_chain_moving=support.copy())
        protocol=dict(directions=['translation'],epsilons_mm=eps,maximum_minimum_relative_derivative_error=.005)
        return result,initial,protocol

    def test_threshold_and_three_point_convergence_are_both_required(self):
        self.assertTrue(summarize(*self.fixture([.007,.006,.005]))['spatial_criterion_pass'])
        self.assertFalse(summarize(*self.fixture([.007,.006,.0051]))['spatial_criterion_pass'])
        self.assertFalse(summarize(*self.fixture([.001,.001,.001]))['spatial_criterion_pass'])

    def test_missing_spatial_support_cannot_pass(self):
        result,initial,protocol=self.fixture([.003,.002,.001])
        initial['spatial_chain_fixed'].update(evaluated=0,unavailable=1)
        row=summarize(result,initial,protocol)
        self.assertTrue(row['directions']['spatial'][0]['passed'])
        self.assertFalse(row['spatial_criterion_pass'])

    def test_missing_row_and_nonfinite_measurement_are_rejected(self):
        result,initial,protocol=self.fixture([.003,.002,.001])
        result['directions'][0]['rows'].pop()
        with self.assertRaisesRegex(ValueError,'epsilon ledger'):summarize(result,initial,protocol)
        result,initial,protocol=self.fixture([.003,.002,.001])
        result['directions'][0]['rows'][0]['analytic']=float('nan')
        with self.assertRaisesRegex(ValueError,'Nonfinite'):summarize(result,initial,protocol)

    def test_claimed_error_cannot_override_measured_derivative(self):
        result,initial,protocol=self.fixture([.003,.002,.001])
        result['directions'][0]['rows'][0]['spatial_chain_relative_error']=0.
        with self.assertRaisesRegex(ValueError,'error arithmetic'):summarize(result,initial,protocol)


if __name__=='__main__':unittest.main()
