import unittest
from trajectory_study import require_control_replay


class ReplayGateTests(unittest.TestCase):
    def receipt(self):
        return {'exit_code':0,'historical_replay':{
            'halfflow-'+direction+suffix:True for direction in ['fixed-to-moving','moving-to-fixed']
            for suffix in ['.f64','.valid-u8']}}

    def test_complete_bit_identical_replay_permits_expansion(self):
        require_control_replay(self.receipt())

    def test_failed_missing_or_changed_control_cannot_be_skipped_on_resume(self):
        for change in ['exit','missing','changed']:
            row=self.receipt()
            if change=='exit':row['exit_code']=1
            elif change=='missing':row['historical_replay']={}
            else:row['historical_replay']['halfflow-fixed-to-moving.f64']=False
            with self.subTest(change=change),self.assertRaises(RuntimeError):require_control_replay(row)


if __name__=='__main__':unittest.main()
