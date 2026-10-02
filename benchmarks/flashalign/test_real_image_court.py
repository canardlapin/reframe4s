"""Independent checks for the acquired-image evidence boundary."""
import json
import copy
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

import nibabel as nib
import numpy as np

from real_image_court import anatomy, assess_reports, evaluate, read_pull, sample


class RealImageEvidenceTests(unittest.TestCase):
    def test_complete_grid_folds_cannot_hide_behind_invalid_exterior(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            shape = (13, 13, 13)
            points = np.moveaxis(np.indices(shape), 0, -1).astype(float)
            mask = np.zeros(shape, np.uint8)
            mask[7:10, 5:8, 5:8] = 1
            for name in ('fixed', 'moving'):
                nib.save(nib.Nifti1Image(points.sum(-1), np.eye(4)), path / f'{name}.nii.gz')
                nib.save(nib.Nifti1Image(mask, np.eye(4)), path / f'{name}-mask.nii.gz')
            for direction in ('fixed-to-moving', 'moving-to-fixed'):
                field = points.copy()
                valid = np.ones(shape, np.uint8)
                if direction == 'fixed-to-moving':
                    field[2, :, :, 0] += 4
                    valid[:5] = 0
                stem = path / ('affine-' + direction)
                stem.with_suffix('.json').write_text(json.dumps({'shape': shape, 'index_to_ras_mm': np.eye(4).flatten().tolist()}))
                field.transpose(2, 1, 0, 3).astype('>f8').tofile(stem.with_suffix('.f64'))
                valid.transpose(2, 1, 0).tofile(str(stem) + '.valid-u8')
                points.sum(-1).transpose(2, 1, 0).astype('>f8').tofile(str(stem) + '-warped.f64')
            args = SimpleNamespace(data=tmp, output=tmp, lane='affine', fixed_labels=None, moving_labels=None)
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as failed:
                evaluate(args)
            self.assertEqual(failed.exception.code, 2)
            report = json.loads((path / 'affine-evaluation.json').read_text())
            row = report['directions']['fixed-to-moving']
            self.assertEqual(row['grid_jacobian_nonpositive'], 121)
            self.assertGreater(row['grid_jacobian_invalid'], 0)
            self.assertEqual(row['brain_roundtrip_coverage'], 1.)
            self.assertFalse(report['geometry_pass'])
            meta_path = path / 'affine-fixed-to-moving.json'
            meta = json.loads(meta_path.read_text())
            meta['index_to_ras_mm'][3] = 1.
            meta_path.write_text(json.dumps(meta))
            with self.assertRaisesRegex(AssertionError, 'map target grid'):
                evaluate(args)

    def test_acceptance_requires_both_metrics_both_directions_and_matching_evidence(self):
        recipe = {'mode': 'test', 'plan_sha256': 'plan'}
        baseline = {'lane': 'affine', 'geometry_pass': True, 'input_sha256': {'fixed': 'a', 'moving': 'b'},
                    'anatomical_labels': {'fixed': {'sha256': 'c'}, 'moving': {'sha256': 'd'}},
                    'directions': {d: {'anatomy': {'labels': [{'label': 1}], 'label_count': 1,
                        'missing_labels': 0, 'macro_dice': .4, 'median_hd95_mm': 5.}}
                        for d in ('fixed-to-moving', 'moving-to-fixed')}}
        candidate = copy.deepcopy(baseline)
        candidate.update(lane='halfflow', native_export_accepted=True, recipe=recipe)
        self.assertFalse(assess_reports(baseline, candidate, recipe)['pass'])
        for row in candidate['directions'].values():
            row['anatomy'].update(macro_dice=.5, median_hd95_mm=4.)
        self.assertTrue(assess_reports(baseline, candidate, recipe)['pass'])
        for change in ({'macro_dice': .3}, {'median_hd95_mm': 6.}, {'missing_labels': 1, 'median_hd95_mm': None}):
            bad = copy.deepcopy(candidate)
            bad['directions']['moving-to-fixed']['anatomy'].update(change)
            self.assertFalse(assess_reports(baseline, bad, recipe)['pass'])
        bad = copy.deepcopy(candidate)
        bad['native_export_accepted'] = False
        self.assertFalse(assess_reports(baseline, bad, recipe)['pass'])
        for key, value in [('lane', 'halfflow-rejected'), ('recipe', {}), ('input_sha256', {}),
                           ('anatomical_labels', {'fixed': {'sha256': 'wrong'}})]:
            bad = copy.deepcopy(candidate)
            bad[key] = value
            with self.assertRaises(AssertionError):
                assess_reports(baseline, bad, recipe)

    def test_scala_x_fastest_interleaved_big_endian_format(self):
        shape = (3, 4, 5)
        points = np.moveaxis(np.indices(shape), 0, -1)
        field = points * np.array([2., 3., 4.]) + np.array([10., -7., 20.])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            metadata = {"shape": shape, "index_to_ras_mm": np.eye(4).flatten().tolist()}
            (path / "pull.json").write_text(json.dumps(metadata))
            # Construct bytes in Scala's explicit z/y/x/component traversal.
            values = [field[x, y, z, c] for z in range(5) for y in range(4) for x in range(3) for c in range(3)]
            np.array(values, dtype=">f8").tofile(path / "pull.f64")
            np.ones(60, dtype=np.uint8).tofile(path / "pull.valid-u8")
            np.arange(60, dtype=">f8").tofile(path / "pull-warped.f64")
            decoded, valid, warped, _ = read_pull(path, "pull")
            np.testing.assert_array_equal(decoded, field)
            self.assertTrue(valid.all())
            self.assertEqual(warped[2, 3, 4], 59)
            self.assertEqual(warped[1, 0, 0], 1)

    def test_oblique_affine_scalar_interpolation_and_coverage(self):
        affine = np.array([[0., -3., 0., 10.], [2., 0., 0., -4.], [0., 0., 4., 8.], [0., 0., 0., 1.]])
        indices = np.moveaxis(np.indices((5, 6, 7)), 0, -1)
        world = nib.affines.apply_affine(affine, indices)
        values = world @ np.array([2., -1., .5]) + 3.
        query = nib.affines.apply_affine(affine, np.array([[1.2, 2.5, 3.3], [-1., 2., 3.]]))
        actual, valid = sample(values, affine, query)
        self.assertAlmostEqual(actual[0], query[0] @ np.array([2., -1., .5]) + 3., places=12)
        np.testing.assert_array_equal(valid, [True, False])

    def test_anatomical_translation_oracle(self):
        labels = np.zeros((9, 10, 11), dtype=np.int16)
        labels[2:5, 2:5, 2:5] = 10
        labels[5:8, 6:9, 6:9] = 20
        fixed = nib.Nifti1Image(labels, np.eye(4))
        affine = np.eye(4)
        affine[:3, 3] = [3., -2., 1.]
        moving = nib.Nifti1Image(labels, affine)
        pull = np.moveaxis(np.indices(labels.shape), 0, -1).astype(float) + affine[:3, 3]
        result = anatomy(fixed, moving, pull, np.eye(4))
        self.assertEqual(result["label_count"], 2)
        self.assertEqual(result["macro_dice"], 1.)
        self.assertEqual(result["median_hd95_mm"], 0.)
        self.assertEqual(result["missing_labels"], 0)


if __name__ == "__main__":
    unittest.main()
