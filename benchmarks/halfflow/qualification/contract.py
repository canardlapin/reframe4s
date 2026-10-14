#!/usr/bin/env python3
"""Validate the prospective protocol, subject-block design and sealed control.

This checks identity and design, not anatomical quality or qualification success.
"""
import argparse
from collections import defaultdict
import hashlib
import itertools
import json
from pathlib import Path
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_protocol(protocol):
    require(protocol['schema'] == 'halfflow-qualification-protocol-v1', 'protocol version')
    sample, gates = protocol['sample'], protocol['acceptance']
    require(sample['minimum_pairs'] >= 100 and sample['minimum_subjects'] >= 60, 'epic sample floor')
    require(sample['minimum_cohorts'] >= 3, 'epic cohort floor')
    require(sample['minimum_subject_disjoint_blocks'] >= 30, 'independent block floor')
    require(gates['minimum_end_to_end_success_fraction'] >= .95, 'success floor')
    require(gates['dice_noninferiority_margin'] <= .02, 'Dice margin')
    require(gates['hd95_ratio_upper'] <= 1.05, 'HD95 margin')
    g = protocol['geometry']
    require(g['minimum_coverage'] >= .99 and g['minimum_sampled_cell_jacobian'] >= .05, 'geometry floor')
    require(g['maximum_folded_cells'] == 0, 'folds forbidden')
    for key in ['maximum_residual_inverse_mm', 'maximum_residual_inverse_vox', 'maximum_composed_brain_roundtrip_mm']:
        require(0 < g[key] <= .2, 'inverse tolerance: ' + key)
    require(protocol['uncertainty']['replicates'] >= 10000, 'bootstrap replicates')
    require(protocol['control']['scale_accumulated_smoothing_by_step'] is False, 'retained control changed')
    return {'protocol_valid': True, 'qualification_claim': False}


def validate_manifest(manifest, protocol):
    """A row is one unordered pair; exported directions stay within that row.

    Required metadata: cohort id/source identity/label list; pair id/cohort/block/
    moving/fixed/expected_labels. P03 must separately attest source independence
    and raw file hashes; this validator cannot establish those facts from names.
    """
    validate_protocol(protocol)
    require(manifest['protocol_sha256'] == sha(HERE / 'protocol-v1.json'), 'protocol seal differs')
    sample = protocol['sample']
    cohorts = {c['id']: c for c in manifest['cohorts']}
    require(len(cohorts) == len(manifest['cohorts']), 'duplicate cohort')
    require(len(cohorts) >= sample['minimum_cohorts'], 'too few cohorts')
    require(len({c['source_identity'] for c in cohorts.values()}) == len(cohorts), 'shared acquisition source requires review')
    observed = set(manifest['observed_subjects'])
    subjects, blocks, edges, pair_ids = {}, defaultdict(set), defaultdict(set), set()
    for row in manifest['pairs']:
        require(row['id'] not in pair_ids, 'duplicate pair id')
        pair_ids.add(row['id'])
        cohort = row['cohort']
        require(cohort in cohorts, 'unknown cohort')
        block = (cohort, row['block'])
        moving, fixed = row['moving'], row['fixed']
        require(moving < fixed, 'pair direction must be lexical and distinct')
        for subject in [moving, fixed]:
            require(subject not in observed, 'observed subject leaked into qualification')
            require(subject not in subjects or subjects[subject] == block, 'subject crosses blocks or cohorts')
            subjects[subject] = block
            blocks[block].add(subject)
        edge = (moving, fixed)
        require(edge not in edges[block], 'duplicate unordered pair')
        edges[block].add(edge)
        declared = set(cohorts[cohort]['labels'])
        expected = set(row['expected_labels'])
        require(0 not in declared and len(declared) > 0, 'invalid cohort labels')
        require(len(expected) == len(row['expected_labels']) and expected <= declared, 'invalid expected labels')
        require(len(expected) / len(declared) >= .9, 'insufficient annotation coverage')
    require(len(pair_ids) >= sample['minimum_pairs'], 'too few pairs')
    require(len(subjects) >= sample['minimum_subjects'], 'too few distinct subjects')
    require(len(blocks) >= sample['minimum_subject_disjoint_blocks'], 'too few independent blocks')
    lower, upper = sample['subjects_per_block']
    for block, members in blocks.items():
        require(lower <= len(members) <= upper, 'invalid block size')
        require(edges[block] == set(itertools.combinations(sorted(members), 2)), 'incomplete within-block pairing')
    for cohort in cohorts:
        require(sum(k[0] == cohort for k in blocks) >= sample['minimum_blocks_per_cohort'], 'too few cohort blocks')
    return {'pairs': len(pair_ids), 'subjects': len(subjects), 'blocks': len(blocks),
            'cohorts': len(cohorts), 'design_valid': True, 'source_independence_verified': False}


def verify_control(path, local=False):
    seal = json.loads(Path(path).read_text())
    require(sha(HERE / 'protocol-v1.json') == seal['protocol_sha256'], 'protocol identity changed')
    archive = ROOT / seal['source_archive']['path']
    require(sha(archive) == seal['source_archive']['sha256'], 'source archive identity changed')
    expected = seal['source_files']
    with tarfile.open(archive, 'r:gz') as tar:
        files = {m.name: m for m in tar.getmembers() if m.isfile()}
        require(set(files) == set(expected), 'source archive member set differs')
        for name, digest in expected.items():
            p = Path(name)
            require(not p.is_absolute() and '..' not in p.parts, 'unsafe source member')
            with tar.extractfile(files[name]) as stream:
                require(hashlib.sha256(stream.read()).hexdigest() == digest, 'archived source differs: ' + name)
    for record in seal['supporting_artifacts']:
        require(sha(ROOT / record['path']) == record['sha256'], 'artifact changed: ' + record['path'])
    if local:
        for name, digest in seal['input_files'].items():
            require(sha(ROOT / name) == digest, 'control input changed: ' + name)
        frozen = Path(seal['local_frozen_run'])
        classes = json.loads((frozen / 'compiled-classpath.json').read_text())
        require(sha(frozen / 'compiled-classpath.json') == seal['compiled_classpath_manifest_sha256'], 'classpath manifest changed')
        for name, digest in classes.items():
            require(sha(name) == digest, 'compiled dependency changed: ' + name)
    return {'verified_source_files': len(expected), 'verified_input_files': len(seal['input_files']) if local else 0,
            'verified_classpath_files': len(classes) if local else 0, 'qualification_claim': False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['protocol', 'manifest', 'control'])
    p.add_argument('--path', type=Path)
    p.add_argument('--local', action='store_true')
    args = p.parse_args()
    protocol = json.loads((HERE / 'protocol-v1.json').read_text())
    if args.command == 'protocol':
        result = validate_protocol(protocol)
    elif args.command == 'manifest':
        require(args.path is not None, 'manifest path required')
        result = validate_manifest(json.loads(args.path.read_text()), protocol)
    else:
        result = verify_control(args.path or HERE / 'control-seal.json', args.local)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
