#!/usr/bin/env python3
"""Archive compact boundary declarations/results while excluding captured fields."""
import argparse
from datetime import datetime,timezone
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()


def archive(root,output,stage):
    if output.exists():raise FileExistsError('Fresh archive required')
    if stage=='representation':
        source=root/'representation-v1'
        files=[p for p in source.rglob('*') if p.is_file()]
        files += [root/'checks.log']
        helpers=['representation.py','test_representation.py','plot_representation.py']
        scope='Completed independent exact representation oracle, three maps/two inverse grids, same25-point denominators. No C04 or MRI attribution.'
    elif stage=='declaration':
        files=[]
        for name in ['c04-capture-v1','c04-capture-v2','c04-capture-v3','c04-capture-v4','r24-analysis-v1','r24-analysis-v2']:
            directory=root/name
            for leaf in ['protocol.json','source-capture-replay.py','runner-adapter.patch']:
                if (directory/leaf).exists():files.append(directory/leaf)
            if name in ['c04-capture-v1','c04-capture-v2','c04-capture-v3']:
                for leaf in ['receipt.json','driver.log','replay/execution.log','replay/provenance.json']:
                    if (directory/leaf).exists():files.append(directory/leaf)
        files += [root/'checks-field-v1.log',root/'checks-field-v2.log']
        helpers=['capture_c04.py','analyze_c04.py','field_oracle.py','test_field_oracle.py']
        scope='Frozen current C04 capture and r24 two-arm/two-grid diagnosis plus three failed preparations. Current capture/root outcomes are outside this declaration.'
    else:
        capture=root/'c04-capture-v4';analysis=root/'r24-analysis-v2'
        receipt=json.loads((capture/'receipt.json').read_text())
        if receipt['exit_code']!=0 or not receipt['source_and_inputs_unchanged']:
            raise ValueError('Successful immutable capture required')
        if not (analysis/'results.json').exists():raise ValueError('Terminal root analysis required')
        files=[p for p in capture.rglob('*') if p.is_file() and 'fields' not in p.relative_to(capture).parts]
        files += [p for p in analysis.rglob('*') if p.is_file()]
        for name in ['r24-witness-v1','r24-cell-witness-v1','c04-cells-v1']:
            directory=root/name
            if not (directory/'results.json').exists():raise ValueError('Completed follow-up required: '+name)
            files += [p for p in directory.rglob('*') if p.is_file()]
        files += [root/'historical-field-replay.json']
        helpers=['capture_c04.py','analyze_c04.py','field_oracle.py','test_field_oracle.py',
            'witness_c04.py','witness_cells.py','audit_cells.py']
        files += [Path(__file__).resolve().parents[2]/'flashalign/real_mri_cell_topology.py']
        scope='Terminal current C04 capture and independently diagnosed historical-r24 residuals; captured fields stay local with all hashes. No production fix or qualification claim.'
    files += [Path(__file__).with_name(n) for n in helpers]+[Path(__file__)]
    output.mkdir(parents=True);inventory=[]
    for p in sorted(set(files)):
        if p.is_symlink() or p.suffix not in ['.json','.jsonl','.py','.patch','.log','.png','.svg']:
            raise ValueError('Unexpected payload: '+str(p))
        relative=p.relative_to(root) if p.is_relative_to(root) else Path('sources')/p.name
        target=output/relative
        if p.suffix in ['.log','.jsonl']:target=target.with_name(target.name+'.gz')
        target.parent.mkdir(parents=True,exist_ok=True)
        if p.suffix in ['.log','.jsonl']:
            with target.open('xb') as stream,gzip.GzipFile(fileobj=stream,filename='',mtime=0,mode='wb') as compressed:
                compressed.write(p.read_bytes())
        else:shutil.copyfile(p,target)
        inventory.append(dict(path=str(relative),bytes=p.stat().st_size,sha256=sha(p),
            archived_path=str(target.relative_to(output)),archived_sha256=sha(target)))
    for row in inventory:
        p=output/row['archived_path']
        if sha(p)!=row['archived_sha256']:raise ValueError('Archive bytes differ')
        if p.suffix=='.gz' and hashlib.sha256(gzip.decompress(p.read_bytes())).hexdigest()!=row['sha256']:
            raise ValueError('Compressed payload differs')
    (output/'artifact-manifest.json').write_text(json.dumps(dict(schema='hfq-boundary-evidence-v1',
        created_at=datetime.now(timezone.utc).isoformat(),files=inventory,scope=scope,
        mri_or_dense_map_payloads=False,qualification_claim=False,runtime_comparison_allowed=False),indent=2)+'\n')
    print('ARCHIVED',stage,len(inventory),'files',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--stage',choices=['representation','declaration','outcomes'],required=True)
    args=parser.parse_args();archive(args.root.resolve(),args.output.resolve(),args.stage)
