#!/usr/bin/env python3
"""Require exact acquired-image neutrality before expanding deferred capture."""
import argparse
import json
from pathlib import Path
from trajectory_study import read, write, sha, verify, require_control_replay


def check(original, replay, exports, output):
    verify(original); verify(replay)
    relative=Path('oasis-11-to-12/selected-control')
    old=original/relative;new=replay/relative
    fit=new.parent/'selected-control-fit-receipt.json'
    require_control_replay(read(fit))
    evidence={str(fit):sha(fit)}
    streams=[]
    for base in [old,new]:
        path=base/'checkpoints/events.jsonl'
        receipt=base.parent/'selected-control-fit-receipt.json'
        if sha(path)!=read(receipt)['events_sha256']:
            raise ValueError('Committed events changed')
        evidence[str(path)]=sha(path)
        streams.append([json.loads(line) for line in path.read_text().splitlines()])
    if len(streams[0])!=len(streams[1]):
        raise ValueError('Event count differs')
    captured=0;raw_count=0
    for before,after in zip(*streams):
        for key in ['serial','info','capture_status','directory']:
            if before[key]!=after[key]:
                raise ValueError('Scalar trajectory or checkpoint selection changed: '+key)
        if after['directory'] is None:
            continue
        captured+=1
        if after['export_status']!='deferred: raw midpoint captured; native export pending':
            raise ValueError('Deferred capture misrepresented as native export')
        a=old/'checkpoints'/before['directory'];b=new/'checkpoints'/after['directory']
        if read(b/'checkpoint.json')!=after:
            raise ValueError('Capture differs from event commit')
        if any(b.glob('halfflow*')):
            raise ValueError('Native export unexpectedly ran within capture')
        for arm in ['fixed','moving']:
            names=['midpoint-'+arm+'-residual'+s for s in ['.f64','.json','.valid-u8','-warped.f64']]
            names+=['midpoint-'+arm+'-affine.json']
            for name in names:
                if sha(a/name)!=sha(b/name):
                    raise ValueError('Raw state changed: '+str(b/name))
                evidence[str(b/name)]=sha(b/name);raw_count+=1
    final=['halfflow-diagnostics.txt']
    final += ['halfflow-'+direction+suffix for direction in ['fixed-to-moving','moving-to-fixed']
              for suffix in ['.f64','.json','.valid-u8','-warped.f64']]
    final += ['midpoint-'+arm+'-residual'+s for arm in ['fixed','moving'] for s in ['.f64','.json','.valid-u8']]
    for name in final:
        if sha(old/name)!=sha(new/name):
            raise ValueError('Final control output changed: '+name)
        evidence[str(new/name)]=sha(new/name)
    result=read(exports/'results.json')
    if not result['all_exports_completed'] or not result['synchronous_parity_pass'] or len(result['rows'])!=2:
        raise ValueError('Offline coarse/fine parity did not pass')
    if any(len(row['synchronous_parity'])!=10 or not row['result']['native_export_admitted'] for row in result['rows']):
        raise ValueError('Incomplete synchronous byte comparison')
    evidence[str(exports/'results.json')]=sha(exports/'results.json')
    write(output,dict(schema='hfq-deferred-capture-neutrality-v1',pass_all=True,
        original_protocol_sha256=sha(original/'engine/protocol.json'),
        replay_protocol_sha256=sha(replay/'engine/protocol.json'),
        offline_export_protocol_sha256=sha(exports/'protocol.json'),
        scalar_events_equal=len(streams[0]),raw_states_equal=captured,
        raw_artifacts_equal=raw_count,final_artifacts_equal=len(final),
        offline_export_artifacts_equal=20,evidence=evidence,
        qualification_claim=False,runtime_comparison_allowed=False))
    print('PASS',len(streams[0]),'events,',captured,'raw states,',len(final),'final files; 20 offline export files',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['original','replay','exports','output']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();check(a.original.resolve(),a.replay.resolve(),a.exports.resolve(),a.output.resolve())
