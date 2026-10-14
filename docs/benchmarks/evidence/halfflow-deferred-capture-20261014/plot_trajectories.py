#!/usr/bin/env python3
"""Plot every scalar event and scored checkpoint without selecting an output."""
import argparse
import hashlib
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np


def read(path):return json.loads(Path(path).read_text())
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def plot_case(study,analysis,case,policy,output):
    base=study/case/policy
    fit_path=base.parent/(policy+'-fit-receipt.json')
    fit=read(fit_path)
    fit_status=('completed' if fit['exit_code']==0 else 'timed out' if fit['exit_code']==124 else 'failed/cancelled')
    fit_caption=f"Source fit {fit_status} (exit {fit['exit_code']}); committed checkpoints only"
    events=[json.loads(line) for line in (base/'checkpoints/events.jsonl').read_text().splitlines()]
    receipts=sorted((analysis/case/policy).glob('event-*.json'))
    rows=[read(path) for path in receipts]
    by_serial={r['event']['serial']:r for r in rows}
    states=[r for r in rows if r['status']=='scored']
    if not states:raise ValueError('No scored checkpoints: '+case+'/'+policy)
    output.mkdir(parents=True)
    figure,axes=plt.subplots(5,2,figsize=(14,15),layout='constrained',sharex=True)
    axes=axes.ravel()
    xs=[r['serial'] for r in events]
    trial=[r for r in events if r['info']['trial']]
    tx=[r['serial'] for r in trial]
    for field,label,color in [('before','Frozen current loss','#64748b'),('after','Rebuilt loss','#2563eb')]:
        axes[0].plot(xs,[r['info'][field]['loss'] for r in events],label=label,color=color,lw=1.4)
    axes[0].plot(tx,[r['info']['trial']['candidateLoss'] for r in trial],'.',color='#d97706',label='Frozen candidate loss')
    axes[0].set_title('Optimizer losses · different frozen weights at each step')
    axes[0].legend(fontsize=8)
    sx=[r['event']['serial'] for r in states]
    def checkpoints(ax,values,label=None,color='#2563eb'):
        # Rejected candidates are points, not members of the accepted path.
        accepted=[i for i,r in enumerate(states) if r['event']['info']['kind']!='Rejected']
        ax.plot([sx[i] for i in accepted],[values[i] for i in accepted],'-o',markersize=3,color=color,label=label,lw=1)
        rejected=[i for i,r in enumerate(states) if r['event']['info']['kind']=='Rejected']
        if rejected:ax.scatter([sx[i] for i in rejected],[values[i] for i in rejected],marker='x',color='#d97706',s=36)
        invalid=[i for i,r in enumerate(states) if not (r['native_export_accepted'] and
            r['geometry_and_anatomy']['geometry_pass'] and r['geometry_and_anatomy']['cells_pass'])]
        if invalid:ax.scatter([sx[i] for i in invalid],[values[i] for i in invalid],facecolors='none',edgecolors='#b91c1c',s=70,zorder=4)
    checkpoints(axes[1],[r['fixed_intensity']['mean_bidirectional_loss'] for r in states])
    axes[1].set_title('Fixed-region squared-CC audit loss ↓ · invalid windows penalized')
    checkpoints(axes[2],[r['geometry_and_anatomy']['mean_dice'] for r in states])
    axes[2].set_title('Mean bidirectional per-label Dice ↑')
    checkpoints(axes[3],[r['geometry_and_anatomy']['mean_hd95_mm'] for r in states])
    axes[3].set_title('Bidirectional median-label HD95 (mm) ↓')
    axes[4].plot(xs,[r['info']['after']['activeFraction'] for r in events],label='Active fraction')
    axes[4].plot(xs,[r['info']['after']['support']['sum']/r['info']['after']['support']['count'] for r in events],label='Mean support')
    axes[4].plot(xs,[r['info']['after']['neighborhood']['variance']['jointReliabilityMean'] for r in events],label='Mean joint reliability')
    axes[4].set_title('Rebuilt support and variance reliability');axes[4].legend(fontsize=8)
    axes[5].plot(tx,[r['info']['trial']['maximumStepMm'] for r in trial],'.-',markersize=3)
    axes[5].set_yscale('log');axes[5].set_title('Maximum attempted update (mm)')
    checkpoints(axes[6],[r['geometry_and_anatomy']['minimum_cell_jacobian'] for r in states])
    axes[6].axhline(.05,color='#b91c1c',ls=':',lw=1);axes[6].set_title('Minimum sampled complete-cell Jacobian · limit 0.05')
    checkpoints(axes[7],[r['geometry_and_anatomy']['maximum_brain_roundtrip_mm'] for r in states])
    axes[7].axhline(.2,color='#b91c1c',ls=':',lw=1);axes[7].set_title('Maximum brain round trip (mm) · limit 0.2')
    for arm,color in [('fixed','#2563eb'),('moving','#059669')]:
        checkpoints(axes[8],[r['regularity'][arm]['rms_displacement_gradient_frobenius'] for r in states],arm,color)
    axes[8].legend(fontsize=8);axes[8].set_title('RMS midpoint residual displacement-gradient norm')
    checkpoints(axes[9],[min(d['window_coverage'] for d in r['fixed_intensity']['directions'].values()) for r in states],'Fixed audit windows')
    checkpoints(axes[9],[r['geometry_and_anatomy']['minimum_brain_coverage'] for r in states],'Brain map coverage','#059669')
    axes[9].set_ylim(0,1.03);axes[9].set_title('Minimum bidirectional coverage');axes[9].legend(fontsize=8)
    transitions=[r['serial'] for r in events if r['info']['kind']=='LevelStart' and r['info']['levelIndex']>0]
    for ax in axes:
        for x in transitions:ax.axvline(x,color='#64748b',ls='--',alpha=.5,lw=1)
        ax.grid(alpha=.15);ax.set_axisbelow(True)
    for ax in axes[-2:]:ax.set_xlabel('Event sequence · includes rejected attempts and level transitions')
    failures=sum(r['status'] in ['analysis_failed','export_unavailable'] for r in rows)
    pending=len(events)-len(rows)
    figure.suptitle(f'{case} · {policy}\nObserved diagnostics · orange ×: rejected candidate · red ring: failed geometry/export\n'
                   f'{len(states)} scored states; {failures} export/analysis failures; {pending} pending events\n{fit_caption}',fontsize=12)
    figure.savefig(output/'trajectory.png',dpi=160);figure.savefig(output/'trajectory.svg');plt.close(figure)
    # Each structure retains its own score. No intersection-based structure filtering.
    anatomy=[]
    for row in states:
        directory=analysis/case/policy/row['event']['directory']
        paths=list(directory.glob('*-anatomy-evaluation.json'))
        if len(paths)!=1:raise ValueError('Exactly one anatomy report required')
        anatomy.append(read(paths[0]))
    labels=sorted({r['label'] for report in anatomy for d in report['directions'].values() for r in d['anatomy']['labels']})
    heat,heat_axes=plt.subplots(1,2,figsize=(max(14,len(states)*.65),16),layout='constrained',sharey=True)
    for ax,metric,title,cmap in zip(heat_axes,['dice','hd95_mm'],['Bidirectional Dice ↑','Bidirectional HD95 (mm) ↓'],['viridis','magma_r']):
        values=np.full((len(labels),len(states)),np.nan)
        for column,report in enumerate(anatomy):
            directions=[{r['label']:r.get(metric) for r in d['anatomy']['labels']} for d in report['directions'].values()]
            for index,label in enumerate(labels):
                found=[d.get(label) for d in directions]
                if len(found)==2 and all(v is not None for v in found):values[index,column]=np.mean(found)
        palette=plt.get_cmap(cmap).copy();palette.set_bad('#cbd5e1')
        im=ax.imshow(values,aspect='auto',interpolation='nearest',cmap=palette,vmin=0,vmax=1 if metric=='dice' else None)
        ax.set_title(title);ax.set_yticks(range(len(labels)),labels,fontsize=7)
        ticks=[f"L{r['event']['info']['levelIndex']} A{r['event']['info']['acceptedSteps']} T{r['event']['info']['attempts']} "
               +r['event']['info']['kind'] for r in states]
        ax.set_xticks(range(len(states)),ticks,rotation=90,fontsize=7)
        heat.colorbar(im,ax=ax,shrink=.6)
    heat_axes[0].set_ylabel('Anatomical label ID · gray means missing, never silently excluded')
    heat.suptitle(f'{case} · {policy} · all scored states, including rejected/invalid candidates\n'
                  'L: pyramid level; A: accepted updates; T: attempted updates · labels used only for diagnosis\n'+fit_caption,fontsize=12)
    heat.savefig(output/'per-structure.png',dpi=150);heat.savefig(output/'per-structure.svg');plt.close(heat)
    accepted=[r for r in events if r['info']['kind']=='Accepted']
    summary={'schema':'hfq-trajectory-plot-v1','case':case,'policy':policy,'source_sha256':sha(Path(__file__)),
             'events_sha256':sha(base/'checkpoints/events.jsonl'),'receipt_sha256':{str(p):sha(p) for p in receipts},
             'fit_receipt_sha256':sha(fit_path),'fit_exit_code':fit['exit_code'],
             'event_count':len(events),'scored_states':len(states),'export_or_analysis_failures':failures,'pending_events':pending,
             'accepted_steps':len(accepted),'accepted_steps_with_rebuilt_loss_above_previous_loss':
                 sum(r['info']['after']['loss']>r['info']['before']['loss'] for r in accepted),
             'unscored_events':[{ 'serial':r['serial'],'capture_status':r['capture_status'],
                 'analysis_status':by_serial[r['serial']]['status'] if r['serial'] in by_serial else 'pending'}
                 for r in events if r['serial'] not in by_serial or by_serial[r['serial']]['status']!='scored'],
             'qualification_claim':False,'runtime_checkpoint_selected':False}
    with (output/'summary.json').open('x') as stream:json.dump(summary,stream,indent=2);stream.write('\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study',type=Path,required=True);parser.add_argument('--analysis',type=Path,required=True)
    parser.add_argument('--case',required=True);parser.add_argument('--policy',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();plot_case(args.study,args.analysis,args.case,args.policy,args.output)
