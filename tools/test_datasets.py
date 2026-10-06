"""Offline transport and migration checks; no real downloads or image packages."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zlib

spec = importlib.util.spec_from_file_location("reframe4s_datasets", Path(__file__).with_name("datasets.py"))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)


class Response(io.BytesIO):
    def __init__(self, payload, acquisition, status=206, content_range=None):
        super().__init__(payload)
        self.status = status
        start, end = acquisition["http_range"]
        self.headers = {"Content-Range": content_range or f"bytes {start}-{end}/9999"}


def zip_entry(values=b"frozen archive bytes", name="data/archive.tar.gz"):
    encoder = zlib.compressobj(wbits=-15)
    compressed = encoder.compress(values) + encoder.flush()
    encoded = name.encode()
    header = struct.pack("<4s5H3I2H", b"PK\x03\x04", 20, 0, 8, 0, 0,
                         zlib.crc32(values), len(compressed), len(values), len(encoded), 0)
    payload = header + encoded + compressed
    acquisition = {"archive_url": "https://example.invalid/archive.zip", "entry": name,
                   "http_range": [100, 100 + len(payload) - 1], "compressed_bytes": len(compressed),
                   "bytes": len(values), "sha256": hashlib.sha256(values).hexdigest()}
    return payload, acquisition


def file_row(values=b"known fixture bytes"):
    return {"id": "fixture", "kind": "files", "description": "synthetic test input", "license": None,
            "source": "synthetic", "download_unavailable": "source unavailable", "usage": "test",
            "files": [{"path": "data/input.bin", "bytes": len(values), "sha256": hashlib.sha256(values).hexdigest()}]}


class DatasetTests(unittest.TestCase):
    def test_exact_range_and_validated_archive_reuse(self):
        payload, acquisition = zip_entry()
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "archive.gz"
            seen = []
            def open_range(request, timeout):
                seen.append(request.get_header("Range"))
                return Response(payload, acquisition)
            d.download_archive(acquisition, target, opener=open_range)
            self.assertEqual(seen, [f"bytes={acquisition['http_range'][0]}-{acquisition['http_range'][1]}"])
            with patch.object(d.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                d.download_archive(acquisition, target)
            self.assertEqual(d.sha(target), acquisition["sha256"])

    def test_bad_responses_never_publish_or_leave_partials(self):
        payload, original = zip_entry()
        variants = [
            (original, payload, 200, None),
            (original, payload, 206, "bytes 0-999/1000"),
            (original, payload[:12], 206, None),
            (original, payload[:-2], 206, None),
            ({**original, "entry": "wrong/name"}, payload, 206, None),
            ({**original, "sha256": "0" * 64}, payload, 206, None),
            ({**original, "bytes": original["bytes"] - 1}, payload, 206, None),
        ]
        for acquisition, data, status, header in variants:
            with self.subTest(acquisition=acquisition), tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp) / "archive.gz"
                with self.assertRaises((RuntimeError, zlib.error)):
                    d.download_archive(acquisition, target, opener=lambda *a, **k: Response(data, acquisition, status, header))
                self.assertFalse(target.exists())
                self.assertEqual(list(Path(tmp).glob("*.partial")), [])

    def test_no_optional_dependencies_or_network_for_discovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "not-created"
            for command in (["list", "--root", str(root)], ["plan", "oasis-development", "--root", str(root)]):
                output = subprocess.check_output([sys.executable, "-S", str(Path(d.__file__)), *command], text=True)
                json.loads(output)
                self.assertFalse(root.exists())
            with patch.object(d.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(d.main(["verify", "hodgeflow-public-t1", "--root", str(root)]), 2)
            self.assertFalse(root.exists())

    def test_budget_checked_before_network_and_optional_dependencies(self):
        row = d.catalog()["oasis-development"]
        for budget in (0, 1, -1, float("nan"), float("inf")):
            with self.subTest(budget=budget), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp) / "not-created"
                with patch.object(d.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                    with self.assertRaisesRegex(RuntimeError, "budget"):
                        d.fetch(row, root, max_download_mib=budget)
                self.assertFalse(root.exists())

    def test_unknown_provenance_never_triggers_download(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(d.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
            with self.assertRaisesRegex(RuntimeError, "source unavailable"):
                d.fetch(file_row(), tmp)

    def test_local_import_bundle_roundtrip_and_existing_cache_reuse(self):
        values, row = b"known fixture bytes", file_row()
        with tempfile.TemporaryDirectory() as tmp, patch.object(d.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
            root = Path(tmp); source = root / "source/data"; source.mkdir(parents=True)
            (source / "input.bin").write_bytes(values)
            self.assertTrue(d.import_data(row, root / "first", source.parent)["ready"])
            bundle = root / "transfer.tar.gz"
            d.export_data(row, root / "first", bundle)
            self.assertTrue(d.import_data(row, root / "second", bundle)["ready"])
            self.assertTrue(d.fetch(row, root / "second", max_download_mib=0)["ready"])
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                d.export_data(row, root / "first", bundle)

    def test_bad_import_is_rejected_before_publication(self):
        row = file_row()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source/data"; source.mkdir(parents=True)
            (source / "input.bin").write_bytes(b"wrong")
            with self.assertRaisesRegex(RuntimeError, "verification failed"):
                d.import_data(row, root / "cache", source.parent)
            self.assertFalse((root / "cache/fixture").exists())
            with self.assertRaisesRegex(RuntimeError, "unverified"):
                d.export_data(row, root / "cache", root / "bad.tar.gz")

    def test_cache_repair_preserves_unrelated_files_and_rejects_symlink_directory(self):
        row = file_row()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source/data"; source.mkdir(parents=True)
            (source / "input.bin").write_bytes(b"known fixture bytes")
            destination = root / "cache/fixture"; destination.mkdir(parents=True)
            note = destination / "user-notes.txt"; note.write_text("keep")
            d.import_data(row, root / "cache", source.parent)
            self.assertEqual(note.read_text(), "keep")
            (destination / "data/input.bin").write_bytes(b"corrupt")
            self.assertFalse(d.verify(row, destination)["ready"])
            d.import_data(row, root / "cache", source.parent)
            self.assertTrue(d.verify(row, destination)["ready"])
            link_root = root / "links"; link_root.mkdir()
            (link_root / "fixture").symlink_to(destination, target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "symbolic link"):
                d.import_data(row, link_root, source.parent)

    def test_bundle_links_duplicates_and_wrong_sizes_are_rejected(self):
        row = file_row()
        for kind in ("link", "duplicate", "size"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); bundle = root / "bad.tar.gz"
                with tarfile.open(bundle, "w:gz") as tar:
                    member = tarfile.TarInfo("fixture/data/input.bin")
                    values = b"known fixture bytes" if kind != "size" else b"wrong"
                    member.size = len(values)
                    if kind == "link":member.type = tarfile.SYMTYPE; member.linkname = "/outside"
                    tar.addfile(member, io.BytesIO(values) if kind != "link" else None)
                    if kind == "duplicate":tar.addfile(copy.copy(member), io.BytesIO(values))
                with self.assertRaises(RuntimeError):
                    d.import_data(row, root / "cache", bundle)
                self.assertFalse((root / "cache/fixture").exists())

    def test_paths_and_catalog_pins(self):
        for path in ("../escape", "/absolute", "a\\b", "", "a\0b"):
            with self.assertRaises(ValueError):d.relative(path)
        rows = d.catalog()
        receipt = json.loads((d.ROOT / rows["hodgeflow-public-t1"]["source"]).read_text())["case"]
        self.assertEqual({f["sha256"] for f in rows["hodgeflow-public-t1"]["files"]}, {receipt["movingSha256"], receipt["fixedSha256"]})
        protocol = json.loads((d.ROOT / "benchmarks/motion/protocol-v2.json").read_text())
        def locate(value):
            if isinstance(value, dict):
                if "exploratoryInputsExcludedFromClaims" in value:return value["exploratoryInputsExcludedFromClaims"]
                for child in value.values():
                    found = locate(child)
                    if found is not None:return found
            return None
        existing = locate(protocol)
        self.assertEqual({(f["sha256"], f["bytes"]) for f in rows["motion-exploratory-fmri"]["files"]}, {(f["sha256"], f["bytes"]) for f in existing})
        acquisition, selection = d.oasis_metadata(rows["oasis-development"])
        self.assertEqual(d.plan(rows["oasis-development"], "/cache")["download_bytes"], acquisition["http_range"][1] - acquisition["http_range"][0] + 1)
        self.assertEqual(set(rows["oasis-development"]["selected_file_bytes"]), set(selection["files"]))


if __name__ == "__main__":
    unittest.main()
