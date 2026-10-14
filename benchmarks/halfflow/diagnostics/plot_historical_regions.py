#!/usr/bin/env python3
"""Show all historical regional changes with separate, explicit metric scales."""
import argparse
import hashlib
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def plot(report,names,output):
    if output.exists():raise FileExistsError(output)
    output.mkdir(parents=True)
    rows=json.loads(report.read_text())['pairs'];lookup=json.loads(names.read_text())['labels']
    labels=sorted({s['label'] for p in rows for s in p['structures']})
    fig,axes=plt.subplots(1,2,figsize=(12,15),layout='constrained',sharey=True)
    for ax,metric,title,cmap in zip(axes,['bidirectional_dice_change','bidirectional_hd95_change_mm'],
            ['Dice change (fine − control)','HD95 change in mm (fine − control)'],['RdBu','RdBu_r']):
        values=np.full((len(labels),len(rows)),np.nan)
        for column,pair in enumerate(rows):
            mapped={s['label']:s[metric] for s in pair['structures']}
            for index,label in enumerate(labels):
                if mapped.get(label) is not None:values[index,column]=mapped[label]
        limit=float(np.nanmax(np.abs(values)))
        palette=plt.get_cmap(cmap).copy();palette.set_bad('#cbd5e1')
        image=ax.imshow(values,aspect='auto',interpolation='nearest',cmap=palette,vmin=-limit,vmax=limit)
        ax.set_title(title,fontsize=11)
        ax.set_xticks(range(len(rows)),[p['pair'].replace('oasis-','OASIS ').replace('mmrr-21-','MMRR ').replace('-to-',' → ') for p in rows],rotation=25,ha='right',fontsize=9)
        ax.set_yticks(range(len(labels)),[str(label)+' '+lookup[str(label)].replace('ctx-lh-','L ').replace('ctx-rh-','R ') for label in labels],fontsize=7)
        fig.colorbar(image,ax=ax,shrink=.45,pad=.02)
    fig.suptitle('Observed fine-refinement regional changes · all 62 structures\n'
                 'step-scaled-fine1.5 versus selected control · red: worse · blue: better\n'
                 'Mean of both directions; descriptive results on previously observed pairs',fontsize=12)
    fig.savefig(output/'regional-changes.png',dpi=160)
    fig.savefig(output/'regional-changes.svg')
    plt.close(fig)
    with (output/'provenance.json').open('x') as stream:
        json.dump({'source_sha256':sha(Path(__file__)),'report_sha256':sha(report),'label_names_sha256':sha(names),
                   'metric_scales':'Separate symmetric zero-centered scales, printed in colorbars; red indicates deterioration',
                   'qualification_claim':False},stream,indent=2);stream.write('\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,required=True);parser.add_argument('--names',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();plot(args.report,args.names,args.output)
