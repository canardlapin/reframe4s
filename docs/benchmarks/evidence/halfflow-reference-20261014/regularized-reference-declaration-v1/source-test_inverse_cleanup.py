import unittest
import numpy as np
from inverse_cleanup import solve_inverse


class InverseCleanupTests(unittest.TestCase):
    def test_recovers_nonlinear_inverse_without_changing_forward(self):
        world=np.moveaxis(np.indices((9,9,9)),0,-1).astype(float)
        forward=world.copy()
        forward[...,0]+=.2*np.sin(np.pi*world[...,0]/8)*np.sin(np.pi*world[...,1]/8)
        original=forward.copy()
        inverse,report=solve_inverse(forward,np.eye(4),world,world,np.eye(4))
        self.assertTrue(report['all_grid_residual_tolerance_met'])
        self.assertLess(report['maximum_grid_residual_mm'],1e-5)
        np.testing.assert_array_equal(forward,original)
        self.assertGreater(np.max(np.abs(inverse-world)),.1)

    def test_affine_extension_remains_finite_and_is_counted(self):
        world=np.moveaxis(np.indices((3,3,3)),0,-1).astype(float)
        affine=np.eye(4);affine[0,3]=2
        forward=world+np.array([2.,0.,0.])
        targets=np.array([[10.,1.,1.]])
        inverse,report=solve_inverse(forward,np.eye(4),targets,targets,affine)
        np.testing.assert_allclose(inverse,[[8.,1.,1.]],atol=1e-12)
        self.assertEqual(report['outside_forward_grid_points'],1)
        self.assertTrue(report['all_grid_residual_tolerance_met'])


if __name__=='__main__':unittest.main()
