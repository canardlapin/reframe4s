#!/usr/bin/env python3
"""Verify every declared pyramid diagnostic and preserve both step-range verdicts."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import math
from pathlib import Path
import shutil

from spatial_chain_followup import read, write, sha, summarize
from objective_probe import verify_engine


def plot(protocol, rows, output):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    figure, axes = plt.subplots(len(rows), 3, figsize=(12, 3.25*len(rows)+1), layout='constrained', sharey=True)
    for index, (task, receipt) in enumerate(zip(protocol['tasks'], rows, strict=True)):
        for column, name in enumerate(protocol['directions']):
            ax = axes[index, column]
            ax.set_title(f"{task['pair']} · level {task['level_index']} · {task['kind']}\n{name}", fontsize=9)
            if not receipt['analysis_completed']:
                text = 'Attempt incomplete' if receipt['attempted'] else 'Not attempted: fine control failed'
                ax.text(.5, .5, text+'\nexit '+str(receipt['exit_code']), ha='center', va='center', transform=ax.transAxes)
                continue
            result = read(output/task['id']/'result.json')
            data = next(d['rows'] for d in result['directions'] if d['direction'] == name)
            for field, label, color in [('relative_error', 'Production gradient', '#b91c1c'),
                    ('intensity_chain_relative_error', 'CC intensity chain', '#2563eb'),
                    ('spatial_chain_relative_error', 'Source/composition chain', '#15803d')]:
                ax.plot([d['epsilon_mm'] for d in data], [max(1e-10, 100*d[field]) for d in data],
                        '-o', ms=2.8, lw=1.1, color=color, label=label)
            ax.axhline(.5, color='#64748b', ls='--', lw=1, label='Unchanged 0.5% criterion')
            ax.axvspan(min(protocol['new_epsilons_mm']), max(protocol['new_epsilons_mm']), color='#64748b', alpha=.08)
            ax.axvline(min(protocol['original_epsilons_mm']), color='#475569', ls=':', lw=1)
            ax.set_xscale('log'); ax.set_yscale('log'); ax.invert_xaxis(); ax.grid(alpha=.15)
            ax.set_xlabel('ε (mm) · decreasing →', fontsize=9)
        axes[index, 0].set_ylabel('Symmetric relative error (%)', fontsize=9)
    handles, labels = [], []
    for row in axes:
        for ax in row:
            handles, labels = ax.get_legend_handles_labels()
            if handles: break
        if handles: break
    if handles: figure.legend(handles, labels, loc='outside lower center', ncol=2, frameon=False, fontsize=9)
    figure.suptitle('Frozen pyramid/cross-cohort derivative diagnostics · all declared states\n'
        'Shaded region = additional small steps; original fine failure retained', fontsize=12)
    figure.savefig(output/'derivative-errors.png', dpi=180)
    figure.savefig(output/'derivative-errors.svg'); plt.close(figure)


def archive(study, output):
    if output.exists(): raise FileExistsError(output)
    protocol, source = verify_engine(study/'engine')
    seal = read(study/'driver-seal.json')
    if (sha(study/'engine/freeze.json') != seal['engine_freeze_sha256']
            or sha(study/'declared-protocol.json') != seal['protocol_sha256']
            or source['sha256'] != seal['source_sha256']
            or read(study/'declared-protocol.json') != protocol):
        raise ValueError('Frozen study seal differs')
    result = read(study/'results.json'); rows = result['rows']
    if len(rows) != len(protocol['tasks']): raise ValueError('Incomplete declared state ledger')
    for path, digest in protocol['inputs'].items():
        if sha(path) != digest: raise ValueError('Frozen input changed')
    for path, digest in protocol['sources'].items():
        if sha(study/('source-'+Path(path).name)) != digest: raise ValueError('Frozen source changed')
    validator = Path(__file__).with_name('spatial_chain_followup.py')
    if sha(validator) != protocol['sources'][str(validator)]: raise ValueError('Verdict validator changed')
    parity = False
    for index, (task, row) in enumerate(zip(protocol['tasks'], rows, strict=True)):
        if row['task'] != task or row != read(study/(task['id']+'-receipt.json')):
            raise ValueError('State attempt identity differs')
        if row['protocol_sha256'] != sha(study/'declared-protocol.json') or row['source_sha256'] != source['sha256']:
            raise ValueError('State provenance differs')
        if row['attempted'] != (index == 0 or parity): raise ValueError('Fine-neutrality execution gate differs')
        if not row['attempted']:
            if row['exit_code'] is not None or row['analysis_completed']: raise ValueError('Unattempted state promoted')
            continue
        if row['log_sha256'] != sha(study/(task['id']+'.log')): raise ValueError('Attempt log changed')
        if not row['analysis_completed']: continue
        target = study/task['id']; initial, data = read(target/'initial.json'), read(target/'result.json')
        if row['exit_code'] != 0 or row['result_sha256'] != sha(target/'result.json') or row['initial_sha256'] != sha(target/'initial.json'):
            raise ValueError('Completed measurements changed')
        if not math.isfinite(initial['audit']['loss']) or abs(initial['audit']['loss']-task['expected_loss']) > protocol['expected_loss_tolerance']:
            raise ValueError('Captured objective differs')
        if (initial['level_index'], initial['shrink']) != (task['level_index'], task['shrink']):
            raise ValueError('Prepared pyramid level differs')
        if summarize(data, initial, protocol) != row['all_step_summary']: raise ValueError('Full-range verdict differs')
        count = len(protocol['original_epsilons_mm'])
        for name, section, steps in [('original', slice(None,count), protocol['original_epsilons_mm']),
                                      ('small', slice(count,None), protocol['new_epsilons_mm'])]:
            subset = dict(data, directions=[dict(d, rows=d['rows'][section]) for d in data['directions']])
            if summarize(subset, initial, dict(protocol, epsilons_mm=steps)) != row[name+'_step_summary']:
                raise ValueError('Step-range verdict differs')
        if index == 0:
            if data != read(Path(protocol['fine_parity_result'])) or not row['fine_measurements_exact']:
                raise ValueError('Fine parity control differs')
            parity = True
    complete = all(row['attempted'] and row['exit_code']==0 and row['analysis_completed'] for row in rows)
    if result['fine_neutrality_pass'] != parity or result['all_states_completed'] != complete or result['original_fine_replication_pass']:
        raise ValueError('Aggregate accounting differs')
    declaration = output.parent/'declaration-v1/artifact-manifest.json'; declaration_sha = sha(declaration)
    if read(declaration)['source_sha256'] != source['sha256']: raise ValueError('Declaration source differs')
    if sha(declaration.parent/'declared-protocol.json') != seal['protocol_sha256']:
        raise ValueError('Pre-execution declaration differs')
    output.mkdir(parents=True)
    files = [p for p in study.iterdir() if p.is_file()]
    files += [p for p in (study/'engine').iterdir() if p.is_file()]
    files += [p for task in protocol['tasks'] for p in (study/task['id']).glob('*') if p.is_file()]
    inventory = []
    for path in sorted(files):
        relative = path.relative_to(study); target = output/relative
        entry = dict(path=str(relative), bytes=path.stat().st_size, sha256=sha(path))
        if path.is_symlink() or path.suffix not in ['.json','.jsonl','.txt','.py','.patch','.log']:
            entry['retained_locally'] = True
        else:
            if path.suffix == '.log': target = target.with_suffix('.log.gz')
            target.parent.mkdir(parents=True, exist_ok=True)
            if path.suffix == '.log':
                with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', filename='', mtime=0) as z:
                    z.write(path.read_bytes())
            else: shutil.copyfile(path, target)
            entry.update(archived_path=str(target.relative_to(output)), archived_sha256=sha(target))
        inventory.append(entry)
    plot(protocol, rows, output); shutil.copyfile(__file__, output/Path(__file__).name)
    for path in [output/Path(__file__).name, *output.glob('derivative-errors.*')]:
        inventory.append(dict(path=path.name, bytes=path.stat().st_size, sha256=sha(path), archived_path=path.name))
    for item in inventory:
        if 'archived_path' not in item: continue
        path = output/item['archived_path']
        if sha(path) != item.get('archived_sha256',item['sha256']): raise ValueError('Archived bytes differ')
        if path.name.endswith('.log.gz') and hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest() != item['sha256']:
            raise ValueError('Compressed evidence differs')
    if sha(declaration) != declaration_sha: raise ValueError('Declaration archive changed')
    write(output/'artifact-manifest.json', dict(schema='hfq-pyramid-chain-evidence-v1',
        created_at=datetime.now(timezone.utc).isoformat(), files=inventory, source_sha256=source['sha256'],
        declaration_manifest_sha256=declaration_sha, declaration_archive_unchanged=True,
        declared_states=len(rows), attempted_states=sum(r['attempted'] for r in rows),
        completed_states=sum(r['analysis_completed'] for r in rows), fine_neutrality_pass=parity,
        new_states_original_step_passes=sum(r.get('original_step_summary',{}).get('spatial_criterion_pass',False) for r in rows[1:]),
        new_states_small_step_passes=sum(r.get('small_step_summary',{}).get('spatial_criterion_pass',False) for r in rows[1:]),
        original_fine_replication_pass=False, qualification_claim=False, runtime_comparison_allowed=False))
    print('ARCHIVED', len(rows), 'declared states; fine neutrality', parity, flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    args = p.parse_args(); archive(args.study.resolve(), args.output.resolve())
