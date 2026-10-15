#!/usr/bin/env python3
"""Archive the cell admission patch and compact diagnostics, excluding fields."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil
import sys

from analyze_c04 import read, write, sha

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/halfflow/diagnostics'))
from archive_objective_probe import source_delta

BASE = ROOT / 'benchmarks/flashalign/local-results/hfq-diagnostics-20261014/grid-zero-study-v1/engine'
ALLOWED = {
    'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/' + name for name in
    ['ForwardMidpoint.scala', 'ForwardMidpointExport.scala', 'TopologyGuard.scala', 'TrilinearCellGeometry.scala']
} | {
    'modules/reframe4s-halfflow/shared/src/test/scala/reframe4s/halfflow/' + name for name in
    ['TrilinearCellGeometrySuite.scala', 'ForwardCellAdmissionSuite.scala', 'HalfFlowCcEngineSuite.scala', 'BasinBridgeAssimilatorSuite.scala']
} | {'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageCellGeometryAudit.scala'}


def archive(root, output, stage):
    if output.exists():
        raise FileExistsError(output)
    native = root / 'native-parity-v1'
    capture = root / 'c04-replay-v1'
    protocol = read(capture / 'protocol.json')
    if not protocol['production_cell_admission_changed'] or protocol['historical_field_hash_replay_required']:
        raise ValueError('Prospective corrected-admission declaration required')
    if stage == 'declaration':
        if (native / 'receipt.json').exists() or (capture / 'driver.log').exists():
            raise ValueError('Declaration must precede execution')
    else:
        if not read(native / 'receipt.json')['passed']:
            raise ValueError('All 48 native parity outcomes required')
        receipt = read(capture / 'receipt.json')
        if receipt['exit_code'] != 0 or not receipt['source_and_inputs_unchanged']:
            raise ValueError('Terminal immutable synthetic replay required')
        if len(read(root / 'c04-cells-v1/results.json')['rows']) != 48:
            raise ValueError('All 48 corrected fields must be independently audited')
    output.mkdir(parents=True)
    source_delta(BASE, native / 'engine', output)
    delta = read(output / 'source-delta.json')
    if set(delta['changed_files']) != ALLOWED:
        raise ValueError('Unexpected source changes: ' + str(set(delta['changed_files']) ^ ALLOWED))
    delta['base_reproduction'] = ('Reconstruct the 5b792b86 acquired-grid/zero-status engine using '
        'docs/benchmarks/halfflow-acquired-grid-audit-v1.md and its archived patch chain, then apply source.patch. '
        'This patch deliberately preserves all other dirty prerequisites.')
    write(output / 'source-delta.json', delta)
    paths = [p for p in root.iterdir() if p.is_file() and p.suffix in ['.json', '.log']]
    paths += [root / 'pre-fix/snapshot.json']
    if (root / 'pre-fix/analyze_c04.py').exists():
        paths += [root / 'pre-fix/analyze_c04.py']
    paths += [p for p in native.iterdir() if p.is_file()]
    paths += [p for p in (native / 'engine').iterdir() if p.is_file()]
    paths += [p for p in capture.rglob('*') if p.is_file() and 'fields' not in p.relative_to(capture).parts]
    for name in ['remaining-success-exports-v1', 'remaining-success-analysis-v1']:
        paths += [p for p in (root / name).iterdir() if p.is_file()]
    if stage == 'outcomes':
        paths += [p for p in (native / 'native').iterdir() if p.is_file()]
        paths += [p for p in (root / 'c04-cells-v1').iterdir() if p.is_file()]
        paths += [p for p in (root / 'r24-corrected-roots-v1').iterdir() if p.is_file()]
    paths += [Path(__file__).with_name(name) for name in
        ['archive_cell_fix.py', 'native_cells.py', 'capture_c04.py', 'audit_cells.py', 'summarize_cell_fix.py',
         'analyze_c04.py', 'field_oracle.py', 'test_field_oracle.py']]
    inventory = []
    for path in sorted(set(paths)):
        if not path.is_file() or path.is_symlink() or path.suffix not in ['.json', '.py', '.log', '.txt', '.patch', '.jsonl']:
            raise ValueError('Unexpected compact evidence: ' + str(path))
        relative = path.relative_to(root) if path.is_relative_to(root) else Path('sources') / path.name
        target = output / relative
        if path.suffix in ['.log', '.jsonl']:
            target = target.with_name(target.name + '.gz')
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.suffix in ['.log', '.jsonl']:
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', filename='', mtime=0) as compressed:
                compressed.write(path.read_bytes())
        else:
            shutil.copyfile(path, target)
        inventory.append(dict(path=str(relative), sha256=sha(path), bytes=path.stat().st_size,
            archived_path=str(target.relative_to(output)), archived_sha256=sha(target)))
    for item in inventory:
        target = output / item['archived_path']
        if sha(target) != item['archived_sha256']:
            raise ValueError('Archive bytes differ')
        if target.suffix == '.gz' and hashlib.sha256(gzip.decompress(target.read_bytes())).hexdigest() != item['sha256']:
            raise ValueError('Compressed evidence differs')
    write(output / 'artifact-manifest.json', dict(schema='hfq-cell-admission-evidence-v1', stage=stage,
        created_at=datetime.now(timezone.utc).isoformat(), source_sha256=delta['source_sha256'], files=inventory,
        source_patch_sha256=sha(output / 'source.patch'), source_delta_sha256=sha(output / 'source-delta.json'),
        source_patch_reconstruction_verified=True, mri_or_dense_map_payloads=False,
        qualification_claim=False, runtime_comparison_allowed=False))
    print('ARCHIVED', stage, len(inventory), 'compact artifacts; nine-file source patch verified', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=['declaration', 'outcomes'], required=True)
    args = parser.parse_args()
    archive(args.root.resolve(), args.output.resolve(), args.stage)
