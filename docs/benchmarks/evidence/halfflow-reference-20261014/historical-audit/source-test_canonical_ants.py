import json
from pathlib import Path
import tempfile
import unittest
import ants
import nibabel as nib
import numpy as np
import pandas as pd
from canonical_ants import affine_ras, compose_points, export_map, FLIP
from real_image_court import read_pull


class CanonicalAntsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        matrix = np.array([[1.125, .0625, .03125], [.03125, .9375, -.0625], [.015625, .0625, 1.03125]])
        center, translation = np.array([12., -7., 4.]), np.array([2., -3., 1.])
        tx = ants.create_ants_transform(transform_type='AffineTransform', precision='double', dimension=3,
                                       matrix=matrix, center=center, translation=translation)
        self.path = self.root / 'affine.mat'; ants.write_transform(tx, str(self.path))
        f = np.diag(FLIP)
        self.matrix = np.eye(4); self.matrix[:3,:3] = f @ matrix @ f
        self.matrix[:3,3] = f @ (translation + center - matrix @ center)
        shape = (9, 10, 11); self.grid = np.diag([1.5, 2., 2.5, 1.]); self.grid[:3,3] = [-6., -8., -10.]
        values = np.zeros(shape + (1, 3)); values[..., 0, :] = [.4, -.3, .2]
        image = nib.Nifti1Image(values, self.grid); image.header.set_intent('vector')
        self.warp = self.root / 'warp.nii.gz'; nib.save(image, self.warp)

    def tearDown(self):
        self.temp.cleanup()

    def test_centered_affine_in_ras_and_inverse(self):
        np.testing.assert_allclose(affine_ras(self.path), self.matrix, atol=1e-12, rtol=0)
        np.testing.assert_allclose(affine_ras(self.path, True) @ affine_ras(self.path), np.eye(4), atol=1e-14)

    def test_noncommuting_warp_and_affine_order_matches_native_points(self):
        pts = np.array([[0.,0.,0.], [1.,2.,3.], [-2.,1.,-1.]])
        actual, _ = compose_points(pts, [self.warp, self.path], [False, False])
        expected = nib.affines.apply_affine(affine_ras(self.path), pts + np.array([.4,-.3,.2])*FLIP)
        np.testing.assert_allclose(actual, expected, atol=1e-13)
        native = ants.apply_transforms_to_points(3, pd.DataFrame(pts*FLIP, columns=['x','y','z']),
                    [str(self.warp), str(self.path)], whichtoinvert=[False,False])[['x','y','z']].to_numpy()*FLIP
        np.testing.assert_allclose(actual, native, atol=3e-6, rtol=0)
        wrong = nib.affines.apply_affine(affine_ras(self.path), pts) + np.array([.4,-.3,.2])*FLIP
        self.assertGreater(float(np.abs(wrong - actual).max()), .01)

    def test_zero_displacement_extension_is_reported_not_masked(self):
        pts = np.array([[200.,200.,200.], [0.,0.,0.]])
        actual, report = compose_points(pts, [self.warp], [False])
        np.testing.assert_array_equal(actual[0], pts[0])
        self.assertEqual(report[0]['outside_field_points'], 1)
        self.assertEqual(report[0]['total_points'], 2)

    def test_field_inversion_requires_an_explicit_inverse(self):
        with self.assertRaises(ValueError):
            compose_points(np.zeros((1,3)), [self.warp], [True])

    def test_persisted_map_roundtrip_retains_axis_order_and_precision(self):
        coords = np.arange(4*5*6*3, dtype=float).reshape(4,5,6,3) * .123456789
        warped = np.arange(4*5*6, dtype=float).reshape(4,5,6) * .314159265
        export_map(self.root, 'ants-fixed-to-moving', coords, warped, self.grid)
        loaded, valid, image, affine = read_pull(self.root, 'ants-fixed-to-moving')
        np.testing.assert_array_equal(loaded, coords)
        np.testing.assert_array_equal(image, warped)
        np.testing.assert_array_equal(affine, self.grid)
        self.assertTrue(valid.all())


if __name__ == '__main__': unittest.main()
