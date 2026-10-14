import unittest
from audit_nki_overlap import compare, normalize, parse_mapping


class NkiOverlapTests(unittest.TestCase):
    def fixture(self):
        rs = {f'NKI-RS-22-{i}': f'{1000+i:07d}' for i in range(1, 23)}
        trt = {f'NKI-TRT-20-{i}': f'{2000+i:07d}' for i in range(1, 21)}
        trt['NKI-TRT-20-5'] = rs['NKI-RS-22-3']
        return rs | trt, set(rs)

    def test_leading_zero_and_project_prefix_are_normalized(self):
        self.assertEqual(normalize('NKI_Rockland_0021002'), normalize('21002'))
        with self.assertRaises(ValueError): normalize('another_project_21002')

    def test_two_table_agreement_detects_overlap_without_claiming_freshness(self):
        data, observed = self.fixture()
        result = compare(data, data.copy(), observed)
        self.assertEqual(result['overlaps'], [dict(candidate='NKI-TRT-20-5', observed='NKI-RS-22-3', source_id='0001003')])
        self.assertEqual(result['remaining_candidate_count'], 19)
        self.assertFalse(result['participant_freshness_established'])

    def test_missing_subject_disagreement_and_incomplete_exposure_fail(self):
        data, observed = self.fixture()
        other = data.copy(); other.pop('NKI-TRT-20-20')
        with self.assertRaisesRegex(ValueError, 'Incomplete'): compare(data, other, observed)
        other = data.copy(); other['NKI-TRT-20-20'] = '9999999'
        with self.assertRaisesRegex(ValueError, 'disagree'): compare(data, other, observed)
        with self.assertRaisesRegex(ValueError, 'Observed'): compare(data, data, observed - {'NKI-RS-22-22'})

    def test_duplicate_mapping_and_duplicate_participant_fail(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            parse_mapping('NKI-TRT-20-1\t21002\nNKI-TRT-20-1\t21002\n', '\t', 1)
        data, observed = self.fixture(); data['NKI-TRT-20-2'] = data['NKI-TRT-20-1']
        with self.assertRaisesRegex(ValueError, 'Within-cohort'): compare(data, data, observed)


if __name__ == '__main__': unittest.main()
