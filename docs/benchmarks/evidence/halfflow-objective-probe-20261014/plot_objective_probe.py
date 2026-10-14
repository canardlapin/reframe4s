#!/usr/bin/env python3
"""Plot every declared derivative sample, including failed criteria."""
import argparse
import hashlib
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def plot(study, output):
    if output.exists():
        raise FileExistsError(output)
    receipt = json.loads((study/'receipt.json').read_text())
    if receipt['exit_code'] != 0:
        raise ValueError('Completed probe required')
    result_path = study/'audit/result.json'
    if sha(result_path) != receipt['result_sha256']:
        raise ValueError('Probe result changed')
    result = json.loads(result_path.read_text())
    protocol = json.loads((study/'engine/protocol.json').read_text())
    output.mkdir(parents=True)
    figure, axes = plt.subplots(1, 2, figsize=(11, 4.8), layout='constrained')
    colors = ['#2563eb', '#d97706', '#15803d']
    for direction, color in zip(result['directions'], colors):
        rows = direction['rows']
        epsilon = [row['epsilon_mm'] for row in rows]
        axes[0].plot(epsilon, [100*row['relative_error'] for row in rows], 'o-',
                     color=color, label=direction['direction'])
        axes[1].plot(epsilon, [row['finite_difference']/row['analytic'] for row in rows],
                     'o-', color=color)
    axes[0].axhline(100*protocol['maximum_minimum_relative_derivative_error'],
                   linestyle='--', color='#555', label='0.5% criterion')
    axes[1].axhline(1, linestyle='--', color='#555')
    axes[0].set_yscale('log')
    axes[0].set_ylim(.3, 25)
    axes[0].set_ylabel('Symmetric relative derivative error (%)')
    axes[1].set_ylabel('Measured derivative / predicted derivative')
    axes[0].set_title('All three directions fail the declared criterion')
    axes[1].set_title('Signs agree; magnitudes differ')
    for ax in axes:
        ax.set_xscale('log')
        ax.invert_xaxis()
        ax.set_xlabel('Perturbation scale (mm), decreasing →')
        ax.grid(alpha=.2)
        ax.set_axisbelow(True)
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(handles, labels, frameon=False, fontsize=9, loc='outside lower center', ncol=4)
    figure.suptitle('Observed OASIS 11 → 12 · fine-level start · frozen production CC objective', fontsize=12)
    figure.savefig(output/'derivatives.png', dpi=180)
    figure.savefig(output/'derivatives.svg')
    plt.close(figure)
    manifest = {'source_sha256': sha(Path(__file__)), 'result_sha256': sha(result_path),
        'receipt_sha256': sha(study/'receipt.json'), 'protocol_sha256': sha(study/'engine/protocol.json'),
        'scope': 'All 24 predeclared derivative samples; one observed checkpoint; no qualification claim',
        'files': {p.name: sha(p) for p in output.iterdir() if p.is_file()}}
    (output/'provenance.json').write_text(json.dumps(manifest, indent=2)+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    plot(args.study.resolve(), args.output.resolve())
