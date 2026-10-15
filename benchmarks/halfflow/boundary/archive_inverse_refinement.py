#!/usr/bin/env python3
"""Retain declarations and scalar inverse-study evidence; dense fields stay local."""
import argparse
from datetime import datetime, timezone
import gzip
from pathlib import Path
import shutil

from analyze_c04 import read, sha, write


def archive(study, output, phase):
    if output.exists():
        raise FileExistsError(output)
    p = read(study / 'protocol.json')
    for path, digest in {**p['sources'], **p['inputs']}.items():
        if sha(path) != digest:
            raise ValueError('Frozen source/input changed: ' + path)
    if phase == 'outcomes' and not (study / 'completion.json').exists():
        raise ValueError('Terminal attempt required')
    files = {study / 'protocol.json': 'protocol.json'}
    for path in sorted(p['sources']):
        source = Path(path)
        files[source] = 'sources/' + source.name
    payloads = []
    for number, path in enumerate(sorted(p['inputs'])):
        source = Path(path)
        if source.suffix in ['.json', '.log']:
            files[source] = f'inputs/{number:02d}-' + source.name
        else:
            payloads.append(dict(path=path, sha256=p['inputs'][path], bytes=source.stat().st_size,
                                 retained_locally=True))
    for path in study.glob('*.json'):
        if phase == 'outcomes' or path.name == 'workload-precheck.json':
            files[path] = path.name
    if phase == 'outcomes':
        files[study / 'execution.log'] = 'execution.log'
        confirmation = study / 'independent-interpolation-verification.json'
        if confirmation.exists():
            verifier = Path(__file__).with_name('verify_inverse_refinement.py').resolve()
            if sha(verifier) != read(confirmation)['source_sha256']:
                raise ValueError('Independent confirmation source changed')
            files[verifier] = 'sources/' + verifier.name
        for path in study.glob('*-factor*/result.json'):
            files[path] = str(path.relative_to(study))
        for path in study.glob('*-factor*/*.npy'):
            payloads.append(dict(path=str(path), sha256=sha(path), bytes=path.stat().st_size,
                                 retained_locally=True))
    files[Path(__file__).resolve()] = 'sources/' + Path(__file__).name
    output.mkdir(parents=True)
    inventory = []
    for source, name in sorted(files.items()):
        target = output / name
        if source.suffix == '.log':
            target = target.with_name(target.name + '.gz')
        target.parent.mkdir(parents=True, exist_ok=True)
        original = source.read_bytes()
        if source.suffix == '.log':
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, filename='', mtime=0, mode='wb') as compressed:
                compressed.write(original)
            if gzip.decompress(target.read_bytes()) != original:
                raise ValueError('Compressed bytes differ')
        else:
            shutil.copyfile(source, target)
            if target.read_bytes() != original:
                raise ValueError('Archived bytes differ')
        inventory.append(dict(path=str(target.relative_to(output)), sha256=sha(target),
                              original_sha256=sha(source), bytes=target.stat().st_size))
    write(output / 'artifact-manifest.json', dict(schema='hfq-inverse-refinement-archive-v1', phase=phase,
          created_at=datetime.now(timezone.utc).isoformat(), protocol_sha256=sha(study / 'protocol.json'),
          files=inventory, local_payloads=payloads, mri_or_dense_map_payloads=False,
          qualification_claim=False, runtime_comparison_allowed=False))
    print('ARCHIVED', phase, len(inventory), 'compact files;', len(payloads), 'identified local payloads')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['declaration', 'outcomes'], required=True)
    args = parser.parse_args()
    archive(args.study.resolve(), args.output.resolve(), args.phase)
