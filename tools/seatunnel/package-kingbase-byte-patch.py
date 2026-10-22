#!/usr/bin/env python3
"""Package a class-only Kingbase fix against the exact deployed JDBC connector."""

import argparse
from copy import copy
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

CLASS = (
    "org/apache/seatunnel/connectors/seatunnel/jdbc/internal/dialect/kingbase/"
    "KingbaseTypeConverter.class"
)


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-jar", type=Path, required=True)
    parser.add_argument("--fixed-jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir / "connector-jdbc-3.0.0.jar"
    if output.resolve() in {args.base_jar.resolve(), args.fixed_jar.resolve()}:
        parser.error("output must not overwrite an input JAR")
    if output.exists():
        parser.error("output already exists; choose a new directory")

    with ZipFile(args.base_jar) as base, ZipFile(args.fixed_jar) as fixed:
        replacement = fixed.read(CLASS)
        original = base.read(CLASS)
        for alias in (b"VARCHARBYTE", b"CHARBYTE", b"BPCHARBYTE"):
            if alias not in replacement:
                parser.error("fixed JAR does not contain the Kingbase BYTE aliases")
        if int.from_bytes(replacement[6:8], "big") > int.from_bytes(original[6:8], "big"):
            parser.error("replacement requires a newer Java runtime than the baseline")
        if any(name.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
               for name in base.namelist() if name.upper().startswith("META-INF/")):
            parser.error("signed baseline JAR requires a separately signed release")

        args.output_dir.mkdir(parents=True, exist_ok=True)
        with ZipFile(output, "w") as result:
            for entry in base.infolist():
                result.writestr(copy(entry), replacement if entry.filename == CLASS else base.read(entry))
        with ZipFile(output) as result:
            assert base.namelist() == result.namelist()
            assert result.testzip() is None
            changed = [name for name in base.namelist() if base.read(name) != result.read(name)]
            assert changed == [CLASS], changed

    manifest = {
        "engineVersion": "3.0.0",
        "baseSha256": sha256(args.base_jar.read_bytes()),
        "fixedBuildSha256": sha256(args.fixed_jar.read_bytes()),
        "patchSha256": sha256(output.read_bytes()),
        "changedEntries": changed,
        "classSha256": sha256(replacement),
        "javaClassVersion": int.from_bytes(replacement[6:8], "big"),
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )
    (args.output_dir / "SHA256SUMS").write_text(f"{manifest['patchSha256']}  {output.name}\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
