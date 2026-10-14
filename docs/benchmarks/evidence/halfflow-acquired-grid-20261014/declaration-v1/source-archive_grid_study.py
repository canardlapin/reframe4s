#!/usr/bin/env python3
"""Verify the complete source-grid reindexing ledger and archive compact positive/negative evidence."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil

from grid_study import summarize, verify_measurements
from objective_probe import verify_engine
from spatial_chain_followup import read, write, sha


def declare(study, output, checks):
    if output.exists() or (study/'results.json').exists() or list(study.glob('*-receipt.json')):
        raise FileExistsError('Fresh declaration and unstarted study required')
    protocol, source = verify_engine(study/'engine')
    seal = read(study/'driver-seal.json')
    if (sha(study/'engine/freeze.json')!=seal['engine_freeze_sha256'] or
            sha(study/'declared-protocol.json')!=seal['protocol_sha256'] or
            source['sha256']!=seal['source_sha256']):
        raise ValueError('Declaration seal differs')
    for name,digest in (protocol['inputs'] | protocol['sources']).items():
        if sha(name)!=digest: raise ValueError('Declared identity differs: '+name)
    log=(checks/'check-v2.log').read_text()
    if 'Passed: Total 6, Failed 0, Errors 0, Passed 6' not in log or '[error]' in log:
        raise ValueError('Runner checks incomplete')
    log=(checks/'python-check.log').read_text()
    if 'Ran 2 tests' not in log or '\nOK\n' not in log: raise ValueError('Ledger checks incomplete')
    output.mkdir(parents=True);inventory=[]
    files=[(p,p.relative_to(study)) for p in study.iterdir() if p.is_file()]
    files += [(p,p.relative_to(study)) for p in (study/'engine').iterdir() if p.is_file()]
    files += [(checks/name,Path('checks')/name) for name in ['check.log','check-v2.log','python-check.log']]
    for path,relative in sorted(files):
        if path.is_symlink() or path.suffix not in ['.json','.txt','.py','.patch','.log']:
            raise ValueError('Unexpected declaration payload')
        target=output/relative
        if target.suffix=='.log': target=target.with_suffix('.log.gz')
        target.parent.mkdir(parents=True,exist_ok=True)
        if path.suffix=='.log':
            with target.open('xb') as stream,gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as compressed:
                compressed.write(path.read_bytes())
        else: shutil.copyfile(path,target)
        inventory.append(dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path),
            archived_path=str(target.relative_to(output)),archived_sha256=sha(target)))
    for item in inventory:
        target=output/item['archived_path']
        if sha(target)!=item['archived_sha256']: raise ValueError('Archive digest differs')
        if target.suffix=='.gz' and hashlib.sha256(gzip.decompress(target.read_bytes())).hexdigest()!=item['sha256']:
            raise ValueError('Compressed check log differs')
    write(output/'artifact-manifest.json',dict(schema='hfq-grid-declaration-v1',created_at=datetime.now(timezone.utc).isoformat(),
        files=inventory,source_sha256=source['sha256'],source_reconstruction_verified=True,
        checks=dict(runner=6,python=2),declared_states=3,variants_per_state=3,candidate_comparisons=108,
        execution_started=False,candidate_policies_added=0,full_fits_executed=0,
        qualification_claim=False,runtime_comparison_allowed=False,mri_or_dense_map_payloads=False))
    print('ARCHIVED pre-execution acquired-grid declaration',flush=True)


def archive(study, output):
    if output.exists(): raise FileExistsError(output)
    protocol, source = verify_engine(study/'engine'); seal = read(study/'driver-seal.json')
    if (sha(study/'engine/freeze.json') != seal['engine_freeze_sha256']
            or sha(study/'declared-protocol.json') != seal['protocol_sha256']
            or source['sha256'] != seal['source_sha256']
            or read(study/'declared-protocol.json') != protocol):
        raise ValueError('Frozen study seal changed')
    for path, digest in protocol['inputs'].items():
        if sha(path) != digest: raise ValueError('Frozen input changed')
    for path, digest in protocol['sources'].items():
        if sha(study/('source-'+Path(path).name)) != digest: raise ValueError('Frozen driver source changed')
    validator = Path(__file__).with_name('grid_study.py')
    if sha(validator) != protocol['sources'][str(validator)]: raise ValueError('Verdict validator changed')
    result = read(study/'results.json'); rows = result['rows']
    if len(rows) != len(protocol['tasks']): raise ValueError('Incomplete attempt ledger')
    for task, row in zip(protocol['tasks'], rows, strict=True):
        if row['task'] != task or row != read(study/(task['id']+'-receipt.json')):
            raise ValueError('Attempt identity differs')
        if row['protocol_sha256'] != seal['protocol_sha256'] or row['source_sha256'] != source['sha256']:
            raise ValueError('Attempt source/protocol differs')
        if row['log_sha256'] != sha(study/(task['id']+'.log')): raise ValueError('Attempt log changed')
        if row['analysis_completed']:
            target = study/task['id']; initial, measured = verify_measurements(target,task)
            if row['detail_sha256'] != {p.name:sha(p) for p in target.iterdir() if p.is_file()}:
                raise ValueError('Detailed measurement ledger differs')
            if row['exit_code'] != 0 or row['initial_sha256'] != sha(target/'initial.json') or \
                    row['result_sha256'] != sha(target/'result.json'):
                raise ValueError('Completed measurement changed')
            if initial['original_audit'] != read(Path(task['baseline_initial']))['original_audit'] or not row['original_audit_exact']:
                raise ValueError('Original objective audit changed')
            if summarize(initial,measured,task,protocol) != row['summary']: raise ValueError('Recomputed verdict differs')
    complete = all(r['exit_code']==0 and r['analysis_completed'] for r in rows)
    passed = all(r.get('summary',{}).get('passed',False) for r in rows)
    if result['all_states_completed'] != complete or result['grid_replication_pass'] != passed:
        raise ValueError('Aggregate attempt accounting differs')
    declaration = output.parent/'declaration-v1/artifact-manifest.json'; before = sha(declaration)
    if read(declaration)['source_sha256'] != source['sha256'] or \
            sha(declaration.parent/'declared-protocol.json') != seal['protocol_sha256']:
        raise ValueError('Pre-execution declaration changed')
    files = [p for p in study.iterdir() if p.is_file()]
    files += [p for p in (study/'engine').iterdir() if p.is_file()]
    files += [p for task in protocol['tasks'] for p in (study/task['id']).glob('*') if p.is_file()]
    output.mkdir(parents=True); inventory = []
    for path in sorted(files):
        relative = path.relative_to(study); target = output/relative
        item = dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path))
        if path.is_symlink() or path.suffix not in ['.json','.jsonl','.txt','.py','.patch','.log']:
            item['retained_locally'] = True
        else:
            if path.suffix == '.log': target = target.with_suffix('.log.gz')
            target.parent.mkdir(parents=True,exist_ok=True)
            if path.suffix == '.log':
                with target.open('xb') as stream, gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as compressed:
                    compressed.write(path.read_bytes())
            else: shutil.copyfile(path,target)
            item.update(archived_path=str(target.relative_to(output)),archived_sha256=sha(target))
        inventory.append(item)
    path = Path(__file__); target = output/path.name; shutil.copyfile(path,target)
    inventory.append(dict(path=path.name,bytes=path.stat().st_size,sha256=sha(path),archived_path=path.name))
    for item in inventory:
        if 'archived_path' not in item: continue
        path = output/item['archived_path']
        if sha(path) != item.get('archived_sha256',item['sha256']): raise ValueError('Archived bytes differ')
        if path.name.endswith('.log.gz') and hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest() != item['sha256']:
            raise ValueError('Compressed log differs')
    if sha(declaration) != before: raise ValueError('Declaration archive changed')
    write(output/'artifact-manifest.json',dict(schema='hfq-grid-outcomes-v1',created_at=datetime.now(timezone.utc).isoformat(),
        files=inventory,source_sha256=source['sha256'],declaration_manifest_sha256=before,
        declaration_archive_unchanged=True,declared_states=len(rows),completed_states=sum(r['analysis_completed'] for r in rows),
        grid_replication_pass=passed,qualification_claim=False,runtime_comparison_allowed=False))
    print('ARCHIVED',len(rows),'declared states; source-grid reindexing replication',passed,flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study',type=Path,required=True); p.add_argument('--output',type=Path,required=True)
    p.add_argument('--declaration',action='store_true');p.add_argument('--checks',type=Path)
    args = p.parse_args()
    if args.declaration:
        if args.checks is None: p.error('--declaration requires --checks')
        declare(args.study.resolve(),args.output.resolve(),args.checks.resolve())
    else: archive(args.study.resolve(),args.output.resolve())
