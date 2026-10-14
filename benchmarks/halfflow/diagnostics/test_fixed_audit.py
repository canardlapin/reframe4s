import unittest
import numpy as np
from fixed_audit import score


class FixedAuditTests(unittest.TestCase):
    def setUp(self):
        xyz=np.indices((11,11,11)).astype(float)
        self.image=np.sin(.7*xyz[0])+.3*np.cos(.5*xyz[1])+.2*np.sin(xyz[2])
        self.region=np.zeros(self.image.shape,bool);self.region[3:-3,3:-3,3:-3]=True
        self.valid=np.ones(self.image.shape,bool)

    def test_identical_textured_images_have_near_zero_loss_on_the_declared_region(self):
        result=score(self.image,self.image,self.region,self.valid,[1.5]*3)
        self.assertEqual(result['declared_target_voxels'],125)
        self.assertEqual(result['valid_window_voxels'],125)
        self.assertLess(result['mean_penalized_squared_cc_loss'],1e-6)

    def test_lost_coverage_cannot_remove_hard_voxels_or_improve_the_score(self):
        warped=np.roll(self.image,2,axis=0)
        before=score(self.image,warped,self.region,self.valid,[1.5]*3)
        self.valid[5,5,5]=False
        after=score(self.image,warped,self.region,self.valid,[1.5]*3)
        self.assertEqual(after['declared_target_voxels'],before['declared_target_voxels'])
        self.assertEqual(after['valid_window_voxels'],0)
        self.assertEqual(after['mean_penalized_squared_cc_loss'],1.)
        self.assertGreaterEqual(after['mean_penalized_squared_cc_loss'],before['mean_penalized_squared_cc_loss'])

    def test_nonfinite_warped_values_are_penalized_and_counted(self):
        warped=self.image.copy();warped[5,5,5]=np.nan
        result=score(self.image,warped,self.region,self.valid,[1.5]*3)
        self.assertEqual(result['nonfinite_warped_target_voxels'],1)
        self.assertEqual(result['declared_target_voxels'],125)
        self.assertEqual(result['mean_penalized_squared_cc_loss'],1.)

    def test_texture_free_reference_is_explicitly_undefined(self):
        with self.assertRaises(ValueError):
            score(np.ones_like(self.image),self.image,self.region,self.valid,[1.5]*3)


if __name__=='__main__':unittest.main()
