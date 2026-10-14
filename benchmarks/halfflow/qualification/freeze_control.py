#!/usr/bin/env python3
"""Seal current HalfFlow source, five observed inputs and compiled dependencies.

Requires a successful fresh build log with an exported runtime classpath.
Writes a new local run; refuses to overwrite an existing control seal.
"""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from run_real_mri_recovery import freeze, verify
from real_image_court import sha, write_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build-log', type=Path, required=True)
    p.add_argument('--hardware', type=Path, required=True)
    p.add_argument('--run', type=Path, required=True)
    args = p.parse_args()
    seal_path = HERE / 'control-seal.json'
    assert not seal_path.exists() and not args.run.exists(), 'new seal and run required'
    text = args.build_log.read_text()
    assert '[error]' not in text and '0 failed, 0 ignored, 15 total' in text
    assert '0 failed, 0 ignored, 11 total' in text and '0 failed, 0 ignored, 6 total' in text
    cp = [line for line in text.splitlines() if line.startswith('/') and '.jar' in line and os.pathsep in line]
    assert len(cp) == 1, 'exactly one exported runtime classpath required'
    paths = cp[0].split(os.pathsep)
    assert all(Path(path).exists() for path in paths)
    args.run.mkdir(parents=True)
    classpath = args.run / 'build-classpath.txt'
    classpath.write_text(cp[0] + '\n')
    previous = ROOT / 'benchmarks/flashalign/local-results/real-mri-recovery-20261011'
    cases = []
    for phase in ['sentinels', 'development']:
        old = json.loads((previous / phase / 'protocol.json').read_text())
        cases.extend(old['cases'])
    assert len(cases) == 5 and len({c['id'] for c in cases}) == 5
    contract = json.loads((HERE / 'protocol-v1.json').read_text())
    run_protocol = {'purpose': 'Current-provider observed development control; not qualification',
                    'workers': 1, 'cases': cases,
                    'lanes': [{'id': 'selected-control', 'mode': contract['control']['mode'],
                               'flags': contract['control']['flags']}],
                    'qualification_contract_sha256': sha(HERE / 'protocol-v1.json')}
    source_protocol = args.run / 'declared-control-protocol.json'
    write_json(source_protocol, run_protocol)
    freeze(args.run, source_protocol, classpath)
    verify(args.run)
    source = json.loads((args.run / 'source.json').read_text())
    # Bind analysis/preparation scripts absent from the numerical candidate inventory.
    additional = ['benchmarks/flashalign/ants_same_affine.py',
                  'benchmarks/halfflow/qualification/protocol-v1.json',
                  'benchmarks/halfflow/qualification/contract.py',
                  'benchmarks/halfflow/qualification/test_contract.py',
                  'benchmarks/halfflow/qualification/freeze_control.py']
    files = source['files'] | {name: sha(ROOT / name) for name in additional}
    archive = HERE / 'control-source.tar.gz'
    with archive.open('xb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as gz, tarfile.open(fileobj=gz, mode='w') as tar:
        for name in sorted(files):
            data = (ROOT / name).read_bytes()
            assert hashlib.sha256(data).hexdigest() == files[name]
            info = tarfile.TarInfo(name); info.size = len(data); info.mode = 0o644; info.mtime = 0
            tar.addfile(info, io.BytesIO(data))
    providers = {}
    for path in paths:
        if '/.sbt/' not in path:
            continue
        repo = subprocess.check_output(['git', '-C', path, 'rev-parse', '--show-toplevel'], text=True).strip()
        if repo in providers:
            continue
        dirty = subprocess.check_output(['git', '-C', repo, 'status', '--porcelain', '--untracked-files=no'], text=True)
        assert not dirty, 'dirty provider: ' + repo
        providers[repo] = {'name': Path(repo).name,
                          'commit': subprocess.check_output(['git', '-C', repo, 'rev-parse', 'HEAD'], text=True).strip(),
                          'tracked_worktree_clean': True, 'build_sbt_sha256': sha(Path(repo) / 'build.sbt')}
    build = (ROOT / 'build.sbt').read_text()
    for name in ['ravel', 'gale', 'image4s']:
        revision = re.search(r'lazy val ' + name + r'Revision = "([a-f0-9]+)"', build).group(1)
        assert any(v['name'] == name and v['commit'] == revision for v in providers.values()), name
    # Keep compact evidence in Git; binaries remain in the ignored local run.
    build_out = HERE / 'control-build.log'
    build_out.write_text(text)
    cases_out = HERE / 'control-cases.json'
    portable = lambda s: str(Path(s).relative_to(ROOT))
    write_json(cases_out, [{k: (portable(v) if isinstance(v, str) and v.startswith(str(ROOT) + '/') else v)
                           for k, v in c.items() if k in ['id', 'data', 'saved_affine', 'moving_labels', 'fixed_labels', 'baseline_report']}
                          for c in cases])
    inputs = json.loads((args.run / 'inputs.json').read_text())
    versions = {name: importlib.metadata.version(name) for name in ['numpy', 'scipy', 'nibabel', 'antspyx', 'matplotlib']}
    java = subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip()
    seal = {'schema': 'halfflow-control-seal-v1', 'created_at': datetime.now(timezone.utc).isoformat(),
            'base_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
            'protocol_sha256': sha(HERE / 'protocol-v1.json'), 'source_files': files,
            'source_archive': {'path': portable(archive), 'sha256': sha(archive)},
            'numerical_source_sha256': source['sha256'], 'local_frozen_run': str(args.run.resolve()),
            'compiled_classpath_manifest_sha256': sha(args.run / 'compiled-classpath.json'),
            'input_files': {portable(k): v for k, v in inputs.items()},
            'providers': sorted(providers.values(), key=lambda r: r['name']),
            'hardware': json.loads(args.hardware.read_text()), 'java': java,
            'python': platform.python_version(), 'packages': versions,
            'supporting_artifacts': [{'path': portable(f), 'sha256': sha(f)} for f in [build_out, cases_out]],
            'validation': {'jvm_engine': 15, 'jvm_export': 11, 'jvm_control': 6, 'runner_compile': 'passed',
                           'source_built_with_new_providers': True, 'anatomical_replay': 'not-yet-run',
                           'scientific_qualification': 'not-run'}}
    write_json(seal_path, seal)
    print(json.dumps({'sealed_source_files': len(files), 'sealed_input_files': len(inputs),
                      'source_archive_bytes': archive.stat().st_size, 'providers': seal['providers'],
                      'local_run': str(args.run)}))


if __name__ == '__main__':
    main()
