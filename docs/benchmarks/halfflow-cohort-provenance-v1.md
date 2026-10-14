# HalfFlow qualification cohort acquisition and provenance

The qualification manifest remains unsealed. LPBA40 and Hammers30 access is
pending, and the public NKI metadata reveals two participants already exposed
in development. This is an acquisition and identity audit; no fresh registration
outcomes were inspected.

## Official acquisition routes

LPBA40 is available through the [LONI atlas download page](https://loni.usc.edu/research/atlas_downloads).
The acquisition targets are individual delineation-space MRI and labels, plus
native-space MRI and masks. LONI's [atlas license](https://resource.loni.usc.edu/resources/atlases/license-agreement/)
allows noncommercial research, specifies nonclinical IRB-approved research use,
and restricts external redistribution. Eligibility and authorization to accept
the terms have been requested; no agreement has been accepted or form submitted.

[Hammers30's official page](https://brain-development.org/brain-atlases/adult-brain-atlases/individual-adult-brain-atlases-30/)
provides individual MRI and manual atlases for academic noncommercial research.
Its terms exclude commercially sponsored research and restrict redistribution.
The registration requires initials, surname, position, institution, country and
email. Those details and terms authorization remain pending. Personal registration
details belong outside the repository and Mote journal.

The [Mindboggle publisher record](https://zenodo.org/records/22070005) provides a
20,140,530-byte documentation archive. It was downloaded and verified against
the publisher's MD5 `67aed2e5ae669a920e99a144165a3c17`; its SHA-256 is
`1904c43d15ec72cf85d85082984fc9ba36dc85e497c31657f093e5891b180f98`.
The record states CC BY 4.0, while the legacy subject table carries an older
BY-NC-SA notice. The record's [license file](https://zenodo.org/records/22070005/files/LICENSE?download=1)
separately retains originating-project terms for raw source MRI. The
[original NKI test-retest project](https://fcon_1000.projects.nitrc.org/indi/pro/eNKI_RS_TRT/FrontPage.html)
links an attribution/noncommercial usage agreement and describes participants
drawn from the initial NKI-RS project. Do not treat the record's license as blanket
authorization for every source image. Only the documentation archive was
downloaded during this audit.

## Participant overlap

The audit compares the two publisher mappings inside `docs.zip`:
`docs/subjects/mindboggle101_sources.txt` and
`docs/subjects/subject_sources_Mindboggle101.txt`. It removes the explicit
`NKI_Rockland_` prefix and represents numeric source IDs with seven digits,
preserving leading zeros. Both tables agree for all 42 NKI entries. The earlier
validation selection/input manifest accounts for 20 observed NKI-RS subjects;
the recovery confirmation protocol accounts for subjects 8 and 9, giving all 22.

| Candidate | Already observed | Published source ID | Decision |
| --- | --- | --- | --- |
| NKI-TRT-20-5 | NKI-RS-22-3 | 1427581 | Exclude from fresh qualification |
| NKI-TRT-20-14 | NKI-RS-22-14 | 3808535 | Exclude from fresh qualification |

The [machine-readable audit](evidence/halfflow-cohort-provenance-20261014/nki-overlap-v1/overlap-audit.json)
retains all mappings, exclusions, the remaining 18 candidate identifiers, and
hashes of the inspected members and historical manifests. Those 18 lack this
known overlap; the audit does not establish complete participant freshness or
an independently sourced qualification cohort. The audit helper reads only the
two mapping members and discards demographic columns. It opens no MRI, labels
or phenotype spreadsheets.

## Revised feasibility arithmetic

Subject to access, full identity/provenance checks and adequate annotations,
the following layout meets the unchanged P01 count rules:

| Candidate cohort | Subjects | Blocks | Unordered pairs, each evaluated both ways |
| --- | ---: | --- | ---: |
| LPBA40 | 40 | 10 blocks of 4 | 60 |
| Hammers30 | 30 | 4 blocks of 4 and 7 blocks of 2 | 31 |
| NKI-TRT after the two exclusions | 18 | 9 blocks of 2 | 9 |
| Total | 88 | 30 | 100 |

This is feasibility arithmetic, not a selected pair manifest. It leaves no
margin for another exclusion and no reserved replacement cohort. Subject
identity across all exposed data, acquisition/site and annotation independence,
preprocessing, evaluation-only labels, available landmarks and a replacement
confirmation strategy remain required before sealing the manifest. NKI-RS and
NKI-TRT cannot be counted as two independent cohorts merely because they have
different release names.

## Reproduction

Run the metadata audit against the exact downloaded documentation archive:

```sh
python3 benchmarks/halfflow/cohorts/audit_nki_overlap.py \
  --archive /path/to/mindboggle-docs.zip --output /path/to/new-audit
python3 -m unittest discover -s benchmarks/halfflow/cohorts -p 'test_nki_overlap.py'
```

Four tests pass: leading-zero/prefix normalization; cross-table overlap without
a freshness claim; incomplete/disagreeing mappings or exposure inventories;
and duplicate mappings/participants. The [evidence manifest](evidence/halfflow-cohort-provenance-20261014/nki-overlap-v1/artifact-manifest.json)
records source and artifact hashes. MRI and full documentation/phenotype
payloads remain outside Git. HFQ-P03 stays open.
