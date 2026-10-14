#!/usr/bin/env python3
"""Archive the separate smaller-step diagnosis, including a negative outcome."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import math
from pathlib import Path
import shutil

from spatial_chain_followup import read, write, sha, summarize
from objective_probe import verify_engine


def archive(study, output):
    if output.exists(): raise FileExistsError(output)
    protocol, source = verify_engine(study/'engine')
    receipt = read(study/'receipt.json')
    if receipt['protocol_sha256'] != sha(study/'declared-protocol.json') or receipt['log_sha256'] != sha(study/'run.log'):
        raise ValueError('Attempt provenance changed')
    if receipt['source_sha256'] != source['sha256'] or receipt['original_replication_pass']:
        raise ValueError('Wrong source or overwritten original verdict')
    for path, digest in protocol['inputs'].items():
        if sha(path) != digest: raise ValueError('Frozen input changed')
    for path, digest in protocol['sources'].items():
        if sha(study/('source-'+Path(path).name)) != digest: raise ValueError('Frozen driver source changed')
    validator = Path(__file__).with_name('spatial_chain_followup.py')
    if sha(validator) != protocol['sources'][str(validator)]: raise ValueError('Summary validator changed')
    if receipt['analysis_completed']:
        initial, result = read(study/'audit/initial.json'), read(study/'audit/result.json')
        if receipt['exit_code'] != 0 or receipt['result_sha256'] != sha(study/'audit/result.json') or receipt['initial_sha256'] != sha(study/'audit/initial.json'):
            raise ValueError('Completed result changed')
        if not math.isfinite(initial['audit']['loss']) or abs(initial['audit']['loss']-protocol['task']['expected_loss']) > protocol['expected_loss_tolerance']:
            raise ValueError('Captured loss differs')
        count = len(protocol['original_epsilons_mm'])
        baseline = read(Path(protocol['original_result']))
        for old, new in zip(baseline['directions'], result['directions'], strict=True):
            if old['rows'] != new['rows'][:count]: raise ValueError('Original measurements differ')
        subset = dict(result, directions=[dict(d, rows=d['rows'][count:]) for d in result['directions']])
        if summarize(result, initial, protocol) != receipt['all_step_summary'] or summarize(subset, initial,
                dict(protocol, epsilons_mm=protocol['new_epsilons_mm'])) != receipt['new_step_summary']:
            raise ValueError('Recomputed verdict differs')
        if not receipt['original_measurements_exact'] or receipt['original_measurement_count'] != count*len(result['directions']):
            raise ValueError('Original measurement accounting differs')
        if receipt['additional_measurement_count'] != len(protocol['new_epsilons_mm'])*len(result['directions']):
            raise ValueError('Additional measurement accounting differs')
    parent = output.parent/'artifact-manifest.json'; parent_sha = sha(parent)
    delta = read(study/'source-delta.json')
    if delta['source_sha256'] != source['sha256'] or delta['base_source_sha256'] != read(output.parent/'source-delta.json')['source_sha256']:
        raise ValueError('Source reconstruction parent differs')
    if delta['patch_sha256'] != sha(study/'source.patch') or not delta['scratch_reconstruction_verified']:
        raise ValueError('Source patch differs')
    output.mkdir(parents=True)
    selected = [p for p in study.iterdir() if p.is_file()]
    selected += [p for p in (study/'engine').iterdir() if p.is_file()]
    selected += [p for p in (study/'audit').glob('*') if p.is_file()]
    inventory = []
    for path in sorted(selected):
        relative = path.relative_to(study); target = output/relative
        entry = dict(path=str(relative), bytes=path.stat().st_size, sha256=sha(path))
        if path.is_symlink() or path.suffix not in ['.json', '.jsonl', '.py', '.txt', '.patch', '.log']:
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
    if receipt['analysis_completed']:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        figure, axes = plt.subplots(1, 3, figsize=(13, 4.5), layout='constrained', sharey=True)
        for ax, direction in zip(axes, result['directions'], strict=True):
            rows = direction['rows']; epsilon = [r['epsilon_mm'] for r in rows]
            for field, label, color in [('relative_error', 'Production gradient', '#b91c1c'),
                    ('intensity_chain_relative_error', 'CC intensity chain', '#2563eb'),
                    ('spatial_chain_relative_error', 'Source/composition chain', '#15803d')]:
                ax.plot(epsilon, [max(1e-10, 100*r[field]) for r in rows], '-o', ms=3, lw=1.1, label=label, color=color)
            ax.axhline(.5, color='#64748b', ls='--', lw=1, label='Unchanged 0.5% criterion')
            ax.axvspan(min(epsilon), max(protocol['new_epsilons_mm']), color='#64748b', alpha=.08)
            ax.axvline(min(protocol['original_epsilons_mm']), color='#475569', ls=':', lw=1)
            ax.set_xscale('log'); ax.set_yscale('log'); ax.invert_xaxis(); ax.grid(alpha=.15)
            ax.set_title(direction['direction']); ax.set_xlabel('ε (mm) · decreasing →')
        axes[0].set_ylabel('Symmetric relative error (%)')
        figure.legend(*axes[0].get_legend_handles_labels(), loc='outside lower center', ncol=2, frameon=False)
        figure.suptitle('OASIS 13 → 14 control fine end · separate smaller-step diagnosis\n'
            'Original replication remains failed; shaded region = newly declared steps', fontsize=12)
        figure.savefig(output/'derivative-errors.png', dpi=180); figure.savefig(output/'derivative-errors.svg'); plt.close(figure)
    shutil.copyfile(__file__, output/Path(__file__).name)
    extra = [output/Path(__file__).name] + list(output.glob('derivative-errors.*'))
    for path in extra: inventory.append(dict(path=path.name, bytes=path.stat().st_size, sha256=sha(path), archived_path=path.name))
    for item in inventory:
        if 'archived_path' not in item: continue
        path = output/item['archived_path']
        if sha(path) != item.get('archived_sha256', item['sha256']): raise ValueError('Archived artifact differs')
        if path.name.endswith('.log.gz') and hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest() != item['sha256']:
            raise ValueError('Compressed evidence differs')
    if sha(parent) != parent_sha: raise ValueError('Parent archive changed')
    write(output/'artifact-manifest.json', dict(schema='hfq-spatial-smaller-step-evidence-v1',
        created_at=datetime.now(timezone.utc).isoformat(), parent_manifest_sha256=parent_sha,
        parent_archive_unchanged=True, source_sha256=source['sha256'], files=inventory,
        analysis_completed=receipt['analysis_completed'], original_replication_pass=False,
        small_step_spatial_pass=receipt.get('new_step_summary', {}).get('spatial_criterion_pass'),
        qualification_claim=False, runtime_comparison_allowed=False))
    print('ARCHIVED', len(inventory), 'artifacts; original replication remains failed', flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--study', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    args = p.parse_args(); archive(args.study.resolve(), args.output.resolve())
