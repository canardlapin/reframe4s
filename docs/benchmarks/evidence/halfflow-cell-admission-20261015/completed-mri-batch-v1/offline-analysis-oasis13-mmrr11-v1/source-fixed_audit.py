#!/usr/bin/env python3
"""Fixed-region, fixed-scale intensity audit for diagnostic checkpoints.

Every target-brain voxel remains in the denominator. A local window touching
invalid warped samples receives maximum loss, rather than disappearing from
the score. This anchored audit is independent of midpoint IRLS reweighting.
It is diagnostic only and is never used for runtime checkpoint selection.
"""
import argparse
import json
from pathlib import Path
import sys

import nibabel as nib
import numpy as np
from scipy.ndimage import minimum_filter, uniform_filter

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'benchmarks/flashalign'))
from real_image_court import read_pull, sample, sha

RADIUS_MM=3.0
VARIANCE_FRACTION=1e-4


def score(fixed,warped,region,valid,spacing):
    fixed=np.asarray(fixed,dtype=np.float64)
    warped=np.asarray(warped,dtype=np.float64)
    region=np.asarray(region,dtype=bool)
    valid=np.asarray(valid,dtype=bool)
    if fixed.ndim!=3 or not (fixed.shape==warped.shape==region.shape==valid.shape):
        raise ValueError('One common 3D lattice required')
    if not np.isfinite(fixed).all() or not region.any():
        raise ValueError('Finite target image and nonempty fixed region required')
    spacing=np.asarray(spacing,dtype=float)
    if spacing.shape!=(3,) or not np.isfinite(spacing).all() or np.any(spacing<=0):
        raise ValueError('Three positive physical spacings required')
    target_variance=float(np.var(fixed[region]))
    if not np.isfinite(target_variance) or target_variance<=0:
        raise ValueError('A finite nonzero target variance is required')
    size=tuple(int(2*np.ceil(RADIUS_MM/s)+1) for s in spacing)
    finite=np.isfinite(warped)
    available=valid & finite
    moving=np.where(available,warped,0.)
    def mean(values):return uniform_filter(values,size=size,mode='constant',cval=0.)
    mean_fixed,mean_moving=mean(fixed),mean(moving)
    aa=np.maximum(0.,mean(fixed*fixed)-mean_fixed*mean_fixed)
    bb=np.maximum(0.,mean(moving*moving)-mean_moving*mean_moving)
    ab=mean(fixed*moving)-mean_fixed*mean_moving
    epsilon=(VARIANCE_FRACTION*target_variance)**2
    if not np.isfinite(epsilon) or epsilon<=0 or not all(np.isfinite(a).all() for a in [aa,bb,ab]):
        raise ValueError('Audit moments or stabilization are outside finite numerical range')
    correlation=np.clip(ab/np.sqrt(aa*bb+epsilon),-1.,1.)
    window_valid=minimum_filter(available.astype(np.uint8),size=size,mode='constant',cval=0)>0
    loss=np.where(window_valid,1.-correlation*correlation,1.)
    signed=np.where(window_valid,correlation,-1.)
    count=int(region.sum())
    return {
        'declared_target_voxels':count,
        'valid_center_voxels':int((region&available).sum()),
        'valid_window_voxels':int((region&window_valid).sum()),
        'nonfinite_warped_target_voxels':int((region&~finite).sum()),
        'center_coverage':float(available[region].mean()),
        'window_coverage':float(window_valid[region].mean()),
        'mean_penalized_squared_cc_loss':float(loss[region].mean()),
        'mean_penalized_signed_correlation':float(signed[region].mean()),
        'target_variance':target_variance,'denominator_epsilon':epsilon,
        'box_window_radius_mm':RADIUS_MM,'window_size_voxels':list(size),
        'variance_fraction':VARIANCE_FRACTION,
        'rule':'All target-mask voxels; invalid windows receive loss 1 and signed correlation -1; no moving-dependent exclusion',
    }


def audit(data,directory,lane):
    result={'schema':'hfq-fixed-intensity-audit-v1','source_sha256':sha(Path(__file__)),
            'directions':{},'diagnostic_only':True,'used_for_runtime_stopping':False}
    for target,source in [('fixed','moving'),('moving','fixed')]:
        direction=target+'-to-'+source
        target_path=data/(target+'.nii.gz');source_path=data/(source+'.nii.gz');mask_path=data/(target+'-mask.nii.gz')
        fixed=nib.load(target_path);moving=nib.load(source_path);mask=nib.load(mask_path)
        coords,valid,_,grid=read_pull(directory,lane+'-'+direction)
        if coords.shape[:3]!=fixed.shape or not np.allclose(grid,fixed.affine,rtol=0,atol=1e-9):
            raise ValueError('Checkpoint must be exported onto its native prepared target grid')
        if mask.shape!=fixed.shape or not np.allclose(mask.affine,fixed.affine,rtol=0,atol=1e-9):
            raise ValueError('Fixed audit mask geometry differs from the target grid')
        warped,inside=sample(moving.get_fdata(),moving.affine,coords)
        row=score(fixed.get_fdata(),warped,mask.get_fdata()>.5,valid&inside,np.linalg.norm(grid[:3,:3],axis=0))
        row['inputs']={str(p):sha(p) for p in [target_path,source_path,mask_path,directory/(lane+'-'+direction+'.f64')]}
        result['directions'][direction]=row
    result['mean_bidirectional_loss']=float(np.mean([r['mean_penalized_squared_cc_loss'] for r in result['directions'].values()]))
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data',type=Path,required=True)
    parser.add_argument('--directory',type=Path,required=True)
    parser.add_argument('--lane',default='halfflow')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError(args.output)
    result=audit(args.data,args.directory,args.lane)
    with args.output.open('x') as stream:
        json.dump(result,stream,indent=2);stream.write('\n')
