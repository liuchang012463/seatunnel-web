#!/usr/bin/env python3
import hashlib
import struct
import tempfile
import unittest
from pathlib import Path

from validate_bundle import BundleError, validate_bundle


def _write_elf(path: Path, machine: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4:7] = bytes((2, 1, 1))
    struct.pack_into("<H", header, 18, machine)
    struct.pack_into("<I", header, 20, 1)
    struct.pack_into("<H", header, 52, 64)
    path.write_bytes(header)
    path.chmod(0o755)


def _write_checksums(root: Path) -> None:
    paths = sorted(
        path for path in root.rglob("*")
        if path.is_file() and path != root / "SHA256SUMS"
    )
    lines = []
    for path in paths:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {path.relative_to(root).as_posix()}")
    (root / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _make_bundle(root: Path, machine: int = 183) -> None:
    (root / "manifest.properties").write_text(
        "format_version=1\n"
        "platform=linux/arm64\n"
        "architecture=aarch64\n"
        "dm_version=DM V8.1.3.140\n"
        "dm_source=vendor-package:DM-ARM64-2026-01\n"
        "kingbase_version=V008R006C009B0014\n"
        "kingbase_source=controlled-artifact:kingbase-v8-arm64-14\n",
        encoding="utf-8",
    )
    _write_elf(root / "dm/bin/dimp", machine)
    _write_elf(root / "kingbase/bin/sys_restore", machine)
    (root / "dm/lib").mkdir(parents=True)
    (root / "kingbase/lib").mkdir(parents=True)
    (root / "dm/lib/libfixture.so").write_bytes(b"fixture library")
    (root / "dm/bin/libfixture.so").symlink_to("../lib/libfixture.so")
    _write_checksums(root)


class BundleValidationTest(unittest.TestCase):
    def test_accepts_complete_arm64_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            manifest = validate_bundle(root)
            self.assertEqual("linux/arm64", manifest["platform"])

    def test_rejects_missing_checksum_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            sums = root / "SHA256SUMS"
            sums.write_text("\n".join(sums.read_text().splitlines()[:-1]) + "\n")
            with self.assertRaisesRegex(BundleError, "does not exactly cover"):
                validate_bundle(root)

    def test_requires_checksum_entry_for_relative_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            sums = root / "SHA256SUMS"
            lines = [line for line in sums.read_text().splitlines() if not line.endswith("  dm/bin/libfixture.so")]
            sums.write_text("\n".join(lines) + "\n")
            with self.assertRaisesRegex(BundleError, "does not exactly cover"):
                validate_bundle(root)

    def test_requires_checksum_for_nested_sha256sums_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            nested_sums = root / "dm/bin/SHA256SUMS"
            nested_sums.write_text("nested checksum metadata\n", encoding="utf-8")
            _write_checksums(root)
            sums = root / "SHA256SUMS"
            lines = [
                line for line in sums.read_text().splitlines()
                if not line.endswith("  dm/bin/SHA256SUMS")
            ]
            sums.write_text("\n".join(lines) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(BundleError, "does not exactly cover"):
                validate_bundle(root)

    def test_rejects_tampered_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            (root / "dm/bin/dimp").write_bytes(b"changed")
            with self.assertRaisesRegex(BundleError, "checksum mismatch"):
                validate_bundle(root)

    def test_rejects_missing_required_tool(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            (root / "kingbase/bin/sys_restore").unlink()
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "required tool is missing"):
                validate_bundle(root)

    def test_rejects_non_executable_required_tool(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            (root / "dm/bin/dimp").chmod(0o644)
            with self.assertRaisesRegex(BundleError, "required tool is not executable"):
                validate_bundle(root)

    def test_rejects_x86_elf_even_with_updated_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            _write_elf(root / "dm/bin/dimp", 62)
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "not 64-bit AArch64"):
                validate_bundle(root)

    def test_rejects_truncated_elf_header(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            (root / "dm/bin/dimp").write_bytes(b"\x7fELF" + bytes(16))
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "truncated ELF64 header"):
                validate_bundle(root)

    def test_rejects_invalid_elf_identification_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            tool = root / "dm/bin/dimp"
            header = bytearray(tool.read_bytes())
            header[6] = 0
            tool.write_bytes(header)
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "invalid ELF identification"):
                validate_bundle(root)

    def test_rejects_invalid_elf_encoding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            tool = root / "dm/bin/dimp"
            header = bytearray(tool.read_bytes())
            header[5] = 0
            tool.write_bytes(header)
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "invalid ELF identification"):
                validate_bundle(root)

    def test_rejects_non_elf_required_tool(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            tool = root / "dm/bin/dimp"
            tool.write_text("#!/bin/sh\necho not a native client\n", encoding="utf-8")
            tool.chmod(0o755)
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "required tool is not 64-bit AArch64 ELF"):
                validate_bundle(root)

    def test_rejects_incomplete_client_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            manifest = root / "manifest.properties"
            manifest.write_text(manifest.read_text().replace("DM V8.1.3.140", "DM V8"))
            _write_checksums(root)
            with self.assertRaisesRegex(BundleError, "complete version"):
                validate_bundle(root)

    def test_rejects_symlink_outside_vendor_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            external = Path(directory) / "outside.so"
            external.write_bytes(b"outside")
            (root / "dm/lib/external.so").symlink_to("../../../outside.so")
            with self.assertRaisesRegex(BundleError, "leaves its vendor bundle"):
                validate_bundle(root)

    def test_rejects_absolute_symlink_even_when_target_is_inside_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "bundle"
            root.mkdir()
            _make_bundle(root)
            target = root / "dm/lib/libfixture.so"
            (root / "dm/bin/absolute.so").symlink_to(target)
            with self.assertRaisesRegex(BundleError, "absolute symlink"):
                validate_bundle(root)


if __name__ == "__main__":
    unittest.main()
