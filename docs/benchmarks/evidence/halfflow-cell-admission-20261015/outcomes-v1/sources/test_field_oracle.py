import unittest
import numpy as np
from field_oracle import sample,solve,world_nodes,composition_errors


class TrilinearRootTests(unittest.TestCase):
    def setUp(self):
        self.shape=[7,8,9]
        self.affine=np.array([[1.2,-.4,.1,-3.],[.3,1.5,.2,2.],[0.,.1,2.,-1.],[0.,0.,0.,1.]])
        self.nodes=world_nodes(self.shape,self.affine);self.valid=np.ones(len(self.nodes),dtype=bool)

    def test_world_affine_field_and_jacobian_are_exact_inside_and_outside(self):
        a=np.array([[1.1,.02,-.03],[.01,.95,.04],[.03,.02,1.02]]);b=np.array([3.,-2.,1.])
        values=self.nodes@a.T+b;query=self.nodes[::13]+np.array([-11.,3.,13.])
        out,valid,jac=sample(values,self.valid,self.shape,self.affine,query,True)
        self.assertTrue(valid.all());np.testing.assert_allclose(out,query@a.T+b,rtol=0,atol=2e-12)
        np.testing.assert_allclose(jac,np.broadcast_to(a,jac.shape),rtol=0,atol=2e-12)

    def test_trilinear_derivatives_match_independent_finite_differences(self):
        values=self.nodes.copy();values[:,0]+=.002*self.nodes[:,0]*self.nodes[:,1]
        query=self.nodes[::37]+np.array([.173,.229,.347])
        _,valid,jac=sample(values,self.valid,self.shape,self.affine,query,True)
        self.assertTrue(valid.all())
        for axis in range(3):
            delta=np.eye(3)[axis]*1e-5
            plus,_,_=sample(values,self.valid,self.shape,self.affine,query+delta)
            minus,_,_=sample(values,self.valid,self.shape,self.affine,query-delta)
            np.testing.assert_allclose(jac[:,:,axis],(plus-minus)/2e-5,atol=1e-8,rtol=0)

    def test_root_solve_inverts_affine_map_without_discarding_boundary_targets(self):
        a=np.diag([1.05,.98,1.02]);b=np.array([3.,-2.,1.])
        values=self.nodes@a.T+b
        inverse,receipt=solve(values,self.valid,self.shape,self.affine,self.nodes,self.nodes)
        self.assertTrue(receipt['all_converged']);self.assertEqual(receipt['targets'],len(self.nodes))
        np.testing.assert_allclose(inverse,(self.nodes-b)@np.linalg.inv(a).T,rtol=0,atol=1e-8)
        errors=composition_errors(values,inverse,self.valid,self.valid,self.shape,self.affine,margin=1)
        for row in errors.values():
            self.assertEqual(row['all']['eligible'],len(self.nodes));self.assertTrue(row['all']['passes_02mm'])

    def test_invalid_support_cannot_be_promoted_by_the_root_solver(self):
        valid=self.valid.copy();valid[:]=False
        _,receipt=solve(self.nodes,valid,self.shape,self.affine,self.nodes,self.nodes)
        self.assertFalse(receipt['all_converged']);self.assertEqual(receipt['unresolved'],len(self.nodes))

    def test_larger_inverse_grid_keeps_original_composition_denominator(self):
        shape=[11,12,13];affine=self.affine.copy();affine[:3,3]-=self.affine[:3,:3]@np.array([2.,2.,2.])
        padded=world_nodes(shape,affine)
        errors=composition_errors(self.nodes+np.array([3.,0.,0.]),padded-np.array([3.,0.,0.]),
            self.valid,np.ones(len(padded),dtype=bool),self.shape,self.affine,margin=1,
            inverse_shape=shape,inverse_affine=affine)
        for row in errors.values():
            self.assertEqual(row['all']['eligible'],len(self.nodes));self.assertTrue(row['all']['passes_02mm'])


if __name__=='__main__':unittest.main()
