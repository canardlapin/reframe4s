#!/usr/bin/env python3
"""Independent trilinear-cell Jacobians at eight corners and cell center.

This supplements, and does not replace, the existing voxel-difference checks.
All complete cells of the saved map grid are declared; none are cropped away.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

import numpy as np
from real_image_court import sha, write_json


def cell_diagnostics(coords, valid, affine, slab=12):
    assert coords.ndim==4 and coords.shape[-1]==3 and valid.shape==coords.shape[:3]
    assert all(n>=2 for n in valid.shape) and np.isfinite(coords).all()
    basis=float(np.linalg.det(affine[:3,:3]));assert np.isfinite(basis) and basis!=0
    total=int(np.prod(np.asarray(valid.shape)-1));covered=0;folded=0;minimum=float('inf');maximum=-float('inf')
    for z in range(0,coords.shape[2]-1,slab):
        end=min(z+slab,coords.shape[2]-1)
        v={};support=np.ones((coords.shape[0]-1,coords.shape[1]-1,end-z),dtype=bool)
        for x in range(2):
            for y in range(2):
                for k in range(2):
                    index=(slice(x,coords.shape[0]-1+x),slice(y,coords.shape[1]-1+y),slice(z+k,end+k))
                    v[x,y,k]=coords[index];support &= valid[index]
        dx={(y,k):v[1,y,k]-v[0,y,k] for y in range(2) for k in range(2)}
        dy={(x,k):v[x,1,k]-v[x,0,k] for x in range(2) for k in range(2)}
        dz={(x,y):v[x,y,1]-v[x,y,0] for x in range(2) for y in range(2)}
        has_fold=np.zeros(support.shape,dtype=bool)
        def consume(a,b,c):
            nonlocal minimum,maximum
            det=np.einsum('...i,...i->...',a,np.cross(b,c))/basis
            assert np.isfinite(det).all()
            if support.any():
                minimum=min(minimum,float(det[support].min()));maximum=max(maximum,float(det[support].max()))
            has_fold[:] |= det<=0
        for x in range(2):
            for y in range(2):
                for k in range(2):consume(dx[y,k],dy[x,k],dz[x,y])
        consume(sum(dx.values())*.25,sum(dy.values())*.25,sum(dz.values())*.25)
        covered+=int(support.sum());folded+=int((has_fold&support).sum())
    assert covered>0
    return dict(minimum_cell_jacobian=minimum,maximum_cell_jacobian=maximum,folded_cells=folded,
        covered_cells=covered,total_declared_cells=total,covered_fraction=covered/total,samples_per_cell=9,
        pass_criteria=folded==0 and minimum>=.05 and covered/total>=.99)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True)
    p.add_argument('--lane',default='halfflow')
    p.add_argument('--classpath',type=Path)
    p.add_argument('--source-sha256')
    args=p.parse_args();rows={}
    assert (args.classpath is None)==(args.source_sha256 is None)
    for direction in ('fixed-to-moving','moving-to-fixed'):
        name=args.lane+'-'+direction;meta=json.loads((args.directory/(name+'.json')).read_text());shape=tuple(meta['shape'])
        flat=np.memmap(args.directory/(name+'.f64'),dtype='>f8',mode='r',shape=(int(np.prod(shape)),3))
        coords=np.stack([flat[:,c].reshape(shape,order='F') for c in range(3)],axis=-1)
        valid=np.fromfile(args.directory/(name+'.valid-u8'),dtype=np.uint8).reshape(shape,order='F')>0
        affine=np.asarray(meta['index_to_ras_mm']).reshape(4,4)
        rows[direction]=cell_diagnostics(coords,valid,affine)|dict(map_sha256=sha(args.directory/(name+'.f64')),
            validity_sha256=sha(args.directory/(name+'.valid-u8')),shape=list(shape),index_to_ras_mm=affine.ravel().tolist(),
            region_lower_inclusive=[0,0,0],region_upper_exclusive=list(shape),sampling_rule='CellCornersAndCenter')
        if args.classpath:
            r=rows[direction];scope=args.directory/(name+'-topology-scope.txt')
            scope.write_text(' '.join(map(str,shape))+'\n'+' '.join(map(str,affine.ravel()))+'\n'+
                ' '.join(map(str,[r['minimum_cell_jacobian'],r['maximum_cell_jacobian'],r['folded_cells'],r['covered_fraction'],r['covered_cells']]))+'\n'+r['map_sha256']+'\n')
            certificate=args.directory/(name+'-topology-certificate.json')
            result=subprocess.run([shutil.which('java'),'-Xmx512M','-cp',args.classpath.read_text().strip(),
                'reframe4s.halfflow.RealImageTopologyCertificate',str(scope),str(certificate),args.source_sha256],check=False)
            r['native_certificate_process_exit']=result.returncode
            r['native_certificate_accepted']=bool(result.returncode==0 and certificate.exists() and json.loads(certificate.read_text())['accepted'])
            r['native_certificate_sha256']=sha(certificate) if certificate.exists() else None
    write_json(args.directory/(args.lane+'-cell-topology.json'),dict(evaluator_sha256=sha(Path(__file__)),directions=rows,
        pass_criteria=all(r['pass_criteria'] for r in rows.values()),scope='All complete cells of each persisted map, at eight corners and center; sampled evidence, not continuous invertibility.'))
    print(json.dumps({k:{a:b for a,b in v.items() if a not in ('index_to_ras_mm',)} for k,v in rows.items()},indent=2))
    if not all(r['pass_criteria'] and r.get('native_certificate_accepted',True) for r in rows.values()):raise SystemExit(2)


if __name__=='__main__':main()
