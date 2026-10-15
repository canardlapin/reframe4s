import unittest
import numpy as np
from field_oracle import composition_errors, sample, world_nodes
from inverse_refinement import inverse_cells, query_partition, refined_grid, solve_lattice


class InverseRefinementTests(unittest.TestCase):
    method = dict(tolerance=1e-8, iterations=40, step_cap=4., backtracks=12)

    def test_refinement_preserves_oblique_world_bounds_and_original_nodes(self):
        shape = [5, 6, 7]
        affine = np.array([[1.2, -.4, .1, -3.], [.3, 1.5, .2, 2.], [0., .1, -2., -1.], [0., 0., 0., 1.]])
        nodes = world_nodes(shape, affine)
        for factor in [1, 2, 4]:
            refined, basis = refined_grid(shape, affine, factor)
            values = world_nodes(refined, basis).reshape(*reversed(refined), 3)
            np.testing.assert_allclose(values[::factor, ::factor, ::factor].reshape(-1, 3), nodes, atol=1e-13)
            np.testing.assert_allclose(world_nodes(refined, basis)[-1], nodes[-1], atol=1e-13)
        with self.assertRaises(ValueError):
            refined_grid(shape, affine, 0)

    def test_oblique_affine_inverse_is_exact_with_chunking_and_full_boundary(self):
        shape = [5, 6, 7]
        affine = np.array([[1.2, -.4, .1, -3.], [.3, 1.5, .2, 2.], [0., .1, -2., -1.], [0., 0., 0., 1.]])
        nodes = world_nodes(shape, affine)
        linear = np.array([[1.05, .02, 0.], [.01, .98, .03], [0., -.02, 1.02]])
        shift = np.array([3., -2., 1.])
        forward = nodes @ linear.T + shift
        stored = (nodes - shift) @ np.linalg.inv(linear).T
        valid = np.ones(len(nodes), dtype=bool)
        for factor in [1, 2, 4]:
            refined, basis = refined_grid(shape, affine, factor)
            roots, available, report = solve_lattice(forward, valid, shape, affine, stored, valid,
                                                     refined, basis, self.method, 97)
            expected = (world_nodes(refined, basis) - shift) @ np.linalg.inv(linear).T
            np.testing.assert_allclose(roots, expected, atol=1e-11, rtol=0)
            self.assertTrue(report['all_converged'])
            for row in composition_errors(forward, roots, valid, available, shape, affine, 1, refined, basis).values():
                self.assertEqual(row['all']['eligible'], len(nodes))
                self.assertEqual(row['all']['evaluated'], len(nodes))
                self.assertLess(row['all']['maximum_mm'], 1e-10)
            self.assertTrue(inverse_cells(roots, available, refined, basis)['pass_criteria'])

    def test_nonlinear_inverse_matches_independent_piecewise_linear_knot_exchange(self):
        shape = [7, 3, 3]
        affine = np.eye(4)
        nodes = world_nodes(shape, affine)
        forward = nodes.copy()
        forward[:, 0] += .04 * nodes[:, 0] ** 2
        valid = np.ones(len(nodes), dtype=bool)
        refined, basis = refined_grid(shape, affine, 4)
        roots, available, report = solve_lattice(forward, valid, shape, affine, nodes, valid,
                                                 refined, basis, self.method, 101)
        targets = world_nodes(refined, basis)
        knots = np.arange(7.)
        exact = np.interp(targets[:, 0], knots + .04 * knots**2, knots)
        np.testing.assert_allclose(roots[:, 0], exact, atol=1e-8, rtol=0)
        self.assertTrue(available.all())
        self.assertEqual(report['unresolved'], 0)

    def test_unresolved_roots_and_unsupported_seeds_are_explicit(self):
        shape = [3, 3, 3]
        affine = np.eye(4)
        nodes = world_nodes(shape, affine)
        valid = np.ones(len(nodes), dtype=bool)
        collapsed = np.zeros_like(nodes)
        _, available, report = solve_lattice(collapsed, valid, shape, affine, nodes, valid,
                                              shape, affine, self.method, 7)
        self.assertGreater(report['unresolved'], 0)
        self.assertLess(int(available.sum()), len(nodes))
        self.assertFalse(inverse_cells(nodes, np.zeros_like(valid), shape, affine)['pass_criteria'])
        with self.assertRaisesRegex(ValueError, 'Unsupported inverse seed'):
            solve_lattice(nodes, valid, shape, affine, nodes, np.zeros_like(valid), shape, affine, self.method, 7)

    def test_exterior_partition_never_changes_original_query_denominator(self):
        shape = [4, 5, 6]
        nodes = world_nodes(shape, np.eye(4))
        valid = np.ones(len(nodes), dtype=bool)
        forward = nodes + np.array([2., 0., 0.])
        partition = query_partition(forward, nodes - np.array([2., 0., 0.]), valid,
                                    shape, np.eye(4), shape, np.eye(4))
        self.assertEqual(sum(r['eligible'] for r in partition.values()), len(nodes))
        self.assertGreater(partition['extrapolated_inverse_queries']['eligible'], 0)
        self.assertEqual(sum(r['invalid'] for r in partition.values()), 0)
        self.assertTrue(all(r['maximum_mm'] == 0 for r in partition.values()))


if __name__ == '__main__':
    unittest.main()
