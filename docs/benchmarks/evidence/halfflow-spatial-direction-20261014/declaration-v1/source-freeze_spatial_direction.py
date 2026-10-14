#!/usr/bin/env python3
"""Freeze tested opt-in direction code and its gated design, without running fits."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'benchmarks/halfflow/diagnostics'))
from objective_probe import freeze_engine, verify_engine
from archive_objective_probe import source_delta, read, write, sha

ALLOWED = {
    'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageRegistration.scala',
    'benchmarks/flashalign/runner/src/test/scala/reframe4s/halfflow/SpatialChainOptionsSuite.scala',
    'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/HalfFlowCcEngine.scala',
    'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/HalfFlowSpatialChainDirection.scala',
    'modules/reframe4s-halfflow/shared/src/test/scala/reframe4s/halfflow/HalfFlowSpatialChainDirectionSuite.scala'}


def freeze(output, protocol, base, classpath, checks, archive):
    if output.exists() or archive.exists(): raise FileExistsError('Fresh build and archive directories required')
    p = read(protocol)
    if p['status']!='execution-gated-design' or p['full_fits_executed']!=0 or len(p['cases'])!=6 or len(p['lanes'])!=2:
        raise ValueError('Expected the unexecuted six-pair/two-lane design')
    log = (checks/'check-v2.log').read_text()
    if log.count('Passed: Total 30, Failed 0, Errors 0, Passed 30')!=2 or \
            'Passed: Total 4, Failed 0, Errors 0, Passed 4' not in log or '[error]' in log:
        raise ValueError('Required JVM/JS/runner checks have not passed')
    monitor = (checks/'monitor-check-v2.log').read_text()
    if 'Ran 4 tests' not in monitor or '\nOK\n' not in monitor: raise ValueError('Monitor checks have not passed')
    _, old = verify_engine(base)
    output.mkdir(parents=True);shutil.copyfile(protocol,output/'declared-design.json')
    freeze_engine(output/'engine',protocol,classpath)
    _, source = verify_engine(output/'engine')
    changed = {n for n in set(old['files']) | set(source['files']) if old['files'].get(n)!=source['files'].get(n)}
    if changed!=ALLOWED: raise ValueError('Unexpected numerical source changes: '+str(changed))
    source_delta(base,output/'engine',output)
    delta = read(output/'source-delta.json')
    delta['base_reproduction'] = 'Reconstruct the coordinate-audit source using its documented patch chain, then apply this source.patch.'
    (output/'source-delta.json').write_text(json.dumps(delta,indent=2)+'\n')
    (output/'checks').mkdir()
    for name in ['check.log','check-v2.log','monitor-check-v2.log','source-preflight.json']:
        shutil.copyfile(checks/name,output/'checks'/name)
    for name in ['freeze_spatial_direction.py','run_isolated_reference.py','test_isolation_monitor.py']:
        shutil.copyfile(Path(__file__).with_name(name),output/('source-'+name))
    write(output/'build-verification.json',dict(source_sha256=source['sha256'],base_source_sha256=old['sha256'],
        changed_files=sorted(changed),source_reconstruction_verified=True,
        checks=dict(JVM=30,JS=30,runner=4,process_monitor=4),new_direction_checks_per_platform=5,
        planned_candidate_policies=1,planned_full_fits=12,full_fits_executed=0,
        execution_gates_still_required=p['execution_prerequisites'],qualification_claim=False,runtime_comparison_allowed=False))
    archive.mkdir(parents=True);inventory=[]
    files=[f for f in output.iterdir() if f.is_file()]
    files += [f for f in (output/'engine').iterdir() if f.is_file()]
    files += list((output/'checks').iterdir())
    for path in sorted(files):
        relative=path.relative_to(output);target=archive/relative
        if path.is_symlink() or path.suffix not in ['.json','.txt','.patch','.py','.log']: raise ValueError('Unexpected archive payload')
        if path.suffix=='.log':target=target.with_suffix('.log.gz')
        target.parent.mkdir(parents=True,exist_ok=True)
        if path.suffix=='.log':
            with target.open('xb') as stream,gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as compressed:
                compressed.write(path.read_bytes())
        else:shutil.copyfile(path,target)
        inventory.append(dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path),
            archived_path=str(target.relative_to(archive)),archived_sha256=sha(target)))
    for item in inventory:
        path=archive/item['archived_path']
        if sha(path)!=item['archived_sha256']:raise ValueError('Archived digest differs')
        if path.suffix=='.gz' and hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest()!=item['sha256']:
            raise ValueError('Archived log differs')
    write(archive/'artifact-manifest.json',dict(schema='hfq-spatial-direction-declaration-v1',created_at=datetime.now(timezone.utc).isoformat(),
        files=inventory,source_sha256=source['sha256'],source_reconstruction_verified=True,
        full_fits_executed=0,qualification_claim=False,runtime_comparison_allowed=False,mri_or_dense_map_payloads=False))
    print('FROZEN',source['sha256'],'five-file source patch; no candidate fits launched',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['output','protocol','base','classpath','checks','archive']:parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();freeze(*[getattr(args,name).resolve() for name in ['output','protocol','base','classpath','checks','archive']])
