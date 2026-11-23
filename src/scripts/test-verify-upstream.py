#!/usr/bin/env python3
"""Offline regression tests of fail-closed verification using synthetic fixtures.

These fixtures do not replace the real six-binary verification or modify its lock.
Run: python3 scripts/test-verify-upstream.py
"""
import csv
import hashlib
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class VerifyUpstreamTest(unittest.TestCase):
    names = ("sing-box", "easytier-core", "cloudflared", "wstunnel", "amneziawg-go", "awg")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "upstream").mkdir()
        self.assets = self.root / "app/src/main/assets/arm64"
        self.assets.mkdir(parents=True)
        self.script = self.root / "scripts/verify-upstream.sh"
        shutil.copyfile(Path(__file__).with_name("verify-upstream.sh"), self.script)
        self.lock = self.root / "upstream/SHA256SUMS.lock"
        rows, sums = [], []
        for name in self.names:
            data = ("synthetic fixture for " + name + "\n").encode()
            (self.assets / name).write_bytes(data)
            digest = hashlib.sha256(data).hexdigest()
            rows.append(f"{name}\tfixture\thttps://example.invalid/{name}\t{digest}\n")
            sums.append(f"{digest}  {name}\n")
        self.lock.write_text("".join(rows))
        (self.assets / "SHA256SUMS").write_text("".join(sums))

    def run_verifier(self):
        result = subprocess.run(["bash", str(self.script)], capture_output=True, text=True)
        rows = list(csv.DictReader(io.StringIO(result.stdout), delimiter="\t"))
        return result, rows

    def test_all_six_pass(self):
        result, rows = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(rows), 6)
        self.assertTrue(all(row["result"] == "PASS" for row in rows))

    def test_tampering_checks_remaining_components(self):
        (self.assets / "sing-box").write_bytes(b"tampered")
        result, rows = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(rows), 6)
        self.assertEqual(rows[0]["result"], "FAIL")
        self.assertTrue(all(row["result"] == "PASS" for row in rows[1:]))

    def test_missing_binary_checks_remaining_components(self):
        (self.assets / "sing-box").unlink()
        result, rows = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(rows), 6)
        self.assertEqual(rows[0]["actual_sha256"], "MISSING")

    def test_symlink_is_rejected(self):
        file = self.assets / "awg"
        file.rename(self.assets / "awg-real")
        file.symlink_to("awg-real")
        result, rows = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(rows[-1]["result"], "FAIL")

    def test_runtime_manifest_mismatch(self):
        (self.assets / "SHA256SUMS").write_text("")
        result, rows = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(rows), 6)
        self.assertIn("Runtime SHA256SUMS differs", result.stderr)

    def test_duplicate_component_rejected(self):
        text = self.lock.read_text()
        self.lock.write_text(text + text.splitlines(keepends=True)[0])
        result, _ = self.run_verifier()
        self.assertEqual(result.returncode, 3)

    def test_missing_lock_component_rejected(self):
        self.lock.write_text("".join(self.lock.read_text().splitlines(keepends=True)[:-1]))
        result, _ = self.run_verifier()
        self.assertEqual(result.returncode, 3)

    def test_unexpected_lock_component_rejected(self):
        self.lock.write_text(self.lock.read_text().replace("sing-box\t", "unknown\t", 1))
        result, _ = self.run_verifier()
        self.assertEqual(result.returncode, 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
