#!/usr/bin/env python3
"""Predeclare and run sequential observed-data quality/cost curves.

Includes the complete shared-affine nonlinear stage, export and independent
geometry, excluding label-only evaluation. Shared affine estimation is a fixed
excluded cost for both methods; this is not end-to-end product qualification.
"""
import argparse
import contextlib
from datetime import datetime, timezone
import gc
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from audit_study import audit_maps
from canonical_ants import convert
from real_image_court import sha, write_json

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def freeze(study):
    if study.exists(): raise ValueError('Fresh budget-study directory required')
    study.mkdir(parents=True)
    control = json.loads((ROOT / 'benchmarks/halfflow/qualification/control-seal.json').read_text())
    cases = json.loads((ROOT / 'benchmarks/halfflow/qualification/control-cases.json').read_text())
    chosen = ['oasis-11-to-12','oasis-13-to-14','oasis-15-to-16']
    cases = [c for c in cases if c['id'] in chosen]
    assert len(cases) == 3
    for c in cases:
        for k in ['data','saved_affine','moving_labels','fixed_labels']: c[k] = str(ROOT / c[k])
    paths = list(HERE.glob('*.py')) + [ROOT/'benchmarks/flashalign/real_image_court.py', ROOT/'benchmarks/flashalign/real_mri_cell_topology.py']
    sources = {str(p):sha(p) for p in paths}
    for p in paths: shutil.copyfile(p, study / ('source-' + p.name))
    inputs = {}
    for c in cases:
        for key in ['saved_affine','moving_labels','fixed_labels']: inputs[c[key]] = sha(c[key])
        for p in Path(c['data']).glob('*.nii.gz'): inputs[str(p)] = sha(p)
    protocol = {'schema':'hfq-p02-budget-study-v1','created_at':datetime.now(timezone.utc).isoformat(),
                'cases':cases,'sources':sources,'inputs':inputs,'hardware':control['hardware'],
                'control_seal_sha256':sha(ROOT/'benchmarks/halfflow/qualification/control-seal.json'),
                'control_run':control['local_frozen_run'],'classpath_sha256':sha(Path(control['local_frozen_run'])/'classpath.txt'),
                'lanes':[{'id':'halfflow-selected','method':'halfflow','flags':['--steps-per-level','20','--total-smoothing-mm','1.5','--boundary-width-mm','6']},
                         {'id':'syn-short','method':'ants','iterations':[20,10,0]},
                         {'id':'syn-standard','method':'ants','iterations':[40,20,0]},
                         {'id':'syn-generous','method':'ants','iterations':[100,70,40]}],
                'native_threads':1,'workers':1,'timeout_seconds':3600,'java_heap':'6G','java_active_processors':1,
                'environment':{'OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1','ITK_GLOBAL_DEFAULT_NUMBER_OF_THREADS':'1','MKL_NUM_THREADS':'1','VECLIB_MAXIMUM_THREADS':'1'},
                'timing_scope':'process startup + nonlinear fitting/export + conversion + independent geometry; shared saved affine already supplied; label evaluation excluded',
                'matched_budget_rule':'Per pair select the predeclared ANTs lane with smallest absolute log runtime ratio to HalfFlow, using timing only; call it comparable only if ratio lies [0.8,1.25]. Otherwise report no comparable-budget lane and retain the full curve, with no interpolation or extrapolation.',
                'selection':'No production policy or reference is promoted by this experiment. High-quality geometry failures remain failures; inspect all results, including incomplete fits.',
                'qualification_claim':False}
    write_json(study/'protocol.json',protocol)
    print('FROZEN',len(cases),'pairs',len(protocol['lanes']),'lanes',flush=True)


def run(study):
    p=json.loads((study/'protocol.json').read_text())
    for group in ['sources','inputs']:
        for path,digest in p[group].items():
            if sha(path)!=digest: raise ValueError(group+' identity changed: '+path)
    control=Path(p['control_run']); cp=(control/'classpath.txt').read_text().strip()
    if sha(control/'classpath.txt')!=p['classpath_sha256']: raise ValueError('classpath identity changed')
    # Use P01's hash-verified frozen compiled control, never live build output.
    for path,digest in json.loads((control/'compiled-classpath.json').read_text()).items():
        if sha(path)!=digest: raise ValueError('frozen class changed: '+path)
    env=os.environ.copy();env.update(p['environment']);env['MPLCONFIGDIR']=str(study/'matplotlib-cache')
    # Copy the already-built font cache to avoid accidental timing of discovery.
    cached=Path('/private/tmp/hfq-mpl')
    if cached.exists() and not Path(env['MPLCONFIGDIR']).exists():shutil.copytree(cached,env['MPLCONFIGDIR'])
    rows=[]
    for c in p['cases']:
        for lane in p['lanes']:
            target=study/c['id']/lane['id'];receipt=target.parent/(lane['id']+'-receipt.json')
            if receipt.exists():rows.append(json.loads(receipt.read_text()));continue
            target.parent.mkdir(parents=True,exist_ok=True)
            fit_log=target.parent/(lane['id']+'-fit.log');audit_log=target.parent/(lane['id']+'-audit.log')
            if lane['method']=='halfflow':
                cmd=[shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',cp,'reframe4s.halfflow.RealImageRegistration',
                     c['data']+'/moving.nii.gz',c['data']+'/fixed.nii.gz',c['data']+'/fixed-mask.nii.gz',str(target),
                     'fine-taper-boundary',c['saved_affine']]+lane['flags']
            else:
                cmd=[sys.executable,str(HERE/'fit_reference.py'),'--data',c['data'],'--affine',c['saved_affine'],
                     '--output',str(target),'--iterations',','.join(map(str,lane['iterations']))]
            row={'pair':c['id'],'lane':lane['id'],'method':lane['method'],'command':cmd,'protocol_sha256':sha(study/'protocol.json')}
            print('START',c['id'],lane['id'],flush=True)
            start=time.monotonic()
            with fit_log.open('x') as stream:
                try:
                    proc=subprocess.run(['/usr/bin/time','-l',*cmd],cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=p['timeout_seconds'])
                    row['exit_code']=proc.returncode
                except subprocess.TimeoutExpired:
                    row['exit_code']=124;row['failure']='one-hour fit deadline'
            row['fit_process_seconds']=time.monotonic()-start
            text=fit_log.read_text();rss=re.findall(r'(\d+)\s+maximum resident set size',text)
            row['fit_peak_rss_bytes']=int(rss[-1]) if rss else None
            try:
                if row['exit_code']!=0:raise RuntimeError('Fit failed; diagnostic artifacts retained')
                with audit_log.open('x') as stream,contextlib.redirect_stdout(stream),contextlib.redirect_stderr(stream):
                    conversion_seconds=0.
                    if lane['method']=='ants':
                        start=time.monotonic();output=target/'canonical'
                        conversion=convert(c['data'],target,c['saved_affine'],output)
                        conversion_seconds=time.monotonic()-start
                        row['conversion_pass']=conversion['conversion_pass'];map_lane='ants'
                    else:output=target;map_lane='halfflow'
                    row.update(audit_maps(c,output,map_lane))
                    row['conversion_seconds']=conversion_seconds
                    row['nonlinear_and_geometry_seconds']=row['fit_process_seconds']+conversion_seconds+row['geometry_audit_seconds']
                row['audit_completed']=True
            except Exception as e:row.update(audit_completed=False,error=type(e).__name__+': '+str(e))
            row['fit_log_sha256']=sha(fit_log)
            if audit_log.exists():row['audit_log_sha256']=sha(audit_log)
            write_json(receipt,row);rows.append(row);write_json(study/'results.json',{'rows':rows,'qualification_claim':False})
            print('DONE',json.dumps({k:v for k,v in row.items() if k!='command'}),flush=True);gc.collect()


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['freeze','run']);p.add_argument('--study',type=Path,required=True)
    a=p.parse_args();freeze(a.study.resolve()) if a.command=='freeze' else run(a.study.resolve())
