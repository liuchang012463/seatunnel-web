#!/usr/bin/env python3
import hashlib
import json
import os
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

from validate_bundle import BundleError, validate_bundle


SCRIPT_DIR = Path(__file__).resolve().parent


def _write_elf(path: Path, machine: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    header = bytearray(20)
    header[:4] = b"\x7fELF"
    header[4:7] = bytes((2, 1, 1))
    struct.pack_into("<H", header, 18, machine)
    path.write_bytes(header)
    path.chmod(0o755)


def _write_checksums(root: Path) -> None:
    paths = sorted(
        path for path in root.rglob("*")
        if path.is_file() and path.name != "SHA256SUMS"
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


class WrapperTest(unittest.TestCase):
    def _run_wrapper(self, wrapper_name: str, vendor: str, executable: str):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "native-clients"
            wrapper = root / "bin" / wrapper_name
            wrapper.parent.mkdir(parents=True)
            shutil.copy2(SCRIPT_DIR / wrapper_name, wrapper)
            target = root / vendor / "bin" / executable
            target.parent.mkdir(parents=True)
            target.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "print(json.dumps({'args': sys.argv[1:], 'cwd': os.getcwd(), "
                "'ld_library_path': os.environ.get('LD_LIBRARY_PATH'), "
                "'kingbase_password': os.environ.get('KINGBASE_PASSWORD')}))\n"
                "raise SystemExit(int(os.environ['TOOL_EXIT']))\n",
                encoding="utf-8",
            )
            target.chmod(0o755)
            work_dir = Path(directory) / "work"
            work_dir.mkdir()
            environment = os.environ.copy()
            environment.update({
                "LD_LIBRARY_PATH": "/injected/path",
                "KINGBASE_PASSWORD": "secret value",
                "TOOL_EXIT": "37",
            })
            args = ["file name.dmp", "a;$()", ""]
            result = subprocess.run(
                [str(wrapper), *args],
                cwd=work_dir,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            return result, json.loads(result.stdout), args, work_dir, root

    def test_dimp_wrapper_preserves_args_environment_cwd_and_exit_code(self):
        result, observed, args, work_dir, root = self._run_wrapper("dimp", "dm", "dimp")
        self.assertEqual(args, observed["args"])
        self.assertEqual(str(work_dir), observed["cwd"])
        self.assertEqual(f"{root}/dm/bin:{root}/dm/lib", observed["ld_library_path"])
        self.assertEqual("secret value", observed["kingbase_password"])
        self.assertEqual(37, result.returncode)

    def test_sys_restore_wrapper_preserves_args_environment_cwd_and_exit_code(self):
        result, observed, args, work_dir, root = self._run_wrapper(
            "sys_restore", "kingbase", "sys_restore"
        )
        self.assertEqual(args, observed["args"])
        self.assertEqual(str(work_dir), observed["cwd"])
        self.assertEqual(
            f"{root}/kingbase/bin:{root}/kingbase/lib", observed["ld_library_path"]
        )
        self.assertEqual("secret value", observed["kingbase_password"])
        self.assertEqual(37, result.returncode)


if __name__ == "__main__":
    unittest.main()
