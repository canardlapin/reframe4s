#!/usr/bin/env python3
"""Freeze and execute an explicit recovery experiment, retaining every failed job.

Development and confirmation use separate immutable protocols and directories.
Labels occur only in evaluation commands, never in registration commands.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys

from real_image_court import sha, write_json
from run_real_mri_assessment import ROOT, COURT, execute
from summarize_real_mri_assessment import compact, improvement

HERE = Path(__file__).resolve()


def read(path):
    return json.loads(Path(path).read_text())


def freeze(output, protocol, classpath):
    assert not (output / 'freeze.json').exists(), 'already frozen'
    output.mkdir(parents=True, exist_ok=True)
    source = json.loads(subprocess.check_output(['node', 'scripts/halfflow-candidate.mjs'], cwd=ROOT))
    for p in (HERE, COURT, HERE.with_name('run_real_mri_assessment.py'),
              HERE.with_name('summarize_real_mri_assessment.py'), HERE.with_name('real_mri_cell_topology.py'),
              HERE.parent / 'receipts/within-modality-mri-budget-v2.json',
              HERE.parent / 'receipts/within-modality-affine-recovery-v1.json',
              HERE.parent / 'receipts/within-modality-affine-recovery-v2.json'):
        source['files'][str(p.relative_to(ROOT))] = sha(p)
    source['sha256'] = hashlib.sha256(json.dumps(source['files'], sort_keys=True).encode()).hexdigest()
    for name in source['files']:
        target = output / 'frozen-source' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, target)
    cp = []
    for i, value in enumerate(classpath.read_text().strip().split(os.pathsep)):
        p = Path(value); assert p.exists(), p
        if p.is_dir():
            target = output / 'frozen-classpath' / str(i)
            shutil.copytree(p, target); cp.append(str(target))
        else:
            cp.append(str(p))
    (output / 'classpath.txt').write_text(os.pathsep.join(cp) + '\n')
    manifest = {str(f): sha(f) for value in cp for f in
                (sorted(Path(value).rglob('*')) if Path(value).is_dir() else [Path(value)]) if f.is_file()}
    write_json(output / 'compiled-classpath.json', manifest)
    shutil.copyfile(protocol, output / 'protocol.json')
    inputs = {}
    for c in read(protocol)['cases']:
        for key in ('moving_labels', 'fixed_labels', 'baseline_report', 'saved_affine'):
            if c.get(key): inputs[c[key]] = sha(Path(c[key]))
        for p in Path(c['data']).glob('*.nii.gz'): inputs[str(p)] = sha(p)
    write_json(output / 'inputs.json', inputs)
    write_json(output / 'source.json', source)
    write_json(output / 'freeze.json', dict(source_sha256=source['sha256'],
        protocol_sha256=sha(protocol), inputs_sha256=sha(output / 'inputs.json'),
        classpath_manifest_sha256=sha(output / 'compiled-classpath.json'),
        classpath_sha256=sha(output / 'classpath.txt'),
        frozen_at=datetime.now(timezone.utc).isoformat()))
    print('FROZEN', source['sha256'], flush=True)


def verify(output):
    frozen = read(output / 'freeze.json'); source = read(output / 'source.json')
    assert source['sha256'] == frozen['source_sha256']
    assert hashlib.sha256(json.dumps(source['files'], sort_keys=True).encode()).hexdigest() == source['sha256']
    assert sha(output / 'protocol.json') == frozen['protocol_sha256']
    assert sha(output / 'classpath.txt') == frozen['classpath_sha256']
    for name, digest in source['files'].items():
        assert sha(output / 'frozen-source' / name) == digest
    for manifest, key in [('inputs.json', 'inputs_sha256'), ('compiled-classpath.json', 'classpath_manifest_sha256')]:
        assert sha(output / manifest) == frozen[key]
        for name, digest in read(output / manifest).items(): assert sha(Path(name)) == digest, name
    # Execution uses only frozen classes and Python sources, so later live edits
    # cannot silently change an already frozen experiment.
    return read(output / 'protocol.json'), source


def run(output):
    protocol, source = verify(output)
    env = os.environ.copy()
    env.update(OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', ITK_GLOBAL_DEFAULT_NUMBER_OF_THREADS='1')
    cp = (output / 'classpath.txt').read_text().strip()
    court = output / 'frozen-source/benchmarks/flashalign/real_image_court.py'
    cells = court.with_name('real_mri_cell_topology.py')
    def job(item):
        case, lane = item; directory = output / case['id']; target = directory / lane['id']; data = Path(case['data'])
        saved_affine = case.get('saved_affine')
        if lane.get('affine_from_lane'):
            saved_affine = str(directory / lane['affine_from_lane'] / 'flashalign-affine-moving-to-fixed.json')
            upstream = read(directory / 'commands' / (lane['affine_from_lane'] + '.json'))
            if upstream['exit_code'] != 0:
                destination = directory / 'commands' / (lane['id'] + '.json')
                if not destination.exists():
                    write_json(destination, dict(exit_code=None, seconds=0, source_sha256=source['sha256'],
                        skipped=True, failure='Upstream affine failed; refinement was not attempted.',
                        upstream_receipt_sha256=sha(directory / 'commands' / (lane['affine_from_lane'] + '.json'))))
                return
        command = [shutil.which('java'), '-Xmx6G', '-XX:ActiveProcessorCount=2', '-cp', cp,
                   'reframe4s.halfflow.RealImageRegistration', str(data / 'moving.nii.gz'),
                   str(data / 'fixed.nii.gz'), str(data / 'fixed-mask.nii.gz'), str(target), lane['mode']]
        if lane['mode'] != 'affine': command.append(saved_affine)
        command += lane.get('flags', [])
        if lane.get('affine_budget'):
            command += ['--affine-budget', str(lane['affine_budget']), '--policy-receipt', str(output / 'protocol.json')]
        execute(command, directory / 'commands' / lane['id'], source, env)
        if lane['mode'] == 'affine' and not case.get('baseline_report'):
            execute([sys.executable, str(court), 'evaluate', '--data', case['data'], '--output', str(target),
                     '--lane', 'identity', '--moving-labels', case['moving_labels'], '--fixed-labels', case['fixed_labels']],
                    directory / 'commands' / ('identity-' + lane['id']), source, env)
        mapping = 'affine' if lane['mode'] == 'affine' else 'halfflow'
        if not (target / (mapping + '-fixed-to-moving.json')).exists(): mapping = 'halfflow-rejected'
        if (target / (mapping + '-fixed-to-moving.json')).exists():
            execute([sys.executable, str(court), 'evaluate', '--data', case['data'], '--output', str(target),
                     '--lane', mapping, '--moving-labels', case['moving_labels'], '--fixed-labels', case['fixed_labels']],
                    directory / 'commands' / ('evaluate-' + lane['id']), source, env)
            execute([sys.executable, str(cells), '--directory', str(target), '--lane', mapping,
                     '--classpath', str(output / 'classpath.txt'), '--source-sha256', source['sha256']],
                    directory / 'commands' / ('cells-' + lane['id']), source, env)
    assert 1 <= protocol['workers'] <= 3
    with ThreadPoolExecutor(protocol['workers']) as pool:
        # Complete each lane before dependent lanes read its saved affine.
        for lane in protocol['lanes']:
            list(pool.map(job, [(c, lane) for c in protocol['cases']]))
    summarize(output)


def summarize(output):
    protocol = read(output / 'protocol.json'); rows = []
    for lane in protocol['lanes']:
        for case in protocol['cases']:
            directory = output / case['id'] / lane['id']
            receipt = read(directory.parent / 'commands' / (lane['id'] + '.json'))
            expected = 'affine' if lane['mode'] == 'affine' else 'halfflow'
            path = directory / (expected + '-evaluation.json')
            if not path.exists(): path = directory / 'halfflow-rejected-evaluation.json'
            report = read(path) if path.exists() else None
            topology = directory / (path.name.replace('-evaluation', '-cell-topology'))
            cell = read(topology) if topology.exists() else None
            baseline = case.get('baseline_report') or str(directory / 'identity-evaluation.json')
            if lane.get('baseline_from_lane'):
                baseline = str(directory.parent / lane['baseline_from_lane'] / 'affine-evaluation.json')
            gain = improvement(read(baseline), report) if report else None
            fit = receipt['exit_code'] == 0 and bool(report) and report.get('native_export_accepted', True)
            diagnostics = directory / 'halfflow-diagnostics.txt'
            levels = [dict(shrink=int(a), initial_loss=float(b), final_loss=float(c),
                           accepted_updates=int(d), attempts=int(e), termination=f)
                      for a,b,c,d,e,f in re.findall(
                          r'HalfFlowCcLevelDiagnostics\((\d+),([^,]+),([^,]+),(\d+),(\d+),([^,]+)',
                          diagnostics.read_text() if diagnostics.exists() else '')]
            row = dict(pair=case['id'], lane=lane['id'], fit=fit,
                geometry=bool(report and report['geometry_pass']), anatomy=bool(gain and gain['pass']),
                cells=bool(cell and cell['pass_criteria'] and all(d['native_certificate_accepted'] for d in cell['directions'].values())),
                directions=compact(report) if report else None, gain=gain,
                levels=levels, accepted_updates=sum(l['accepted_updates'] for l in levels) if levels else None,
                report_sha256=sha(path) if report else None, seconds=receipt['seconds'],
                failure=(directory / 'failure.txt').read_text().splitlines()[0] if (directory / 'failure.txt').exists() else receipt.get('failure'))
            row['all_checks'] = all(row[k] for k in ('fit', 'geometry', 'anatomy', 'cells'))
            rows.append(row)
    write_json(output / 'results.json', dict(source_sha256=read(output / 'source.json')['sha256'],
               protocol_sha256=sha(output / 'protocol.json'), rows=rows))
    for lane in protocol['lanes']:
        selected = [r for r in rows if r['lane'] == lane['id']]
        print(lane['id'], {k: sum(r[k] for r in selected) for k in ('fit', 'geometry', 'anatomy', 'cells', 'all_checks')}, flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage', choices=['freeze', 'run', 'summarize'])
    p.add_argument('--output', type=Path, required=True); p.add_argument('--protocol', type=Path); p.add_argument('--classpath', type=Path)
    args = p.parse_args(); output = args.output.resolve()
    if args.stage == 'freeze': freeze(output, args.protocol.resolve(), args.classpath.resolve())
    elif args.stage == 'run': run(output)
    else: summarize(output)


if __name__ == '__main__': main()
