#!/usr/bin/env python3
"""Opt-in, hash-pinned retrieval and migration of existing Reframe4s datasets."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import tarfile
import tempfile
import urllib.request
import zlib

ROOT = Path(__file__).resolve().parents[1]
CHUNK = 1024 * 1024


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def relative(value):
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or str(path) in ("", ".") or "\\" in value or "\0" in value:
        raise ValueError(f"unsafe dataset path: {value}")
    return str(path)


def catalog():
    document = json.loads((ROOT / "tools/datasets.json").read_text())
    if document["schema_version"] != 1:
        raise ValueError("unsupported dataset catalog schema")
    rows = document["datasets"]
    if len({row["id"] for row in rows}) != len(rows):
        raise ValueError("duplicate dataset identifiers")
    for row in rows:
        if row["kind"] not in ("oasis", "files"):
            raise ValueError("unsupported dataset kind")
        if relative(row["id"]) != row["id"] or "/" in row["id"]:
            raise ValueError("dataset identifier must be a single path component")
        for file in row.get("files", []):
            relative(file["path"])
    return {row["id"]: row for row in rows}


def oasis_metadata(row):
    acquisition = json.loads((ROOT / relative(row["acquisition"])).read_text())
    selection = json.loads((ROOT / relative(row["selection"])).read_text())
    return acquisition, selection


def expected_files(row):
    if row["kind"] == "oasis":
        _, selection = oasis_metadata(row)
        files = [{"path": relative(p), "sha256": h, **({"bytes": row["selected_file_bytes"][p]} if "selected_file_bytes" in row else {})}
                 for p, h in selection["files"].items()]
        for target, source in (("acquisition.json", "acquisition"), ("selection.json", "selection"), ("LICENSE", "license_file")):
            files.append({"path": target, "sha256": sha(ROOT / relative(row[source]))})
        return files
    return row["files"]


def verify(row, directory):
    directory = Path(directory)
    missing, invalid = [], []
    for file in expected_files(row):
        path = directory / relative(file["path"])
        if not path.is_file() or path.is_symlink():
            missing.append(file["path"])
        elif ("bytes" in file and path.stat().st_size != file["bytes"]) or sha(path) != file["sha256"]:
            invalid.append(file["path"])
    return {"id": row["id"], "directory": str(directory), "ready": not missing and not invalid,
            "missing": missing, "invalid": invalid}


def plan(row, root):
    acquisition = oasis_metadata(row)[0] if row["kind"] == "oasis" else None
    network = acquisition["http_range"][1] - acquisition["http_range"][0] + 1 if acquisition else 0
    return {"id": row["id"], "description": row["description"], "directory": str(Path(root) / row["id"]),
            "fetch_available": acquisition is not None, "download_bytes": network,
            "archive_bytes": acquisition["bytes"] if acquisition else 0,
            "known_file_bytes": sum(f.get("bytes", 0) for f in expected_files(row)) or None,
            "license": row["license"], "source": row["source"],
            "download_unavailable": row.get("download_unavailable"), "usage": row["usage"],
            "note": "Archive storage is additional to extracted files. Plan/verify do not download; cache reuse and import use zero network bytes."}


def read_exact(response, count):
    data = response.read(count)
    if len(data) != count:
        raise RuntimeError("truncated ZIP entry header")
    return data


def download_archive(acquisition, target, opener=None):
    """Fetch only the frozen ZIP entry range; never fall back to the full archive."""
    opener = opener or urllib.request.urlopen
    target = Path(target)
    if target.is_file() and target.stat().st_size == acquisition["bytes"] and sha(target) == acquisition["sha256"]:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    start, end = acquisition["http_range"]
    request = urllib.request.Request(acquisition["archive_url"], headers={"Range": f"bytes={start}-{end}"})
    descriptor, temporary = tempfile.mkstemp(prefix=target.name + ".", suffix=".partial", dir=target.parent)
    os.close(descriptor)
    partial = Path(temporary)
    try:
        with opener(request, timeout=120) as response, partial.open("wb") as stream:
            content_range = response.headers.get("Content-Range", "")
            if response.status != 206 or not content_range.startswith(f"bytes {start}-{end}/"):
                raise RuntimeError("publisher did not honor the pinned byte range; full download refused")
            header = read_exact(response, 30)
            if header[:4] != b"PK\x03\x04" or struct.unpack_from("<H", header, 8)[0] != 8:
                raise RuntimeError("unexpected ZIP entry header or compression")
            if struct.unpack_from("<H", header, 6)[0] & 1:
                raise RuntimeError("encrypted ZIP entries are not supported")
            name_size, extra_size = struct.unpack_from("<HH", header, 26)
            name = read_exact(response, name_size).decode()
            read_exact(response, extra_size)
            if name != acquisition["entry"] or 30 + name_size + extra_size + acquisition["compressed_bytes"] != end - start + 1:
                raise RuntimeError("archive entry identity or size differs from the pinned receipt")
            decoder = zlib.decompressobj(-15)
            remaining, written = acquisition["compressed_bytes"], 0
            while remaining:
                chunk = response.read(min(CHUNK, remaining))
                if not chunk:
                    raise RuntimeError("truncated compressed archive entry")
                remaining -= len(chunk)
                while chunk:
                    values = decoder.decompress(chunk, min(CHUNK, acquisition["bytes"] - written + 1))
                    written += len(values)
                    if written > acquisition["bytes"]:
                        raise RuntimeError("archive entry exceeds its pinned size")
                    stream.write(values)
                    chunk = decoder.unconsumed_tail
            if not decoder.eof or decoder.unused_data or written != acquisition["bytes"]:
                raise RuntimeError("incomplete or incorrectly sized compressed archive entry")
        if sha(partial) != acquisition["sha256"]:
            raise RuntimeError("archive SHA-256 differs from the pinned receipt")
        partial.replace(target)
        return target
    finally:
        partial.unlink(missing_ok=True)


def materialize_oasis(row, archive, output):
    # Optional image dependencies are loaded only when creating the frozen masks.
    try:
        import nibabel as nib
        import numpy as np
    except ImportError as error:
        raise RuntimeError("OASIS fetch needs numpy and nibabel; see docs/datasets.md. Import of an existing cache needs neither.") from error
    acquisition, selection = oasis_metadata(row)
    archive, output = Path(archive), Path(output)
    if archive.stat().st_size != acquisition["bytes"] or sha(archive) != acquisition["sha256"]:
        raise RuntimeError("archive SHA-256 or size differs from the pinned receipt")
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for subject in selection["subjects"]:
            relative(subject)
            for name in ("t1weighted_brain.nii.gz", "labels.DKT31.manual.nii.gz"):
                suffix = subject + "/" + name
                matches = [m for m in members if m.isfile() and (m.name == suffix or m.name.endswith("/" + suffix))]
                if len(matches) != 1:
                    raise RuntimeError(f"expected exactly one processed member {suffix}")
                if "selected_file_bytes" in row and matches[0].size != row["selected_file_bytes"]["selected/" + suffix]:
                    raise RuntimeError(f"processed image size mismatch: {suffix}")
                path = output / "selected" / suffix
                path.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(matches[0]) as source, path.open("xb") as target:
                    shutil.copyfileobj(source, target, CHUNK)
                if sha(path) != selection["files"]["selected/" + suffix]:
                    raise RuntimeError(f"processed image hash mismatch: {suffix}")
            image = nib.load(output / "selected" / subject / "t1weighted_brain.nii.gz")
            mask = nib.Nifti1Image(np.asarray(image.get_fdata() > 0, dtype=np.uint8), image.affine)
            mask.set_sform(image.affine, code=1)
            mask.set_qform(image.affine, code=1)
            nib.save(mask, output / "selected" / subject / "positive-intensity-mask.nii.gz")
    for target, source in (("acquisition.json", "acquisition"), ("selection.json", "selection"), ("LICENSE", "license_file")):
        shutil.copy2(ROOT / row[source], output / target)


def publish(row, staged, destination):
    result = verify(row, staged)
    if not result["ready"]:
        raise RuntimeError(f"dataset verification failed: {result}")
    destination = Path(destination)
    if destination.is_symlink():
        raise RuntimeError("dataset destination is a symbolic link; select its containing cache with --root instead")
    # Replace only this profile's known files; preserve unrelated user files.
    for file in expected_files(row):
        target = destination / relative(file["path"])
        for parent in target.parents:
            if parent == destination.parent:
                break
            if parent.is_symlink():
                raise RuntimeError(f"dataset destination directory is a symbolic link: {parent}")
        target.parent.mkdir(parents=True, exist_ok=True)
        (Path(staged) / file["path"]).replace(target)
    receipt = {"id": row["id"], "source": row["source"], "license": row["license"],
               "files": expected_files(row), "usage": row["usage"]}
    (destination / "dataset-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return verify(row, destination)


def fetch(row, root, archive=None, max_download_mib=None):
    root = Path(root)
    destination = root / row["id"]
    ready = verify(row, destination)
    if ready["ready"]:
        return ready
    if row["kind"] != "oasis":
        raise RuntimeError(row["download_unavailable"] + " Use the import command.")
    acquisition, _ = oasis_metadata(row)
    cached = Path(archive) if archive else root / ".archives" / (acquisition["sha256"] + ".tar.gz")
    if archive and (not cached.is_file() or cached.stat().st_size != acquisition["bytes"] or sha(cached) != acquisition["sha256"]):
        raise RuntimeError("supplied archive SHA-256 or size differs from the pinned receipt")
    reusable = cached.is_file() and cached.stat().st_size == acquisition["bytes"] and sha(cached) == acquisition["sha256"]
    network_bytes = 0 if reusable else plan(row, root)["download_bytes"]
    if max_download_mib is not None and (not math.isfinite(max_download_mib) or max_download_mib < 0 or network_bytes > max_download_mib * CHUNK):
        raise RuntimeError(f"planned download ({network_bytes} bytes) exceeds the --max-download-mib budget")
    # Check optional dependencies before allowing a large network request.
    try:
        import nibabel
        import numpy
    except ImportError as error:
        raise RuntimeError("OASIS fetch needs numpy and nibabel; see docs/datasets.md.") from error
    if not reusable:
        download_archive(acquisition, cached)
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".dataset-stage-", dir=root) as staged:
        materialize_oasis(row, cached, staged)
        return publish(row, staged, destination)


def import_data(row, root, source):
    root, source = Path(root), Path(source)
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".dataset-stage-", dir=root) as temporary:
        staged = Path(temporary)
        if source.is_dir():
            valid = verify(row, source)
            if not valid["ready"]:
                raise RuntimeError(f"source dataset verification failed: {valid}")
            for file in expected_files(row):
                target = staged / relative(file["path"])
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source / file["path"], target)
        else:
            with tarfile.open(source, "r:gz") as tar:
                for file in expected_files(row):
                    name = row["id"] + "/" + relative(file["path"])
                    matches = [m for m in tar.getmembers() if m.name == name]
                    if len(matches) != 1 or not matches[0].isfile():
                        raise RuntimeError(f"expected exactly one regular bundle member: {name}")
                    if "bytes" in file and matches[0].size != file["bytes"]:
                        raise RuntimeError(f"bundle member size mismatch: {name}")
                    target = staged / file["path"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(matches[0]) as stream, target.open("xb") as out:
                        shutil.copyfileobj(stream, out, CHUNK)
        return publish(row, staged, root / row["id"])


def export_data(row, root, output):
    source, output = Path(root) / row["id"], Path(output)
    result = verify(row, source)
    if not result["ready"]:
        raise RuntimeError(f"cannot export unverified dataset: {result}")
    if output.exists():
        raise RuntimeError("export output already exists; choose a new filename")
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=output.name + ".", suffix=".partial", dir=output.parent)
    os.close(descriptor)
    try:
        with tarfile.open(temporary, "w:gz") as tar:
            for file in expected_files(row):
                tar.add(source / file["path"], arcname=row["id"] + "/" + file["path"], recursive=False)
        Path(temporary).replace(output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return {"id": row["id"], "output": str(output), "bytes": output.stat().st_size, "sha256": sha(output)}


def fetch_oasis_legacy(output, archive=None):
    """Keep the original real_image_court.py fetch output layout and fresh-output rule."""
    output = Path(output)
    if output.exists():
        raise FileExistsError(f"output already exists: {output}")
    row = catalog()["oasis-development"]
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".reframe4s-oasis-", dir=output.parent) as temporary:
        result = fetch(row, temporary, archive=archive)
        if not archive:
            acquisition, _ = oasis_metadata(row)
            cached = Path(temporary) / ".archives" / (acquisition["sha256"] + ".tar.gz")
            cached.replace(Path(result["directory"]) / "OASIS-TRT-20_volumes.tar.gz")
        Path(result["directory"]).replace(output)
    print(f"Verified four processed subjects and manual cortical labels in {output / 'selected'}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    default_root = os.environ.get("REFRAME4S_DATA_ROOT", str(ROOT / ".datasets"))
    commands.add_parser("list", help="list existing datasets and download sizes; no network").add_argument("--root", default=default_root)
    for name in ("plan", "verify", "fetch", "import", "export"):
        command = commands.add_parser(name)
        command.add_argument("dataset", choices=sorted(catalog()))
        command.add_argument("--root", default=default_root)
        if name == "fetch":
            command.add_argument("--archive", help="reuse the pinned OASIS archive without a download")
            command.add_argument("--max-download-mib", type=float, help="cap network bytes; 0 permits only cache reuse")
        if name == "import":
            command.add_argument("--source", required=True, help="existing source directory or exported .tar.gz")
        if name == "export":
            command.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    rows = catalog()
    if args.command == "list":
        result = [plan(row, Path(args.root).expanduser().resolve()) for row in rows.values()]
    else:
        row, root = rows[args.dataset], Path(args.root).expanduser().resolve()
        if args.command == "plan":
            result = plan(row, root)
        elif args.command == "verify":
            result = verify(row, root / row["id"])
        elif args.command == "fetch":
            result = fetch(row, root, args.archive, args.max_download_mib)
        elif args.command == "import":
            result = import_data(row, root, args.source)
        else:
            result = export_data(row, root, args.output)
    print(json.dumps(result, indent=2))
    return 2 if isinstance(result, dict) and result.get("ready") is False else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, tarfile.TarError, zlib.error) as error:
        raise SystemExit(f"datasets: {error}") from None
