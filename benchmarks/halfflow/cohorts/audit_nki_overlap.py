#!/usr/bin/env python3
"""Audit public NKI pseudonymous IDs without opening images or phenotypes."""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[3]
ARCHIVE_SHA256 = '1904c43d15ec72cf85d85082984fc9ba36dc85e497c31657f093e5891b180f98'
MEMBERS = ['docs/subjects/mindboggle101_sources.txt',
           'docs/subjects/subject_sources_Mindboggle101.txt']
SUBJECT = re.compile(r'NKI-(?:RS-22|TRT-20)-[1-9][0-9]*')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalize(value):
    value = value.strip().removeprefix('NKI_Rockland_')
    if not re.fullmatch(r'[0-9]{1,7}', value):
        raise ValueError('Unexpected NKI source ID')
    return value.zfill(7)


def parse_mapping(text, delimiter, column):
    result = {}
    for fields in csv.reader(io.StringIO(text), delimiter=delimiter):
        if not fields or not SUBJECT.fullmatch(fields[0].strip()):
            continue
        subject = fields[0].strip()
        if subject in result:
            raise ValueError('Duplicate subject mapping: ' + subject)
        if len(fields) <= column:
            raise ValueError('Missing source ID')
        result[subject] = normalize(fields[column])
    return result


def compare(primary, secondary, observed):
    expected = {f'NKI-RS-22-{i}' for i in range(1, 23)} | {f'NKI-TRT-20-{i}' for i in range(1, 21)}
    if set(primary) != expected or set(secondary) != expected:
        raise ValueError('Incomplete published NKI mapping')
    if primary != secondary:
        raise ValueError('Published mapping tables disagree')
    rs = {key: value for key, value in primary.items() if key.startswith('NKI-RS-22-')}
    trt = {key: value for key, value in primary.items() if key.startswith('NKI-TRT-20-')}
    if set(observed) != set(rs):
        raise ValueError('Observed inventory does not account for all 22 NKI-RS subjects')
    if len(set(rs.values())) != len(rs) or len(set(trt.values())) != len(trt):
        raise ValueError('Within-cohort duplicate source ID')
    reverse = {value: key for key, value in rs.items()}
    overlap = [dict(candidate=key, observed=reverse[value], source_id=value)
               for key, value in sorted(trt.items()) if value in reverse]
    remaining = sorted(key for key, value in trt.items() if value not in reverse)
    return dict(observed_subjects=sorted(observed), published_nki_mappings=primary,
                overlaps=overlap, overlap_count=len(overlap),
                candidates_without_this_known_overlap=remaining,
                remaining_candidate_count=len(remaining),
                participant_freshness_established=False,
                independent_cohort_established=False, qualification_manifest_sealed=False)


def audit(archive, output):
    if output.exists():
        raise FileExistsError(output)
    if sha(archive) != ARCHIVE_SHA256:
        raise ValueError('Documentation archive identity changed')
    with zipfile.ZipFile(archive) as source:
        if any(source.namelist().count(name) != 1 for name in MEMBERS):
            raise ValueError('Missing or duplicate metadata member')
        contents = {name: source.read(name) for name in MEMBERS}
    primary = parse_mapping(contents[MEMBERS[0]].decode('utf-8-sig'), '\t', 1)
    secondary = parse_mapping(contents[MEMBERS[1]].decode('utf-8-sig'), ',', 4)
    validation = ROOT/'docs/benchmarks/evidence/real-mri-calibration-20261011/validation-selection.json'
    acquired = validation.parent/'validation/input-manifest.json'
    confirmation = ROOT/'docs/benchmarks/evidence/real-mri-recovery-20261011/confirmation-protocol.json'
    v, a, c = [json.loads(path.read_text()) for path in [validation, acquired, confirmation]]
    if a['selection_sha256'] != sha(validation):
        raise ValueError('Observed selection differs from acquired manifest')
    observed = {pair[role] for pair in v['pairs'] if pair['cohort'] == 'NKI-RS-22'
                for role in ['moving', 'fixed']}
    for subject in observed:
        if f'inputs/{subject}/t1weighted_brain.nii.gz' not in a['files']:
            raise ValueError('Observed subject missing from acquired manifest')
    for case in c['cases']:
        for role in ['moving_labels', 'fixed_labels']:
            subject = Path(case[role]).parent.name
            if subject.startswith('NKI-RS-22-'):
                observed.add(subject)
    result = compare(primary, secondary, observed)
    result.update(schema='hfq-nki-subject-overlap-audit-v1',
        created_at=datetime.now(timezone.utc).isoformat(),
        source_url='https://zenodo.org/records/22070005',
        download_url='https://zenodo.org/records/22070005/files/docs.zip?download=1',
        archive_sha256=ARCHIVE_SHA256, archive_bytes=archive.stat().st_size,
        inspected_members={name: dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                           for name, data in contents.items()},
        historical_inputs={str(path.relative_to(ROOT)): sha(path) for path in [validation, acquired, confirmation]},
        normalization='Remove only NKI_Rockland_ prefix; preserve numeric ID as seven digits. Compare both publisher mapping tables.',
        scope='Public pseudonymous ID metadata only; no MRI, labels or phenotype columns opened by this audit.',
        exclusions='Both overlapping TRT subjects are ineligible for fresh qualification; remaining IDs require the full provenance/access audit.',
        source_sha256=sha(__file__))
    output.mkdir(parents=True)
    shutil.copyfile(__file__, output/Path(__file__).name)
    with (output/'overlap-audit.json').open('x') as stream:
        json.dump(result, stream, indent=2); stream.write('\n')
    print(json.dumps({key: result[key] for key in ['overlaps', 'remaining_candidate_count',
                     'participant_freshness_established', 'independent_cohort_established']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    audit(args.archive.resolve(), args.output.resolve())
