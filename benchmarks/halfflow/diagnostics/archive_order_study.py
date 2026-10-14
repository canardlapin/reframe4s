#!/usr/bin/env python3
"""Verify the complete role-swap ledger and archive compact positive/negative evidence."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil

from order_study import summarize
from objective_probe import verify_engine
from spatial_chain_followup import read, write, sha


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
    validator = Path(__file__).with_name('order_study.py')
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
            target = study/task['id']; initial, measured = read(target/'initial.json'), read(target/'result.json')
            if row['exit_code'] != 0 or row['initial_sha256'] != sha(target/'initial.json') or \
                    row['result_sha256'] != sha(target/'result.json'):
                raise ValueError('Completed measurement changed')
            if initial['original_audit'] != read(Path(task['baseline_initial']))['audit'] or not row['original_audit_exact']:
                raise ValueError('Original objective audit changed')
            if summarize(initial,measured,task,protocol) != row['summary']: raise ValueError('Recomputed verdict differs')
    complete = all(r['exit_code']==0 and r['analysis_completed'] for r in rows)
    passed = all(r.get('summary',{}).get('passed',False) for r in rows)
    if result['all_states_completed'] != complete or result['order_replication_pass'] != passed:
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
    write(output/'artifact-manifest.json',dict(schema='hfq-order-outcomes-v1',created_at=datetime.now(timezone.utc).isoformat(),
        files=inventory,source_sha256=source['sha256'],declaration_manifest_sha256=before,
        declaration_archive_unchanged=True,declared_states=len(rows),completed_states=sum(r['analysis_completed'] for r in rows),
        order_replication_pass=passed,qualification_claim=False,runtime_comparison_allowed=False))
    print('ARCHIVED',len(rows),'declared states; role-swap replication',passed,flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study',type=Path,required=True); p.add_argument('--output',type=Path,required=True)
    args = p.parse_args(); archive(args.study.resolve(),args.output.resolve())
