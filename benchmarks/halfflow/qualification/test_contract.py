import copy
import itertools
import json
from pathlib import Path
import unittest
from contract import sha, validate_manifest, validate_protocol

HERE = Path(__file__).resolve().parent


class QualificationDesignTests(unittest.TestCase):
    def setUp(self):
        self.protocol = json.loads((HERE / 'protocol-v1.json').read_text())
        self.manifest = {'protocol_sha256': sha(HERE / 'protocol-v1.json'), 'observed_subjects': ['previous-person'],
                         'cohorts': [], 'pairs': []}
        for c in range(3):
            cohort = f'cohort-{c}'
            self.manifest['cohorts'].append({'id': cohort, 'source_identity': f'source-{c}', 'labels': [1, 2, 3]})
            for b in range(12):
                subjects = [f'c{c}-b{b:02d}-s{s}' for s in range(3)]
                for i, (moving, fixed) in enumerate(itertools.combinations(subjects, 2)):
                    self.manifest['pairs'].append(dict(id=f'{c}-{b}-{i}', cohort=cohort, block=str(b),
                                                      moving=moving, fixed=fixed, expected_labels=[1, 2, 3]))

    def test_valid_design_retains_subject_clusters(self):
        self.assertEqual(validate_manifest(self.manifest, self.protocol),
                         dict(pairs=108, subjects=108, blocks=36, cohorts=3, design_valid=True,
                              source_independence_verified=False))

    def test_shared_subject_cannot_inflate_independent_blocks(self):
        self.manifest['pairs'][3]['moving'] = self.manifest['pairs'][0]['moving']
        with self.assertRaisesRegex(ValueError, 'crosses blocks'):
            validate_manifest(self.manifest, self.protocol)

    def test_reversed_pair_is_not_an_independent_observation(self):
        row = copy.deepcopy(self.manifest['pairs'][0])
        row.update(id='reverse', moving=row['fixed'], fixed=row['moving'])
        self.manifest['pairs'].append(row)
        with self.assertRaisesRegex(ValueError, 'lexical'):
            validate_manifest(self.manifest, self.protocol)

    def test_observed_subject_is_rejected(self):
        self.manifest['observed_subjects'].append(self.manifest['pairs'][0]['moving'])
        with self.assertRaisesRegex(ValueError, 'leaked'):
            validate_manifest(self.manifest, self.protocol)

    def test_warped_disappearance_cannot_shrink_label_denominator(self):
        self.manifest['pairs'][0]['expected_labels'] = [1, 2]
        with self.assertRaisesRegex(ValueError, 'annotation coverage'):
            validate_manifest(self.manifest, self.protocol)

    def test_missing_pair_cannot_be_removed_after_outcome(self):
        self.manifest['pairs'].pop()
        with self.assertRaisesRegex(ValueError, 'incomplete within-block'):
            validate_manifest(self.manifest, self.protocol)

    def test_renamed_cohort_does_not_establish_independent_acquisition(self):
        self.manifest['cohorts'][1]['source_identity'] = self.manifest['cohorts'][0]['source_identity']
        with self.assertRaisesRegex(ValueError, 'shared acquisition'):
            validate_manifest(self.manifest, self.protocol)

    def test_geometry_and_epic_floors_cannot_silently_weaken(self):
        for section, key, value in [('geometry', 'maximum_residual_inverse_mm', .3),
                                    ('geometry', 'maximum_folded_cells', 1),
                                    ('acceptance', 'minimum_end_to_end_success_fraction', .9),
                                    ('acceptance', 'dice_noninferiority_margin', .03)]:
            with self.subTest(key=key):
                protocol = copy.deepcopy(self.protocol)
                protocol[section][key] = value
                with self.assertRaises(ValueError):
                    validate_protocol(protocol)

    def test_protocol_tampering_is_detected(self):
        self.manifest['protocol_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'protocol seal'):
            validate_manifest(self.manifest, self.protocol)


if __name__ == '__main__':
    unittest.main()
