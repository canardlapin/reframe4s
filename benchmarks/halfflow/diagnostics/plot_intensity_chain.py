#!/usr/bin/env python3
"""Show both derivative paths against the same frozen-objective difference."""
import argparse
import hashlib
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def plot(study, output):
    if output.exists():raise FileExistsError(output)
    receipt=json.loads((study/'receipt.json').read_text())
    if receipt['exit_code']!=0:raise ValueError('A completed probe is required')
    result=json.loads((study/'audit/result.json').read_text())
    output.mkdir(parents=True)
    figure, axes=plt.subplots(1,3,figsize=(12,4.5),layout='constrained',sharey=True)
    for ax, direction in zip(axes,result['directions'],strict=True):
        rows=direction['rows'];eps=[r['epsilon_mm'] for r in rows]
        for field,label,color in [('relative_error','Production velocity gradient','#b91c1c'),
                                  ('intensity_chain_relative_error','CC gradient × actual image changes','#2563eb')]:
            errors=[100*r[field] for r in rows]
            if any(e<=0 for e in errors):raise ValueError('Log plot requires positive measured errors')
            ax.plot(eps,errors,'-o',color=color,ms=4,label=label)
        ax.axhline(.5,color='#64748b',ls='--',lw=1,label='Declared 0.5% criterion')
        ax.set_xscale('log');ax.set_yscale('log');ax.invert_xaxis()
        ax.set_title(direction['direction'].replace('-',' ').capitalize())
        ax.set_xlabel('Perturbation ε (mm) · decreasing →')
        ax.grid(alpha=.18);ax.set_axisbelow(True)
    axes[0].set_ylabel('Symmetric relative derivative error (%)')
    handles,labels=axes[0].get_legend_handles_labels()
    figure.legend(handles,labels,loc='outside lower center',ncol=3,frameon=False,fontsize=9)
    figure.suptitle('OASIS 11 → 12 · fine-start checkpoint · all 24 perturbations\n'
        'Both paths compared with the same frozen-objective finite difference',fontsize=12)
    figure.savefig(output/'intensity-chain.png',dpi=180)
    figure.savefig(output/'intensity-chain.svg')
    plt.close(figure)
    (output/'provenance.json').write_text(json.dumps(dict(schema='hfq-intensity-chain-plot-v1',
        source_sha256=sha(Path(__file__)),receipt_sha256=sha(study/'receipt.json'),
        result_sha256=sha(study/'audit/result.json'),qualification_claim=False),indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();plot(a.study.resolve(),a.output.resolve())
