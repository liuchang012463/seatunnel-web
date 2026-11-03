#!/usr/bin/env python3
"""Validate a controlled linux/arm64 offline-import client bundle."""

from __future__ import annotations

import hashlib
import os
import re
import stat
import struct
import sys
from pathlib import Path, PurePosixPath


REQUIRED_MANIFEST = {
    "format_version",
    "platform",
    "architecture",
    "dm_version",
    "dm_source",
    "kingbase_version",
    "kingbase_source",
}
PLACEHOLDERS = {"", "unknown", "unspecified", "n/a", "none", "tbd", "latest", "current", "pending"}
CHECKSUM_LINE = re.compile(r"^([0-9a-fA-F]{64})  (.+)$")
EM_AARCH64 = 183


class BundleError(ValueError):
    """Raised when bundle metadata or payload does not meet the contract."""


def fail(message: str) -> None:
    raise BundleError(message)


def _read_manifest(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read manifest.properties: {exc}")

    for number, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, separator, value = stripped.partition("=")
        if not separator or not key.strip() or not value.strip():
            fail(f"manifest.properties:{number}: expected non-empty key=value")
        key = key.strip()
        if key in result:
            fail(f"manifest.properties:{number}: duplicate key {key!r}")
        result[key] = value.strip()

    missing = REQUIRED_MANIFEST - result.keys()
    extra = result.keys() - REQUIRED_MANIFEST
    if missing:
        fail("manifest.properties is missing: " + ", ".join(sorted(missing)))
    if extra:
        fail("manifest.properties has unsupported fields: " + ", ".join(sorted(extra)))
    if result["format_version"] != "1":
        fail("manifest format_version must be 1")
    if result["platform"] != "linux/arm64" or result["architecture"] != "aarch64":
        fail("manifest must declare platform=linux/arm64 and architecture=aarch64")

    for key in ("dm_version", "kingbase_version", "dm_source", "kingbase_source"):
        value = result[key]
        if value.casefold() in PLACEHOLDERS or any(ord(char) < 32 for char in value):
            fail(f"manifest field {key} must contain a specific, traceable value")
    for key in ("dm_version", "kingbase_version"):
        if len(re.findall(r"\d+", result[key])) < 3:
            fail(f"manifest field {key} must include the complete version identifier")
    return result


def _walk_bundle(root: Path) -> tuple[list[Path], list[Path]]:
    regular_files: list[Path] = []
    symlinks: list[Path] = []
    for current, directory_names, file_names in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in list(directory_names):
            path = current_path / name
            if path.is_symlink():
                symlinks.append(path)
                directory_names.remove(name)
            elif not path.is_dir():
                fail(f"unsupported bundle entry: {path.relative_to(root)}")
        for name in file_names:
            path = current_path / name
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                symlinks.append(path)
            elif stat.S_ISREG(mode):
                regular_files.append(path)
            else:
                fail(f"unsupported bundle entry: {path.relative_to(root)}")

    for path in regular_files + symlinks:
        relative = path.relative_to(root).as_posix()
        if "\n" in relative or "\r" in relative or "\\" in relative:
            fail(f"bundle paths must not contain backslashes or newlines: {relative!r}")
    return regular_files, symlinks


def _validate_layout(root: Path) -> tuple[list[Path], list[Path]]:
    allowed_root = {"manifest.properties", "SHA256SUMS", "dm", "kingbase"}
    actual_root = {entry.name for entry in root.iterdir()}
    if actual_root != allowed_root:
        missing = allowed_root - actual_root
        extra = actual_root - allowed_root
        details = []
        if missing:
            details.append("missing " + ", ".join(sorted(missing)))
        if extra:
            details.append("unexpected " + ", ".join(sorted(extra)))
        fail("bundle root must contain only manifest.properties, SHA256SUMS, dm/, kingbase/ (" + "; ".join(details) + ")")
    for name in ("dm", "kingbase"):
        path = root / name
        if path.is_symlink() or not path.is_dir():
            fail(f"bundle entry {name}/ must be a real directory")
    for name in ("manifest.properties", "SHA256SUMS"):
        path = root / name
        if path.is_symlink() or not path.is_file():
            fail(f"bundle entry {name} must be a regular file")

    regular_files, symlinks = _walk_bundle(root)
    for path in symlinks:
        vendor_root = root / path.relative_to(root).parts[0]
        if PurePosixPath(os.readlink(path)).is_absolute():
            fail(f"absolute symlink is not relocatable: {path.relative_to(root)}")
        try:
            resolved = path.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            fail(f"invalid or broken symlink {path.relative_to(root)}: {exc}")
        try:
            resolved.relative_to(vendor_root.resolve())
        except ValueError:
            fail(f"symlink leaves its vendor bundle directory: {path.relative_to(root)}")
        if not resolved.is_file():
            fail(f"symlink must resolve to a regular file: {path.relative_to(root)}")
    return regular_files, symlinks


def _validate_checksums(root: Path, regular_files: list[Path], symlinks: list[Path]) -> None:
    expected_files = {
        path.relative_to(root).as_posix()
        for path in regular_files + symlinks
        if path.name != "SHA256SUMS"
    }
    try:
        lines = (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read SHA256SUMS: {exc}")

    actual_files: set[str] = set()
    for number, line in enumerate(lines, 1):
        match = CHECKSUM_LINE.fullmatch(line)
        if match is None:
            fail(f"SHA256SUMS:{number}: expected '<sha256><two spaces><relative path>'")
        digest, relative = match.groups()
        pure = PurePosixPath(relative)
        if pure.is_absolute() or pure.as_posix() != relative or any(part in {"", ".", ".."} for part in pure.parts):
            fail(f"SHA256SUMS:{number}: invalid relative path {relative!r}")
        if relative in actual_files:
            fail(f"SHA256SUMS:{number}: duplicate path {relative!r}")
        actual_files.add(relative)
        path = root.joinpath(*pure.parts)
        if not path.is_file():
            fail(f"SHA256SUMS:{number}: path is not a bundle file: {relative}")
        digest_state = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest_state.update(chunk)
        actual = digest_state.hexdigest()
        if actual.casefold() != digest.casefold():
            fail(f"SHA256SUMS:{number}: checksum mismatch for {relative}")

    if actual_files != expected_files:
        missing = expected_files - actual_files
        extra = actual_files - expected_files
        details = []
        if missing:
            details.append("not covered: " + ", ".join(sorted(missing)))
        if extra:
            details.append("not present: " + ", ".join(sorted(extra)))
        fail("SHA256SUMS does not exactly cover bundle files (" + "; ".join(details) + ")")


def _is_aarch64_elf(path: Path) -> bool | None:
    with path.open("rb") as stream:
        header = stream.read(20)
    if not header.startswith(b"\x7fELF"):
        return None
    if len(header) < 20:
        fail(f"truncated ELF file: {path}")
    elf_class = header[4]
    encoding = header[5]
    if elf_class != 2 or encoding not in (1, 2):
        return False
    endian = "<" if encoding == 1 else ">"
    machine = struct.unpack(endian + "H", header[18:20])[0]
    return machine == EM_AARCH64


def _validate_tools_and_architecture(root: Path, regular_files: list[Path]) -> None:
    for tool in (root / "dm/bin/dimp", root / "kingbase/bin/sys_restore"):
        if not tool.is_file():
            fail(f"required tool is missing: {tool.relative_to(root)}")
        if (tool.stat().st_mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)) == 0:
            fail(f"required tool is not executable: {tool.relative_to(root)}")

    for path in regular_files:
        if path.name == "SHA256SUMS":
            continue
        is_aarch64 = _is_aarch64_elf(path)
        if is_aarch64 is False:
            fail(f"ELF file is not 64-bit AArch64: {path.relative_to(root)}")


def validate_bundle(bundle: Path) -> dict[str, str]:
    root = bundle.resolve(strict=True)
    if not root.is_dir():
        fail(f"bundle path is not a directory: {bundle}")
    manifest = _read_manifest(root / "manifest.properties")
    regular_files, symlinks = _validate_layout(root)
    _validate_checksums(root, regular_files, symlinks)
    _validate_tools_and_architecture(root, regular_files)
    return manifest


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"Usage: {Path(argv[0]).name} BUNDLE_DIRECTORY", file=sys.stderr)
        return 2
    try:
        manifest = validate_bundle(Path(argv[1]))
    except (BundleError, OSError) as exc:
        print(f"Bundle validation failed: {exc}", file=sys.stderr)
        return 1
    print(
        "Bundle valid: "
        f"DM {manifest['dm_version']} ({manifest['dm_source']}), "
        f"Kingbase {manifest['kingbase_version']} ({manifest['kingbase_source']}), "
        f"{manifest['platform']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
