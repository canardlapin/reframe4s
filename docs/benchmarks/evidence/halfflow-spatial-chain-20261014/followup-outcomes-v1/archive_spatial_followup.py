#!/usr/bin/env python3
"""Verify and archive every declared acquired-image derivative replication."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil

from spatial_chain_followup import read, write, sha, summarize


def plot(protocol, rows, output):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    figure, axes=plt.subplots(len(rows),3,figsize=(12,3.3*len(rows)+1),layout='constrained',sharey=True)
    for index,(task,row) in enumerate(zip(protocol['tasks'],rows,strict=True)):
        caption=task['pair']+' · '+task['policy']+' · '+task['kind']
        for col,direction in enumerate(protocol['directions']):
            ax=axes[index,col]
            ax.set_title(caption+'\n'+direction,fontsize=9)
            if not row['analysis_completed']:
                ax.text(.5,.5,'Attempt incomplete\nexit '+str(row['exit_code']),ha='center',va='center',transform=ax.transAxes)
                continue
            result=read(output/task['id']/'result.json')
            data=next(d['rows'] for d in result['directions'] if d['direction']==direction)
            for field,label,color in [('relative_error','Production gradient','#b91c1c'),
                ('intensity_chain_relative_error','CC intensity chain','#2563eb'),
                ('spatial_chain_relative_error','Source/composition chain','#15803d')]:
                values=[100*d[field] for d in data]
                if any(value<=0 for value in values):raise ValueError('Log plot requires positive measured errors')
                ax.plot([d['epsilon_mm'] for d in data],values,'-o',ms=3,lw=1.2,label=label,color=color)
            ax.axhline(.5,color='#64748b',ls='--',lw=1,label='Declared 0.5% criterion')
            ax.set_xscale('log');ax.set_yscale('log');ax.invert_xaxis();ax.grid(alpha=.15)
            ax.set_xlabel('ε (mm) · decreasing →',fontsize=9)
        axes[index,0].set_ylabel('Symmetric relative error (%)',fontsize=9)
    handles,labels=[],[]
    for row_axes in axes:
        for ax in row_axes:
            handles,labels=ax.get_legend_handles_labels()
            if handles:break
        if handles:break
    if handles:figure.legend(handles,labels,loc='outside lower center',ncol=2,frameon=False,fontsize=9)
    figure.suptitle('Fixed spatial-chain replication · every declared state and perturbation\n'
        'Observed OASIS fine-scale diagnostics; no optimizer or anatomical-improvement claim',fontsize=12)
    folder=output/'figures';folder.mkdir()
    figure.savefig(folder/'derivative-errors.png',dpi=180)
    figure.savefig(folder/'derivative-errors.svg');plt.close(figure)


def archive(study, output):
    if output.exists():raise FileExistsError(output)
    protocol=read(study/'protocol.json');result=read(study/'results.json');rows=result['rows']
    if len(rows)!=len(protocol['tasks']):raise ValueError('Incomplete attempt ledger')
    for source,digest in protocol['sources'].items():
        if sha(study/('source-'+Path(source).name))!=digest:raise ValueError('Frozen driver source changed')
    if sha(Path(__file__).with_name('spatial_chain_followup.py'))!=protocol['sources'][str(Path(__file__).with_name('spatial_chain_followup.py'))]:
        raise ValueError('Summary validator changed')
    for source,digest in protocol['inputs'].items():
        if sha(source)!=digest:raise ValueError('Frozen input changed')
    parent=output.parent/'artifact-manifest.json';parent_sha=sha(parent)
    if read(output.parent/'source-delta.json')['source_sha256']!=protocol['source_sha256']:
        raise ValueError('Follow-up engine differs from archived pilot')
    for task,row in zip(protocol['tasks'],rows,strict=True):
        if row['task']!=task or row!=read(study/(task['id']+'-receipt.json')):
            raise ValueError('Attempt identity changed')
        if row['protocol_sha256']!=sha(study/'protocol.json') or row['log_sha256']!=sha(study/(task['id']+'.log')):
            raise ValueError('Attempt provenance changed')
        if row['analysis_completed']:
            target=study/task['id']
            if sha(target/'result.json')!=row['result_sha256'] or sha(target/'initial.json')!=row['initial_sha256']:
                raise ValueError('Derivative result changed')
            scientific=summarize(read(target/'result.json'),read(target/'initial.json'),protocol)
            if any(row[key]!=value for key,value in scientific.items()):raise ValueError('Recomputed verdict differs')
    passed=all(row['exit_code']==0 and row['analysis_completed'] and row['spatial_criterion_pass'] for row in rows)
    if result['spatial_replication_pass']!=passed:raise ValueError('Aggregate verdict differs')
    output.mkdir(parents=True);inventory=[]
    for path in sorted(study.rglob('*')):
        if not path.is_file():continue
        relative=path.relative_to(study);target=output/relative
        entry=dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path))
        if path.is_symlink():entry['retained_locally']=True
        elif path.suffix in ['.json','.jsonl','.py','.txt','.log']:
            if path.suffix=='.log':target=target.with_suffix('.log.gz')
            target.parent.mkdir(parents=True,exist_ok=True)
            if path.suffix=='.log':
                with target.open('xb') as stream,gzip.GzipFile(fileobj=stream,mode='wb',mtime=0,filename='') as compressed:
                    compressed.write(path.read_bytes())
                entry['archived_sha256']=sha(target)
            else:shutil.copyfile(path,target)
            entry['archived_path']=str(target.relative_to(output))
        else:entry['retained_locally']=True
        inventory.append(entry)
    plot(protocol,rows,output)
    shutil.copyfile(__file__,output/Path(__file__).name)
    extras=[output/Path(__file__).name]+list((output/'figures').iterdir())
    for path in extras:inventory.append(dict(path=str(path),sha256=sha(path),bytes=path.stat().st_size,archived_path=str(path.relative_to(output))))
    write(output/'artifact-manifest.json',dict(schema='hfq-spatial-chain-followup-evidence-v1',
        created_at=datetime.now(timezone.utc).isoformat(),parent_manifest_sha256=parent_sha,
        parent_archive_unchanged=True,numerical_source_sha256=protocol['source_sha256'],
        attempts=len(rows),completed=sum(row['analysis_completed'] for row in rows),
        production_passes=sum(row.get('production_criterion_pass',False) for row in rows),
        intensity_passes=sum(row.get('intensity_criterion_pass',False) for row in rows),
        spatial_passes=sum(row.get('spatial_criterion_pass',False) for row in rows),
        spatial_replication_pass=passed,files=inventory,qualification_claim=False,runtime_comparison_allowed=False))
    for item in inventory:
        if 'archived_path' in item:
            data=(output/item['archived_path']).read_bytes()
            if hashlib.sha256(data).hexdigest()!=item.get('archived_sha256',item['sha256']):raise ValueError('Archive changed')
            if 'archived_sha256' in item and hashlib.sha256(gzip.decompress(data)).hexdigest()!=item['sha256']:
                raise ValueError('Compressed evidence differs')
    if sha(parent)!=parent_sha:raise ValueError('Pilot archive changed')
    print('ARCHIVED',len(rows),'attempts; spatial replication',passed,flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();archive(args.study.resolve(),args.output.resolve())
