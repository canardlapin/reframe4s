#!/usr/bin/env python3
"""Archive a timestamped loss-accounting snapshot without selecting checkpoints."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path

from plot_trajectories import scalar_loss_decomposition
from trajectory_study import verify, read, sha, write


def archive(study, output, check_log):
    if output.exists(): raise FileExistsError(output)
    protocol, source = verify(study)
    protocol_hash, seal_hash = sha(study/'engine/protocol.json'), sha(study/'diagnostic-seal.json')
    files = [study/name for name in ['declared-protocol.json','diagnostic-seal.json','checkpoint-manifest.json',
        'engine/protocol.json','engine/source.json','engine/inputs.json','engine/freeze.json']]
    rows = []
    # Enumerate the declared denominator, including attempts not terminal yet.
    for case in protocol['cases']:
        for lane in protocol['lanes']:
            receipt = study/case['id']/(lane['id']+'-fit-receipt.json')
            row = dict(pair=case['id'],lane=lane['id'],terminal=receipt.exists())
            if not row['terminal']:
                row['status'] = 'pending_at_snapshot'
                rows.append(row)
                continue
            fit = read(receipt); base = receipt.parent/lane['id']
            if (fit['pair']!=case['id'] or fit['lane']!=lane['id'] or
                    fit['protocol_sha256']!=protocol_hash or fit['diagnostic_seal_sha256']!=seal_hash or
                    fit['source_sha256']!=source['sha256']):
                raise ValueError('Fit identity/source differs')
            log = receipt.parent/(lane['id']+'-fit.log')
            if sha(log)!=fit['log_sha256']: raise ValueError('Terminal fit log changed')
            files += [receipt,log]
            row.update(status='completed' if fit['exit_code']==0 else 'failed',exit_code=fit['exit_code'],
                fit_receipt_sha256=sha(receipt),historical_replay={})
            for name, digest in case['expected_outputs'].get(lane['id'],{}).items():
                path = base/name
                actual = sha(path) if path.exists() else None
                row['historical_replay'][name] = dict(expected_sha256=digest,actual_sha256=actual,equal=actual==digest)
            if {n:r['equal'] for n,r in row['historical_replay'].items()}!=fit['historical_replay']:
                raise ValueError('Historical replay bytes differ from receipt')
            row['historical_baseline_available'] = bool(row['historical_replay'])
            events_path = base/'checkpoints/events.jsonl'
            if events_path.exists():
                if sha(events_path)!=fit.get('events_sha256'): raise ValueError('Terminal scalar stream changed')
                events = [json.loads(line) for line in events_path.read_text().splitlines()]
                if len(events)!=fit['events'] or sum(e['directory'] is not None for e in events)!=fit['saved_states']:
                    raise ValueError('Scalar/capture counts differ')
                row.update(events_sha256=sha(events_path),event_count=len(events),saved_states=fit['saved_states'],
                    levels=scalar_loss_decomposition(events),accepted_rebuilt_increases=[])
                for event in events:
                    info = event['info']
                    if info['kind']=='Accepted' and info['after']['loss']>info['before']['loss']:
                        row['accepted_rebuilt_increases'].append(dict(serial=event['serial'],
                            level_index=info['levelIndex'],accepted_steps=info['acceptedSteps'],
                            frozen_current_loss=info['before']['loss'],frozen_candidate_loss=info['trial']['candidateLoss'],
                            rebuilt_loss=info['after']['loss'],capture_status=event['capture_status'],
                            directory=event['directory']))
                files.append(events_path)
            else:
                if fit['exit_code']==0 or 'events_sha256' in fit: raise ValueError('Recorded scalar stream missing')
                row['scalar_status'] = 'unavailable_after_failed_fit'
            rows.append(row)
    result = dict(schema='hfq-scalar-loss-snapshot-v1',created_at=datetime.now(timezone.utc).isoformat(),
        protocol_sha256=protocol_hash,source_sha256=source['sha256'],declared_fits=len(rows),
        terminal_fits=sum(r['terminal'] for r in rows),rows=rows,
        qualification_claim=False,runtime_comparison_allowed=False,candidate_policies_added=0,
        interpretation='Observed changing-objective arithmetic. Rebuilt losses have different weights/support; '
            'their decomposition is neither a common-population objective nor a causal explanation of anatomical changes. '
            'Exit zero and accepted-step budgets do not establish convergence.')
    output.mkdir(parents=True)
    inventory = []
    def copy(path, relative):
        data = path.read_bytes(); digest = hashlib.sha256(data).hexdigest()
        compressed = path.suffix in ['.jsonl','.log']
        target = output/(str(relative)+'.gz' if compressed else str(relative))
        target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream:
            if compressed:
                with gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as zipped: zipped.write(data)
            else: stream.write(data)
        archived = target.read_bytes()
        if (gzip.decompress(archived) if compressed else archived)!=data: raise ValueError('Archive bytes differ')
        inventory.append(dict(path=str(relative),bytes=len(data),sha256=digest,
            archived_path=str(target.relative_to(output)),archived_sha256=sha(target)))
    for path in files: copy(path,path.relative_to(study))
    for name in ['summarize_scalar_losses.py','plot_trajectories.py','test_loss_decomposition.py']:
        copy(Path(__file__).with_name(name),Path('sources')/name)
    copy(check_log,Path('checks')/check_log.name)
    write(output/'summary.json',result)
    inventory.append(dict(path='summary.json',bytes=(output/'summary.json').stat().st_size,
        sha256=sha(output/'summary.json'),archived_path='summary.json'))
    write(output/'artifact-manifest.json',dict(schema='hfq-scalar-loss-archive-v1',files=inventory,
        terminal_fits=result['terminal_fits'],declared_fits=len(rows),source_sha256=source['sha256'],
        qualification_claim=False,runtime_comparison_allowed=False,mri_or_dense_map_payloads=False))
    print('ARCHIVED',result['terminal_fits'],'terminal fits of',len(rows),'declared fits',flush=True)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--check-log',type=Path,required=True)
    args = parser.parse_args(); archive(args.study.resolve(),args.output.resolve(),args.check_log.resolve())
