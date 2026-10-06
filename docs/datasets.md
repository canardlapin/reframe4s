# Optional real-data caches and migration

Cloning and running ordinary library tests does not fetch real datasets.
Synthetic fixtures are already in Git. Real inputs are retrieved or imported
only by an explicit command. A machine without a real-data cache can continue
development; optional acquired-data tests report their skips.

The lightweight entry point uses Python 3's standard library for discovery,
verification, import, and export:

```sh
python3 tools/datasets.py list
python3 tools/datasets.py plan oasis-development
python3 tools/datasets.py verify hodgeflow-public-t1
```

`list` and `plan` print sources, licensing status, byte counts, cache locations,
and intended use without contacting a server or creating cache directories.
`verify` hashes every required file, exits 0 when ready and 2 when incomplete or
corrupt, and never downloads. No dependency installation happens automatically.

## Existing inputs

| Dataset ID | Contents | Network retrieval | Transfer size |
| --- | --- | --- | --- |
| `oasis-development` | Frozen four-subject Mindboggle OASIS brain images, cortical labels, and masks | Pinned public ZIP-member range; CC BY 4.0 | About 390 MiB to fetch; about 12 MiB of selected files, plus cached archive |
| `hodgeflow-public-t1` | The exact T1/MNI pair for `BasinBridgeRealDataSuite` | Local import or private bundle; no accessible pinned public source recorded | About 3 MiB |
| `motion-exploratory-fmri` | Two existing Volregger functional runs | Local import or private bundle; provenance/license unknown | About 95 MiB |

The IDs and file identities are in [the catalog](../tools/datasets.json). OASIS
uses the existing acquisition and selection receipts. The other profiles retain
the hashes and roles from their existing diagnostic receipt and frozen motion
protocol. Importing those inputs does not establish missing licensing evidence,
make exploratory data claim-eligible, or fill the unresolved real-anatomy court.
Historical IBSR/sub18 inputs remain subject to their existing licensed-data and
manifest requirements; this tool does not guess a replacement source or cohort.

## Choose storage per machine

The default cache is `.datasets/` at the repository root, ignored by Git.
Use `--root /path/to/cache` on any command or set `REFRAME4S_DATA_ROOT` to keep
data on a disk with enough space. Each dataset gets its own directory, and the
shared OASIS archive cache is under `.archives/`. Paths are resolved on the
current machine; historical paths in receipts are not installation requirements.

```sh
export REFRAME4S_DATA_ROOT=/path/to/large-disk/reframe4s-data
python3 tools/datasets.py plan oasis-development
```

Plan reports network bytes, archive bytes, and known selected-file bytes
separately. Allow room for the archive, extracted files, temporary verified
copies, and any later registration outputs. The complete upstream Mindboggle
release is never downloaded as a fallback when range requests fail.

## Explicit OASIS fetch or offline archive reuse

Creating the frozen OASIS masks requires `numpy==2.3.5` and `nibabel==5.4.2`
from the existing development environment. Install these in an optional
environment on machines that need to fetch OASIS; list/import/verify/export need
neither package. See [the full recipe](benchmarks/real-image-development.md)
for its separate preparation/evaluation dependencies.

```sh
python3 tools/datasets.py fetch oasis-development --max-download-mib 400
# Reuse an existing pinned archive with zero network allowance:
python3 tools/datasets.py fetch oasis-development \
  --archive /path/OASIS-TRT-20_volumes.tar.gz --max-download-mib 0
```

`fetch` is the opt-in; no build, test, or setup step calls it. The optional
`--max-download-mib` budget is checked before any network request. Verified
datasets and archives are reused. Downloads are written to temporary files,
size/hash checked, and then published; interrupted or invalid downloads never
become a valid cache. All selected files and frozen masks are checked against
the existing hashes before publication.

The selected inputs are at `$REFRAME4S_DATA_ROOT/oasis-development/selected`.
The original `real_image_court.py fetch --output ... --archive ...` command
remains supported with its original fresh-output rule and selected-file layout.

## Migrate existing private or local inputs

Import from the original checkout/cache directory without network access:

```sh
python3 tools/datasets.py import hodgeflow-public-t1 --source /path/to/hodgeflow
python3 tools/datasets.py import motion-exploratory-fmri --source /path/to/volregger
python3 tools/datasets.py import oasis-development --source /path/to/existing/inputs
```

Alternatively, make a verified bundle on the machine that already has the data,
transfer it privately, and import it into the other machine's cache:

```sh
python3 tools/datasets.py export hodgeflow-public-t1 --output /path/hodgeflow-t1.tar.gz
# After transferring the file to the other machine:
python3 tools/datasets.py import hodgeflow-public-t1 --source /path/hodgeflow-t1.tar.gz
```

The same export/import commands work for all catalog IDs. Bundles include only
the profile's required files, not caches, credentials, fitted maps, or unrelated
inputs. Import checks hashes and sizes before publishing files, rejects link
members and duplicate required entries, and preserves unrelated user files.
Binary datasets and transfer bundles belong outside Git, subject to their
existing access and redistribution terms.

Run the existing optional T1 court from a verified cache:

```sh
HODGEFLOW_ROOT="$REFRAME4S_DATA_ROOT/hodgeflow-public-t1" \
  sbt -J-Xmx4G -batch 'reframe4s-halfflowJVM/testOnly reframe4s.halfflow.BasinBridgeRealDataSuite'
```

If you use the default cache, set `HODGEFLOW_ROOT="$PWD/.datasets/hodgeflow-public-t1"`
instead. No Hodgeflow checkout is required: the profile retains its expected
`data-raw/` layout and exact fixture hashes.

## Fetcher verification

Offline tests use small synthetic transport responses and bundles:

```sh
python3 -m unittest discover -s tools -p 'test_datasets.py'
```

These tests do not fetch real MRI data. Dataset verification establishes file
identity and portability, not registration accuracy or release qualification.
