"""Analytic checks for the independent whole-cell topology measurement."""
import unittest
import numpy as np
from real_mri_cell_topology import cell_diagnostics


class CellTopologyTest(unittest.TestCase):
    def setUp(self):
        self.shape=(5,6,7)
        self.affine=np.array([[1.5,.2,0,-14],[0,2,.3,17],[.1,0,2.5,9],[0,0,0,1.]])
        index=np.moveaxis(np.indices(self.shape),0,-1)
        self.world=index@self.affine[:3,:3].T+self.affine[:3,3]
        self.valid=np.ones(self.shape,dtype=bool)

    def test_physical_determinant_on_an_oblique_grid(self):
        transform=np.array([[1.1,.2,-.1],[.1,.9,.2],[0,.1,1.2]])
        row=cell_diagnostics(self.world@transform.T+[3,-2,8],self.valid,self.affine)
        self.assertTrue(row['pass_criteria'])
        self.assertAlmostEqual(row['minimum_cell_jacobian'],np.linalg.det(transform),places=12)
        self.assertAlmostEqual(row['maximum_cell_jacobian'],np.linalg.det(transform),places=12)
        self.assertEqual(row['total_declared_cells'],120)

    def test_reflection_folds_every_cell(self):
        mapped=self.world.copy();mapped[...,0]*=-1
        row=cell_diagnostics(mapped,self.valid,self.affine)
        self.assertFalse(row['pass_criteria']);self.assertEqual(row['folded_cells'],120)
        self.assertAlmostEqual(row['minimum_cell_jacobian'],-1,places=12)

    def test_cell_corner_fold_is_preserved(self):
        mapped=np.moveaxis(np.indices((2,2,2)),0,-1).astype(float)
        mapped[...,0]*=1-2*mapped[...,1]
        row=cell_diagnostics(mapped,np.ones((2,2,2),bool),np.eye(4))
        self.assertEqual(row['folded_cells'],1)
        self.assertEqual(row['minimum_cell_jacobian'],-1)
        self.assertEqual(row['maximum_cell_jacobian'],1)
        self.assertFalse(row['pass_criteria'])

    def test_invalid_vertex_excludes_all_eight_touching_cells(self):
        self.valid[2,2,2]=False
        row=cell_diagnostics(self.world,self.valid,self.affine)
        self.assertEqual(row['covered_cells'],112);self.assertEqual(row['total_declared_cells'],120)
        self.assertAlmostEqual(row['covered_fraction'],112/120)
        self.assertFalse(row['pass_criteria'])

    def test_slab_partition_does_not_change_measurement(self):
        a=cell_diagnostics(self.world,self.valid,self.affine,slab=1)
        b=cell_diagnostics(self.world,self.valid,self.affine,slab=12)
        self.assertEqual(a,b)


if __name__=='__main__':unittest.main()
