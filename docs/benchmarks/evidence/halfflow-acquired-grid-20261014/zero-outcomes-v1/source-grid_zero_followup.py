#!/usr/bin/env python3
"""Freeze and measure exact-zero sensitivity without changing the original grid verdict."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

from objective_probe import ROOT, freeze_engine, verify_engine
from archive_objective_probe import source_delta
from spatial_chain_followup import read, write, sha

HERE=Path(__file__).resolve().parent
ALLOWED={
    'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/HalfFlowCcEngine.scala',
    'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/HalfFlowCcObjectiveProbe.scala',
    'modules/reframe4s-halfflow/shared/src/test/scala/reframe4s/halfflow/HalfFlowCcObjectiveProbeSuite.scala',
    'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageGridAudit.scala'}


def summarize(initial, result, baseline_initial, baseline, protocol):
    if initial!=baseline_initial or result['intensity_only'] is not True or not result['original_current_loss_unchanged']:
        raise ValueError('Original state differs or wrong diagnostic mode')
    if result['passed']!=baseline['passed']:raise ValueError('Original grid verdict changed')
    if [v['variant'] for v in result['variants']]!=protocol['variants']:
        raise ValueError('Incomplete variant ledger')
    measured=[]
    n=math.prod(initial['work_shape'])
    for v,old in zip(result['variants'],baseline['variants'],strict=True):
        if v['rows']!=[]: raise ValueError('Unexpected candidate evaluations')
        for key,value in old.items():
            if key!='rows' and v[key]!=value: raise ValueError('Prior variant measurement differs: '+key)
        for arm in ['fixed','moving']:
            a=v[arm+'_intensity_agreement'];changes=a['zero_status_changes']
            if a['original_nonzero']!=initial[arm+'_support']['nonzeroIntensityDerivatives'] or \
                    a['reindexed_nonzero']!=v[arm+'_support']['nonzeroIntensityDerivatives']:
                raise ValueError('Derivative counts differ from original support reports')
            if len(changes)!=a['zero_status_change_count'] or len({r['index'] for r in changes})!=len(changes):
                raise ValueError('Zero-status ledger differs')
            delta=0
            for row in changes:
                x,y=row['original'],row['reindexed']
                if not isinstance(row['index'],int) or not 0<=row['index']<n or not math.isfinite(x) or not math.isfinite(y):
                    raise ValueError('Invalid zero-status sample')
                if (x==0)==(y==0) or abs(x-y)!=row['absolute_difference']:
                    raise ValueError('Zero-status arithmetic differs')
                delta+=int(y!=0)-int(x!=0)
            if a['original_nonzero']+delta!=a['reindexed_nonzero']: raise ValueError('Count balance differs')
            for key in ['maximum_absolute_difference','relative_l2_difference','original_l2','reindexed_l2']:
                if not math.isfinite(a[key]) or a[key]<0: raise ValueError('Invalid intensity derivative measurement')
            if min(a['original_l2'],a['reindexed_l2'])<=0: raise ValueError('Missing derivative')
            passed=a['maximum_absolute_difference']<=protocol['gradient_absolute_tolerance'] and \
                a['relative_l2_difference']<=protocol['gradient_relative_tolerance']
            if a['passed']!=passed: raise ValueError('Magnitude verdict differs')
            measured.append(dict(variant=v['variant'],arm=arm,zero_status_changes=len(changes),
                original_nonzero=a['original_nonzero'],reindexed_nonzero=a['reindexed_nonzero'],
                maximum_zero_status_difference=max((r['absolute_difference'] for r in changes),default=0),
                maximum_absolute_difference=a['maximum_absolute_difference'],relative_l2_difference=a['relative_l2_difference'],
                magnitude_agreement_pass=passed))
    return dict(measurements=measured,prior_grid_measurements_exact=True,
        zero_status_changes=sum(m['zero_status_changes'] for m in measured),
        magnitude_agreement_pass=all(m['magnitude_agreement_pass'] for m in measured),
        original_grid_verdict_unchanged=baseline['passed']==result['passed'])


def verify(study):
    protocol,source=verify_engine(study/'engine');seal=read(study/'driver-seal.json')
    if (sha(study/'declared-protocol.json')!=seal['protocol_sha256'] or sha(study/'engine/freeze.json')!=seal['engine_freeze_sha256'] or
            source['sha256']!=seal['source_sha256']):
        raise ValueError('Study seal differs')
    for path,digest in (protocol['inputs']|protocol['sources']).items():
        if sha(path)!=digest: raise ValueError('Declared source/input differs: '+path)
    return protocol,source,seal


def freeze(study, prior, classpath, checks):
    if study.exists(): raise FileExistsError(study)
    previous,old=verify_engine(prior/'engine')
    completed=read(prior/'results.json')
    if not completed['all_states_completed']: raise ValueError('Complete original grid study required, including failures')
    log=(checks/'check.log').read_text()
    if log.count('Passed: Total 9, Failed 0, Errors 0, Passed 9')!=2 or \
            'Passed: Total 6, Failed 0, Errors 0, Passed 6' not in log or '[error]' in log:
        raise ValueError('Required JVM/JS/runner checks incomplete')
    python_log=(checks/'python-check-v2.log').read_text()
    if 'Ran 2 tests' not in python_log or '\nOK\n' not in python_log:raise ValueError('Follow-up ledger checks incomplete')
    protocol=dict(previous);protocol.update(schema='hfq-grid-zero-followup-v1',created_at=datetime.now(timezone.utc).isoformat())
    protocol['inputs']=dict(previous['inputs']);protocol['inputs'][str(prior/'results.json')]=sha(prior/'results.json')
    for task in protocol['tasks']:
        task['grid_baseline_initial']=str(prior/task['id']/'initial.json')
        task['grid_baseline_result']=str(prior/task['id']/'result.json')
        for key in ['grid_baseline_initial','grid_baseline_result']:
            protocol['inputs'][task[key]]=sha(task[key])
    sources=[Path(__file__),HERE/'test_grid_zero_followup.py',HERE/'objective_probe.py',HERE/'archive_objective_probe.py',HERE/'spatial_chain_followup.py',
        ROOT/'benchmarks/flashalign/run_real_mri_recovery.py',ROOT/'benchmarks/flashalign/real_image_court.py']
    protocol['sources']={str(p):sha(p) for p in sources}
    protocol.update(selection='All original three states and all three variants; no replacement.',
        scope='Measure detached CC intensity derivatives and retain every exact-zero status change. Reproduce all previous original/variant audits, gradients and support summaries exactly. No candidate perturbations or fits. Original replication failure remains unchanged.',
        analysis_rule='Verify exact previous scalar measurements and all mismatch arithmetic/count balances. Report absolute and relative derivative differences using the existing gradient tolerances as a diagnostic magnitude comparison, not a replacement for the original support-identity rule.',
        full_fits_executed=0,candidate_comparisons=0)
    study.mkdir(parents=True);write(study/'declared-protocol.json',protocol)
    freeze_engine(study/'engine',study/'declared-protocol.json',classpath)
    _,source=verify_engine(study/'engine')
    changed={n for n in set(old['files'])|set(source['files']) if old['files'].get(n)!=source['files'].get(n)}
    if changed!=ALLOWED:raise ValueError('Unexpected source changes: '+str(changed))
    source_delta(prior/'engine',study/'engine',study)
    delta=read(study/'source-delta.json');delta['base_reproduction']='Reconstruct acquired-grid declaration source, then apply this source.patch.'
    (study/'source-delta.json').write_text(json.dumps(delta,indent=2)+'\n')
    for p in sources:shutil.copyfile(p,study/('source-'+p.name))
    shutil.copyfile(checks/'check.log',study/'check.log')
    shutil.copyfile(checks/'python-check.log',study/'python-check.log')
    shutil.copyfile(checks/'python-check-v2.log',study/'python-check-v2.log')
    write(study/'driver-seal.json',dict(protocol_sha256=sha(study/'declared-protocol.json'),
        engine_freeze_sha256=sha(study/'engine/freeze.json'),source_sha256=source['sha256']))
    print('FROZEN zero-status follow-up',source['sha256'],flush=True)


def run(study):
    protocol,source,seal=verify(study);env=os.environ.copy();env.update(protocol['environment']);rows=[]
    for task in protocol['tasks']:
        target=study/task['id'];log=study/(task['id']+'.log');receipt=study/(task['id']+'-receipt.json')
        if target.exists() or log.exists() or receipt.exists():raise FileExistsError('Prior attempt retained')
        command=[shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',(study/'engine/classpath.txt').read_text().strip(),
            'reframe4s.halfflow.RealImageGridAudit',task['data']+'/moving.nii.gz',task['data']+'/fixed.nii.gz',
            task['data']+'/fixed-mask.nii.gz',task['checkpoint'],str(target),str(task['expected_loss']),str(task['level_index']),'intensity-only']
        row=dict(task=task,command=command,protocol_sha256=seal['protocol_sha256'],source_sha256=source['sha256'],analysis_completed=False)
        started=time.monotonic();print('START',task['id'],flush=True)
        with log.open('x') as f:
            process=subprocess.Popen(command,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
            try:row['exit_code']=process.wait(timeout=protocol['timeout_seconds'])
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
                row.update(exit_code=124,failure='Declared diagnostic deadline exceeded')
        row.update(log_sha256=sha(log),diagnostic_wall_seconds=time.monotonic()-started)
        if row['exit_code']==0:
            try:
                row['summary']=summarize(read(target/'initial.json'),read(target/'result.json'),
                    read(Path(task['grid_baseline_initial'])),read(Path(task['grid_baseline_result'])),protocol)
                row['detail_sha256']={p.name:sha(p) for p in target.iterdir() if p.is_file()};row['analysis_completed']=True
            except Exception as error:row['failure']=type(error).__name__+': '+str(error)
        write(receipt,row);rows.append(row);print('DONE',task['id'],row['exit_code'],row['analysis_completed'],flush=True)
    write(study/'results.json',dict(rows=rows,all_states_completed=all(r['analysis_completed'] for r in rows),
        original_grid_verdict_preserved=True,qualification_claim=False,runtime_comparison_allowed=False))


def archive(study, output, declaration):
    if output.exists():raise FileExistsError(output)
    protocol,source,seal=verify(study)
    declaration_digest=None
    if declaration:
        if (study/'results.json').exists() or list(study.glob('*-receipt.json')):raise ValueError('Study already started')
    else:
        declared=output.parent/'zero-declaration-v1/artifact-manifest.json'
        declaration_digest=sha(declared)
        if read(declared)['source_sha256']!=source['sha256'] or \
                sha(declared.parent/'declared-protocol.json')!=seal['protocol_sha256']:
            raise ValueError('Pre-execution declaration differs')
        result=read(study/'results.json')
        if [r['task'] for r in result['rows']]!=protocol['tasks']:raise ValueError('Attempt ledger differs')
        for row in result['rows']:
            task=row['task'];target=study/task['id']
            if read(study/(task['id']+'-receipt.json'))!=row or row['log_sha256']!=sha(study/(task['id']+'.log')):
                raise ValueError('Attempt identity differs')
            if row['source_sha256']!=source['sha256'] or row['protocol_sha256']!=seal['protocol_sha256']:
                raise ValueError('Attempt source/protocol differs')
            if row['analysis_completed']:
                if row['exit_code']!=0 or row['detail_sha256']!={p.name:sha(p) for p in target.iterdir() if p.is_file()}:
                    raise ValueError('Completed details differ')
                if row['summary']!=summarize(read(target/'initial.json'),read(target/'result.json'),
                        read(Path(task['grid_baseline_initial'])),read(Path(task['grid_baseline_result'])),protocol):
                    raise ValueError('Recomputed summary differs')
        if result['all_states_completed']!=all(r['analysis_completed'] for r in result['rows']):raise ValueError('Completion differs')
    files=[p for p in study.iterdir() if p.is_file()]+[p for p in (study/'engine').iterdir() if p.is_file()]
    files += [p for task in protocol['tasks'] for p in (study/task['id']).glob('*') if p.is_file()]
    output.mkdir(parents=True);inventory=[]
    for path in sorted(files):
        if path.is_symlink() or path.suffix not in ['.json','.txt','.py','.patch','.log']:raise ValueError('Unexpected payload')
        relative=path.relative_to(study);target=output/relative
        if path.suffix=='.log':target=target.with_suffix('.log.gz')
        target.parent.mkdir(parents=True,exist_ok=True)
        if path.suffix=='.log':
            with target.open('xb') as f,gzip.GzipFile(fileobj=f,mode='wb',filename='',mtime=0) as compressed:compressed.write(path.read_bytes())
        else:shutil.copyfile(path,target)
        inventory.append(dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path),
            archived_path=str(target.relative_to(output)),archived_sha256=sha(target)))
    for item in inventory:
        target=output/item['archived_path']
        if sha(target)!=item['archived_sha256']:raise ValueError('Archive digest differs')
        if target.suffix=='.gz' and hashlib.sha256(gzip.decompress(target.read_bytes())).hexdigest()!=item['sha256']:
            raise ValueError('Compressed log differs')
    write(output/'artifact-manifest.json',dict(schema='hfq-grid-zero-followup-archive-v1',files=inventory,
        created_at=datetime.now(timezone.utc).isoformat(),source_sha256=source['sha256'],pre_execution_declaration=declaration,
        declaration_manifest_sha256=declaration_digest,checks=dict(JVM=9,JS=9,runner=6,python=2),
        original_grid_verdict_preserved=True,full_fits_executed=0,qualification_claim=False,runtime_comparison_allowed=False))
    print('ARCHIVED zero-status follow-up',len(inventory),'files',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['freeze','run','archive'])
    p.add_argument('--study',type=Path,required=True)
    for name in ['prior','classpath','checks','output']:p.add_argument('--'+name,type=Path)
    p.add_argument('--declaration',action='store_true');a=p.parse_args()
    if a.command=='freeze':freeze(a.study.resolve(),a.prior.resolve(),a.classpath.resolve(),a.checks.resolve())
    elif a.command=='run':run(a.study.resolve())
    else:archive(a.study.resolve(),a.output.resolve(),a.declaration)
