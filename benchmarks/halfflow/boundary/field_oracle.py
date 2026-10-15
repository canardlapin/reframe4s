#!/usr/bin/env python3
"""Independent boundary-linear trilinear sampler and bounded pointwise root solve."""
import numpy as np


def world_nodes(shape, affine):
    z,y,x=np.indices(tuple(reversed(shape)),dtype=float)
    xyz=np.column_stack([x.ravel(),y.ravel(),z.ravel()])
    return xyz@affine[:3,:3].T+affine[:3,3]


def sample(values, validity, shape, affine, points, derivatives=False):
    values=np.asarray(values,dtype=float);points=np.asarray(points,dtype=float)
    if points.ndim!=2 or points.shape[1]!=3 or not np.isfinite(points).all():
        raise ValueError('Finite world-coordinate triples required')
    shape=np.asarray(shape,dtype=int)
    if shape.shape!=(3,) or np.any(shape<2):raise ValueError('At least two samples on each axis required')
    if values.shape!=(int(np.prod(shape)),3) or np.shape(validity)!=(len(values),):
        raise ValueError('Field/validity shape mismatch')
    inverse=np.linalg.inv(affine[:3,:3]);voxel=(points-affine[:3,3])@inverse.T
    nearest=np.rint(voxel);voxel=np.where(np.abs(voxel-nearest)<=1e-10,nearest,voxel)
    lower=np.clip(np.floor(voxel).astype(int),0,shape-2);fraction=voxel-lower
    out=np.zeros_like(points);jacobian=np.zeros((len(points),3,3)) if derivatives else None
    valid=np.ones(len(points),dtype=bool)
    for z in [0,1]:
        for y in [0,1]:
            for x in [0,1]:
                corner=np.array([x,y,z]);v=lower+corner
                index=v[:,0]+shape[0]*(v[:,1]+shape[1]*v[:,2])
                weights=np.where(corner[None,:]==1,fraction,1-fraction)
                weight=np.prod(weights,axis=1);entries=values[index]
                needed=weight!=0
                dw=[]
                if derivatives:
                    for axis in range(3):
                        w=(1 if corner[axis] else -1)*np.prod(weights[:,[i for i in range(3) if i!=axis]],axis=1)
                        dw.append(w);needed |= w!=0
                valid &= ~needed | (np.asarray(validity)[index] & np.isfinite(entries).all(axis=1))
                out += entries*weight[:,None]
                if derivatives:
                    for axis,w in enumerate(dw):jacobian[:,:,axis] += entries*w[:,None]
    valid &= np.isfinite(out).all(axis=1)
    if derivatives:
        jacobian=jacobian@inverse;valid &= np.isfinite(jacobian).all(axis=(1,2))
    return out,valid,jacobian


def solve(values, validity, shape, affine, targets, seed, tolerance=1e-8, iterations=40, step_cap=4., backtracks=12):
    q=np.array(seed,dtype=float,copy=True);targets=np.asarray(targets,dtype=float)
    if q.shape!=targets.shape:raise ValueError('Seed/target shape mismatch')
    used=0;blocked=np.zeros(len(q),dtype=bool)
    for iteration in range(iterations):
        f,valid,jac=sample(values,validity,shape,affine,q,True)
        residual=f-targets;norm=np.linalg.norm(residual,axis=1)
        determinant=np.linalg.det(jac)
        active=valid & (norm>tolerance) & ~blocked
        if not active.any():break
        good=active & np.isfinite(determinant) & (np.abs(determinant)>1e-12)
        blocked |= active & ~good
        if not good.any():break
        indices=np.flatnonzero(good)
        step=np.linalg.solve(jac[indices],residual[indices,:,None])[:,:,0]
        length=np.linalg.norm(step,axis=1)
        step *= np.minimum(1.,step_cap/np.maximum(length,1e-300))[:,None]
        accepted=np.zeros(len(indices),dtype=bool)
        for halving in range(backtracks):
            remaining=np.flatnonzero(~accepted)
            if not len(remaining):break
            at=indices[remaining];trial=q[at]-step[remaining]*(.5**halving)
            sampled,available,_=sample(values,validity,shape,affine,trial)
            improves=available & (np.linalg.norm(sampled-targets[at],axis=1)<norm[at])
            q[at[improves]]=trial[improves];accepted[remaining[improves]]=True
        blocked[indices[~accepted]]=True;used=iteration+1
    f,valid,jac=sample(values,validity,shape,affine,q,True)
    error=np.linalg.norm(f-targets,axis=1);determinant=np.linalg.det(jac)
    converged=valid & (error<=tolerance)
    return q,dict(targets=len(q),converged=int(converged.sum()),unresolved=int((~converged).sum()),
        invalid=int((~valid).sum()),blocked=int(blocked.sum()),iterations_used=used,
        maximum_equation_residual_mm=float(np.max(error)),minimum_root_jacobian=float(np.min(determinant)),
        nonpositive_root_jacobians=int(np.sum(determinant<=0)),all_converged=bool(converged.all()))


def composition_errors(forward,inverse,valid_forward,valid_inverse,shape,affine,margin=6,
        inverse_shape=None,inverse_affine=None):
    nodes=world_nodes(shape,affine)
    inverse_shape=shape if inverse_shape is None else inverse_shape
    inverse_affine=affine if inverse_affine is None else inverse_affine
    g_f,v1,_=sample(inverse,valid_inverse,inverse_shape,inverse_affine,forward)
    g_nodes,g_valid,_=sample(inverse,valid_inverse,inverse_shape,inverse_affine,nodes)
    f_g,v2,_=sample(forward,valid_forward,shape,affine,g_nodes)
    z,y,x=np.indices(tuple(reversed(shape)))
    interior=((x>=margin)&(x<shape[0]-margin)&(y>=margin)&(y<shape[1]-margin)&
        (z>=margin)&(z<shape[2]-margin)).ravel()
    result={}
    for name,coords,valid in [('forward_then_inverse',g_f,v1 & valid_forward),('inverse_then_forward',f_g,v2 & g_valid)]:
        errors=np.linalg.norm(coords-nodes,axis=1)
        def stats(region):
            usable=valid & region
            maximum=float(np.max(errors[usable])) if usable.any() else None
            return dict(eligible=int(region.sum()),evaluated=int(usable.sum()),invalid=int((region & ~valid).sum()),
                maximum_mm=maximum,passes_02mm=bool(usable.sum()==region.sum() and maximum is not None and maximum<=.2))
        result[name]=dict(all=stats(np.ones(len(nodes),dtype=bool)),interior=stats(interior),boundary=stats(~interior))
    return result
