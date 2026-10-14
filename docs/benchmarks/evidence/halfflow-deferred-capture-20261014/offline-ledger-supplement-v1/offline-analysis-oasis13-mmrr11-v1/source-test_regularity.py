import unittest
import numpy as np
from regularity import displacement_regularity


class RegularityTests(unittest.TestCase):
    def test_linear_displacement_has_exact_physical_derivative_on_oblique_grid(self):
        affine=np.array([[2.,.3,0.,4.],[0.,1.5,.2,-3.],[.1,0.,3.,2.],[0.,0.,0.,1.]])
        world=np.moveaxis(np.indices((7,8,9),dtype=float),0,-1)@affine[:3,:3].T+affine[:3,3]
        linear=np.array([[.1,.2,0.],[-.1,.3,.05],[0.,0.,-.2]])
        coords=world+world@linear.T+np.array([1.,2.,3.])
        row=displacement_regularity(coords,np.ones((7,8,9),dtype=bool),affine)
        self.assertAlmostEqual(row['rms_displacement_gradient_frobenius'],np.linalg.norm(linear),places=13)
        self.assertAlmostEqual(row['maximum_displacement_gradient_frobenius'],np.linalg.norm(linear),places=13)
        self.assertEqual(row['valid_interior_stencil_voxels'],5*6*7)

    def test_translation_has_zero_gradient_and_invalid_stencils_are_counted(self):
        world=np.moveaxis(np.indices((7,7,7),dtype=float),0,-1)
        valid=np.ones((7,7,7),dtype=bool);valid[3,3,3]=False
        row=displacement_regularity(world+np.array([3.,4.,0.]),valid,np.eye(4))
        self.assertEqual(row['rms_displacement_mm'],5.)
        self.assertEqual(row['rms_displacement_gradient_frobenius'],0.)
        self.assertEqual(row['valid_interior_stencil_voxels'],125-7)


if __name__=='__main__':unittest.main()
