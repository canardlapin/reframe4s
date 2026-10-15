#!/usr/bin/env python3
"""Archive a terminal attempt using its immutable declaration's source bytes."""
import argparse
import gzip
from pathlib import Path
import shutil
from analyze_c04 import read, sha, write


def archive(study, declaration, output):
    if output.exists():
        raise FileExistsError(output)
    p = read(study / 'protocol.json')
    if sha(study / 'protocol.json') != sha(declaration / 'protocol.json'):
        raise ValueError('Attempt and declaration differ')
    if not (study / 'completion.json').exists():
        raise ValueError('Terminal attempt required')
    files = {}
    for name, digest in p['sources'].items():
        source = declaration / 'sources' / Path(name).name
        if sha(source) != digest:
            raise ValueError('Declared source archive changed')
        files[source] = 'sources/' + source.name
    payloads = []
    for number, (name, digest) in enumerate(sorted(p['inputs'].items())):
        source = Path(name)
        if sha(source) != digest:
            raise ValueError('Declared input changed')
        if source.suffix in ['.json', '.log']:
            files[source] = f'inputs/{number:03d}-' + source.name
        else:
            payloads.append(dict(path=name, sha256=digest, retained_locally=True))
    files.update({path: path.name for path in study.glob('*.json') if path.name != 'preexisting-files.json'})
    files[study / 'execution.log'] = 'execution.log'
    files[Path(__file__).resolve()] = 'archive-sources/' + Path(__file__).name
    output.mkdir(parents=True)
    inventory = []
    for source, name in sorted(files.items()):
        target = output / name
        if source.suffix == '.log':
            target = target.with_name(target.name + '.gz')
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix == '.log':
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, filename='', mtime=0, mode='wb') as compressed:
                compressed.write(source.read_bytes())
            if gzip.decompress(target.read_bytes()) != source.read_bytes():
                raise ValueError('Compressed bytes differ')
        else:
            shutil.copyfile(source, target)
            if sha(source) != sha(target):
                raise ValueError('Archived bytes differ')
        inventory.append(dict(path=str(target.relative_to(output)), sha256=sha(target), original_sha256=sha(source)))
    write(output / 'artifact-manifest.json', dict(schema='hfq-terminal-query-attempt-v1', files=inventory,
          declaration_manifest_sha256=sha(declaration / 'artifact-manifest.json'),
          frozen_sources_recovered_from_declaration=True, local_payloads=payloads,
          completion=read(study / 'completion.json'), qualification_claim=False, runtime_comparison_allowed=False))
    print('ARCHIVED terminal attempt', len(inventory), 'compact files')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--declaration', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    archive(args.study.resolve(), args.declaration.resolve(), args.output.resolve())
