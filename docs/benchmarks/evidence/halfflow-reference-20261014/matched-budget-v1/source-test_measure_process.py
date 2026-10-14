import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ProcessMeasurementTests(unittest.TestCase):
    def run_child(self, code):
        with tempfile.TemporaryDirectory() as root:
            receipt = Path(root) / 'receipt.json'
            completed = subprocess.run([
                sys.executable, str(Path(__file__).with_name('measure_process.py')),
                '--receipt', str(receipt), '--', sys.executable, '-c', code,
            ], capture_output=True, text=True)
            return completed, json.loads(receipt.read_text())

    def test_success_preserves_output_and_measures_child(self):
        completed, receipt = self.run_child("data=bytearray(16*1024*1024); print('complete')")
        self.assertEqual(completed.returncode, 0)
        self.assertEqual(completed.stdout.strip(), 'complete')
        self.assertEqual(receipt['exit_code'], 0)
        self.assertGreater(receipt['wall_seconds'], 0)
        self.assertGreater(receipt['peak_rss_bytes'], 16*1024*1024)

    def test_child_failure_is_not_hidden(self):
        completed, receipt = self.run_child('raise SystemExit(7)')
        self.assertEqual(completed.returncode, 7)
        self.assertEqual(receipt['exit_code'], 7)

    def test_signal_death_is_retained(self):
        completed, receipt = self.run_child('import os, signal; os.kill(os.getpid(), signal.SIGTERM)')
        self.assertEqual(completed.returncode, 143)
        self.assertEqual(receipt['exit_code'], -15)


if __name__ == '__main__':
    unittest.main()
