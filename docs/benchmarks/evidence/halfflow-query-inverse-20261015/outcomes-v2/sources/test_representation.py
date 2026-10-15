import unittest
import numpy as np
from representation import linear_sample, inspect_case


class ExactInverseTests(unittest.TestCase):
    def test_affine_continuation_and_inverse_are_exact_at_exterior_queries(self):
        x=np.arange(7.)
        for a,b in [(1.,3.),(1.05,-2.),(.7,4.)]:
            q=np.array([-13.,-.2,.3,5.1,6.4,19.])
            np.testing.assert_allclose(linear_sample(x,a*x+b,q),a*q+b,atol=1e-13,rtol=0)
            np.testing.assert_allclose(linear_sample(a*x+b,x,a*q+b),q,atol=1e-13,rtol=0)

    def test_inverse_swapped_knots_resolve_interior_and_exterior_cells(self):
        x=np.array([0.,1.,2.,3.]);f=np.array([3.,4.,6.,9.])
        query=np.array([-2.,.4,1.6,2.7,8.])
        np.testing.assert_allclose(linear_sample(f,x,linear_sample(x,f,query)),query,atol=1e-14,rtol=0)

    def test_padding_preserves_both_original_denominators(self):
        x=np.arange(25.);f=x+3.+.04*x*x
        same=inspect_case(x,f,x);padded=inspect_case(x,f,np.arange(-1.,53.))
        self.assertGreater(same['maximum_error_mm'],.2)
        self.assertLess(padded['maximum_error_mm'],.2)
        for row in [same,padded]:
            for key in ['eligible_forward_then_inverse','eligible_inverse_then_forward',
                        'evaluated_forward_then_inverse','evaluated_inverse_then_forward']:
                self.assertEqual(row[key],25)
            self.assertGreater(row['minimum_forward_cell_jacobian'],0)
            self.assertGreater(row['minimum_inverse_cell_jacobian'],0)
            self.assertLess(row['maximum_pointwise_oracle_error_mm'],1e-12)

    def test_invalid_or_nonmonotone_inputs_are_rejected(self):
        for knots,values in [([0.,0.],[0.,1.]),([0.,1.],[0.,float('nan')])]:
            with self.assertRaises(ValueError):linear_sample(knots,values,[.5])
        with self.assertRaises(ValueError):inspect_case(np.arange(3.),[0.,2.,1.],np.arange(3.))


if __name__=='__main__':unittest.main()
