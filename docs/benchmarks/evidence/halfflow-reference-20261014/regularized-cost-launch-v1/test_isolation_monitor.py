import unittest
from run_isolated_reference import classify_processes


class IsolationMonitorTest(unittest.TestCase):
    def test_nested_worker_belongs_to_launcher(self):
        result = classify_processes('30 20 100 /jdk/java\n10 1 0 /bin/Python\n20 10 0 /bin/Python',10)
        self.assertEqual(result['external_active_numerical_processes'],[])
        self.assertTrue(all(p['owned'] for p in result['numerical_processes']))

    def test_external_work_and_idle_services_are_distinct(self):
        result = classify_processes('10 1 0 /bin/Python\n50 1 10 /jdk/java\n60 1 0 /bin/Python\n70 1 20 /R.framework/R',10)
        self.assertEqual([p['pid'] for p in result['external_active_numerical_processes']],[50,70])

    def test_bad_sample_cannot_imply_quiet(self):
        with self.assertRaises(ValueError): classify_processes('not a process table',10)


if __name__=='__main__':unittest.main()
