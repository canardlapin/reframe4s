import unittest
import numpy as np
from field_oracle import sample, solve, world_nodes
from query_correction import evaluate
from study_query_correction import independent_forward


class QueryCorrectionTests(unittest.TestCase):
    def setUp(self):
        self.shape = [7, 8, 9]
        self.affine = np.array([[1.2, -.4, .1, -3.], [.3, 1.5, .2, 2.], [0., .1, -2., -1.], [0., 0., 0., 1.]])
        self.nodes = world_nodes(self.shape, self.affine)
        self.valid = np.ones(len(self.nodes), dtype=bool)

    def test_one_correction_inverts_oblique_affine_at_interior_and_exterior_queries(self):
        a = np.array([[1.05, .02, 0.], [.01, .98, .03], [0., -.02, 1.02]])
        b = np.array([.3, -.2, .1])
        forward = self.nodes @ a.T + b
        query = self.nodes[::13] + np.array([-4., 3., 7.])
        corrected, valid, report = evaluate(forward, self.valid, self.nodes, self.valid,
                                           self.shape, self.affine, query, 1)
        np.testing.assert_allclose(corrected, (query - b) @ np.linalg.inv(a).T, atol=1e-10, rtol=0)
        self.assertTrue(valid.all())
        self.assertTrue(report['all_converged'])

    def test_large_translation_cannot_bypass_the_existing_physical_step_cap(self):
        forward = self.nodes + np.array([12., 0., 0.])
        corrected, _, report = evaluate(forward, self.valid, self.nodes, self.valid,
                                        self.shape, self.affine, self.nodes, 1)
        displacement = np.linalg.norm(corrected - self.nodes, axis=1)
        self.assertLessEqual(float(displacement.max()), 4. + 1e-10)
        self.assertGreater(report['maximum_equation_residual_mm'], 7.9)
        self.assertFalse(report['all_converged'])

    def test_zero_budget_preserves_stored_inverse_values_exactly(self):
        inverse = self.nodes + np.array([.2, -.3, .4])
        query = self.nodes[::11] + .123
        expected, _, _ = sample(inverse, self.valid, self.shape, self.affine, query)
        actual, _, report = evaluate(self.nodes, self.valid, inverse, self.valid,
                                     self.shape, self.affine, query, 0)
        np.testing.assert_array_equal(actual, expected)
        self.assertEqual(report['work']['linear_solves']['total'], 0)

    def test_bounded_values_and_failures_match_existing_oracle_without_work_count_mutation(self):
        forward = self.nodes.copy()
        forward[:, 0] += .002 * self.nodes[:, 0] * self.nodes[:, 1]
        query = self.nodes[::19] + .173
        seed, _, _ = sample(self.nodes, self.valid, self.shape, self.affine, query)
        original = forward.copy()
        for budget in [0, 1, 2, 4, 8]:
            expected, oracle = solve(forward, self.valid, self.shape, self.affine, query, seed, iterations=budget)
            actual, _, report = evaluate(forward, self.valid, self.nodes, self.valid,
                                         self.shape, self.affine, query, budget)
            np.testing.assert_array_equal(actual, expected)
            self.assertEqual(report['unresolved'], oracle['unresolved'])
            self.assertLessEqual(report['work']['linear_solves']['maximum_per_query'], budget)
            self.assertLessEqual(report['work']['forward_derivative_samples']['maximum_per_query'], budget + 1)
            self.assertLessEqual(report['work']['forward_point_samples']['maximum_per_query'], budget * 13 + 1)
        np.testing.assert_array_equal(forward, original)

    def test_unsupported_seed_and_singular_forward_never_become_successful_queries(self):
        for forward, inverse_valid in [(self.nodes, ~self.valid), (np.zeros_like(self.nodes), self.valid)]:
            _, available, report = evaluate(forward, self.valid, self.nodes, inverse_valid,
                                            self.shape, self.affine, self.nodes, 8)
            self.assertFalse(report['all_converged'])
            self.assertGreater(report['unresolved'], 0)
            if not inverse_valid.any():
                self.assertFalse(available.any())
                self.assertEqual(report['work']['linear_solves']['total'], 0)

    def test_direct_correction_and_rasterizing_corrected_nodes_are_distinct_representations(self):
        shape = [7, 3, 3]
        affine = np.eye(4)
        nodes = world_nodes(shape, affine)
        forward = nodes.copy()
        forward[:, 0] += .04 * nodes[:, 0] ** 2
        valid = np.ones(len(nodes), dtype=bool)
        direct, _, report = evaluate(forward, valid, nodes, valid, shape, affine, forward, 8)
        raster, _, _ = evaluate(forward, valid, nodes, valid, shape, affine, nodes, 8)
        interpolated, _, _ = sample(raster, valid, shape, affine, forward)
        self.assertTrue(report['all_converged'])
        np.testing.assert_allclose(direct, nodes, atol=1e-8, rtol=0)
        self.assertGreater(float(np.linalg.norm(interpolated - nodes, axis=1).max()), .01)

    def test_invalid_correction_budgets_cannot_expand_the_declared_envelope(self):
        for budget in [-1, 9, .5, True]:
            with self.assertRaises(ValueError):
                evaluate(self.nodes, self.valid, self.nodes, self.valid,
                         self.shape, self.affine, self.nodes, budget)

    def test_independent_interpolator_uses_the_existing_near_knot_coordinate_convention(self):
        shape = [4, 4, 4]
        affine = np.diag([2., 2., 2., 1.])
        nodes = world_nodes(shape, affine)
        values = nodes.copy()
        values[:, 0] += .02 * nodes[:, 0] * nodes[:, 1]
        query = nodes[::7] + np.array([-1.8e-10, -1.7e-10, -1.6e-10])
        authoritative, _, _ = sample(values, np.ones(len(nodes), dtype=bool), shape, affine, query)
        independently_sampled = independent_forward(values, shape, affine, query)
        np.testing.assert_allclose(independently_sampled, authoritative, atol=1e-13, rtol=0)


if __name__ == '__main__':
    unittest.main()
