#!/usr/bin/env python3
"""Render the predeclared boundary representation counterexample."""
import argparse
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
from representation import linear_sample


def plot(study,output):
    if output.exists():raise FileExistsError(output)
    output.mkdir(parents=True)
    p=json.loads((study/'results.json').read_text())
    row=next(r for r in p['cases'] if r['case']['id']=='monotone-nonlinear')
    x=np.array(row['x_mm']);f=np.array(row['forward_samples_mm']);queries=np.linspace(-1,52,800)
    figure,axes=plt.subplots(1,2,figsize=(12,4.7),layout='constrained')
    axes[0].plot(queries,linear_sample(f,x,queries),color='#334155',label='Exact inverse of forward map',lw=2)
    for name,color,label in [('same-target-grid','#dc2626','Inverse on original grid'),
            ('padded-to-forward-image-plus-one-cell','#059669','Inverse on padded grid')]:
        v=row['variants'][name]
        axes[0].plot(queries,linear_sample(v['inverse_grid'],v['inverse_samples'],queries),color=color,label=label,ls='--')
        axes[1].plot(x,v['forward_then_inverse_error_mm'],'o-',color=color,label=label,markersize=3)
    axes[0].axvspan(0,24,color='#64748b',alpha=.1,label='Original inverse-grid extent')
    axes[0].set(xlabel='Inverse query coordinate (mm)',ylabel='Returned source coordinate (mm)',title='Exact roots lose accuracy when extrapolated')
    axes[1].axhline(.2,color='#64748b',ls=':',label='0.2 mm bound')
    axes[1].axhline(-.2,color='#64748b',ls=':')
    axes[1].set(xlabel='Original forward-grid coordinate (mm)',ylabel='Inverse(forward(x)) − x (mm)',title='Same 25 evaluation points in both variants')
    for ax in axes:ax.grid(alpha=.15);ax.legend(fontsize=8)
    figure.suptitle('Independent separable synthetic oracle · no production solver or MRI result',fontsize=12)
    figure.savefig(output/'representation.png',dpi=170);figure.savefig(output/'representation.svg');plt.close(figure)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--study',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();plot(args.study,args.output)
