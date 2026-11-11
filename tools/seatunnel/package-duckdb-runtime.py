#!/usr/bin/env python3
"""Build a checksummed DuckDB runtime bundle for the Web API or SeaTunnel Engine."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import tarfile
import tempfile
from urllib.request import Request, urlopen


DUCKDB_VERSION = "1.5.6"
JDBC_VERSION = "1.5.6.0"
PLATFORM_CHECKSUMS = {
    "linux_arm64": {
        "httpfs.duckdb_extension.gz": (
            "https://extensions.duckdb.org/v1.5.6/linux_arm64/"
            "httpfs.duckdb_extension.gz",
            "b18472a0e85cbf15ca4ebbe335173f87b62a074cd0f26072d4c756205c6239d0",
            "ae940d800b7f938a4848d0eebcffb28dda0bbed76987f11dc55faa6d1a86db0d",
        ),
        "sqlite_scanner.duckdb_extension.gz": (
            "https://extensions.duckdb.org/v1.5.6/linux_arm64/"
            "sqlite_scanner.duckdb_extension.gz",
            "f6afdebec449521c68adc17019d55c93179fa0a4b7f0d655b53caa12366ef0fe",
            "473216143ca24e25f97af783644edf63ef2bcee63d8961ceb4127b929bd40a7d",
        ),
    },
    "linux_amd64": {
        "httpfs.duckdb_extension.gz": (
            "https://extensions.duckdb.org/v1.5.6/linux_amd64/"
            "httpfs.duckdb_extension.gz",
            "19e6906934a845487c96f9c94beee250c71e32bb9be260eb27ad96939a1df5f0",
            "ff36e7d2561e21b958c243c6bd5f6a10cfc055a98ea76c0280b94728b57588e2",
        ),
        "sqlite_scanner.duckdb_extension.gz": (
            "https://extensions.duckdb.org/v1.5.6/linux_amd64/"
            "sqlite_scanner.duckdb_extension.gz",
            "edc79e292b069187f9dc95bfa735fbe578718e0b1df71d4f9ce2ca671b6dd51b",
            "0b3c576e7a9b65846285e5e7f4980a700d79d9c14b10b2fb56d495f4d36b566a",
        ),
    },
}
JDBC_SHA256 = "04ae3375d7c40dc88dc9bf0014b82ba5e66715564584af4e3abea39c7ba144d3"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def download_verified(url: str, expected_sha256: str) -> bytes:
    request = Request(url, headers={"User-Agent": "seatunnel-web-duckdb-runtime-packager/1.0"})
    with urlopen(request, timeout=60) as response:
        data = response.read()
    actual = sha256(data)
    if actual != expected_sha256:
        raise RuntimeError(
            f"SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual}"
        )
    return data


def add_file(archive: tarfile.TarFile, root: Path, relative_path: str) -> None:
    path = root / relative_path
    info = archive.gettarinfo(str(path), arcname=relative_path)
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.mtime = 0
    info.mode = 0o644
    with path.open("rb") as source:
        archive.addfile(info, source)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--target",
        required=True,
        choices=("engine", "web"),
        help="engine includes JDBC/httpfs/sqlite; web includes only sqlite_scanner",
    )
    parser.add_argument(
        "--platform",
        required=True,
        choices=sorted(PLATFORM_CHECKSUMS),
        help="target runtime platform; this is not inferred from the packaging host",
    )
    parser.add_argument("--output", required=True, type=Path, help="output .tar.gz path")
    args = parser.parse_args()

    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="seatunnel-duckdb-runtime-") as temp_dir:
        root = Path(temp_dir)
        extension_dir = root / "extensions" / f"v{DUCKDB_VERSION}" / args.platform
        extension_dir.mkdir(parents=True)

        checksums: dict[str, str] = {}
        if args.target == "engine":
            (root / "lib").mkdir(parents=True)
            jdbc_url = (
                "https://repo.maven.apache.org/maven2/org/duckdb/duckdb_jdbc/"
                f"{JDBC_VERSION}/duckdb_jdbc-{JDBC_VERSION}.jar"
            )
            jdbc = download_verified(jdbc_url, JDBC_SHA256)
            jdbc_path = f"lib/duckdb_jdbc-{JDBC_VERSION}.jar"
            (root / jdbc_path).write_bytes(jdbc)
            checksums[jdbc_path] = JDBC_SHA256
            print(f"Verified DuckDB JDBC {JDBC_VERSION} ({len(jdbc)} bytes)")

        extension_names = (
            tuple(PLATFORM_CHECKSUMS[args.platform])
            if args.target == "engine"
            else ("sqlite_scanner.duckdb_extension.gz",)
        )
        for compressed_name in extension_names:
            url, compressed_hash, extension_hash = PLATFORM_CHECKSUMS[args.platform][compressed_name]
            compressed = download_verified(url, compressed_hash)
            extension = gzip.decompress(compressed)
            if sha256(extension) != extension_hash:
                raise RuntimeError(f"Decompressed extension checksum mismatch: {compressed_name}")
            filename = compressed_name.removesuffix(".gz")
            relative_path = f"extensions/v{DUCKDB_VERSION}/{args.platform}/{filename}"
            (root / relative_path).write_bytes(extension)
            checksums[relative_path] = extension_hash
            print(f"Verified {filename} for {args.platform} ({len(extension)} bytes)")

        manifest = {
            "target": args.target,
            "duckdb_version": DUCKDB_VERSION,
            "jdbc_artifact_version": JDBC_VERSION,
            "platform": args.platform,
            "sha256": checksums,
        }
        (root / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        checksum_lines = "".join(
            f"{checksums[path]}  {path}\n" for path in sorted(checksums)
        )
        (root / "SHA256SUMS").write_text(checksum_lines, encoding="ascii")

        temp_output = output.with_name(output.name + ".tmp")
        try:
            with tarfile.open(temp_output, "w:gz", format=tarfile.PAX_FORMAT) as archive:
                for relative_path in [
                    "SHA256SUMS",
                    "manifest.json",
                    *sorted(checksums),
                ]:
                    add_file(archive, root, relative_path)
            os.replace(temp_output, output)
        finally:
            temp_output.unlink(missing_ok=True)

    print(f"Created verified {args.target} runtime package: {output}")
    print(f"DuckDB {DUCKDB_VERSION}, platform {args.platform}")


if __name__ == "__main__":
    main()
