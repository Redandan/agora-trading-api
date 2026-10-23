#!/usr/bin/env python3
"""Regression coverage for the shared, offline deployment gate."""
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(sys.argv.pop(1)) if len(sys.argv) > 1 else Path(__file__).with_name("java-tls-preflight.sh")
SOURCE = SCRIPT.read_text(encoding="utf-8").split("<<'JAVA_TLS_PREFLIGHT_PY'\n", 1)[1]
SOURCE = SOURCE.rsplit("\nJAVA_TLS_PREFLIGHT_PY", 1)[0]
gate = types.ModuleType("tls_gate")
exec(compile(SOURCE, str(SCRIPT), "exec"), gate.__dict__)


FIXTURES = Path(__file__).with_name("fixtures")
KEYTOOL = shutil.which("keytool")
RUN = subprocess.run


class TlsPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.blocklist = self.home / "lib/security/blocked.certs"
        self.blocklist.parent.mkdir(parents=True)
        self.blocklist.write_text("Algorithm=SHA-256\n" + "\n".join(sorted(gate.REQUIRED)) + "\n")
        self.metadata = patch.object(Path, "stat", return_value=types.SimpleNamespace(
            st_uid=0, st_mode=0o100644))
        self.metadata.start()
        self.addCleanup(self.metadata.stop)

    def test_official_baseline_and_future_additions_pass(self):
        self.assertEqual(86, len(gate.REQUIRED))
        self.assertEqual(len(gate.REQUIRED), gate.validate_blocklist(self.home))
        with self.blocklist.open("a") as output:
            output.write("F" * 64 + "\n")
        self.assertEqual(len(gate.REQUIRED) + 1, gate.validate_blocklist(self.home))

    def test_missing_compromised_fingerprint_fails(self):
        self.blocklist.write_text("Algorithm=SHA-256\n" + "\n".join(sorted(gate.REQUIRED)[1:]))
        with self.assertRaisesRegex(ValueError, "missing required"):
            gate.validate_blocklist(self.home)

    def test_unsupported_algorithm_fails(self):
        self.blocklist.write_text("Algorithm=MD5\n")
        with self.assertRaisesRegex(ValueError, "unsupported"):
            gate.validate_blocklist(self.home)

    def test_writable_security_file_fails_on_posix(self):
        if os.name != "posix":
            self.skipTest("POSIX ownership semantics")
        with patch.object(Path, "stat", return_value=types.SimpleNamespace(st_uid=0, st_mode=0o100664)):
            with self.assertRaisesRegex(ValueError, "root-owned"):
                gate.validate_blocklist(self.home)

    def test_tls_property_overrides_fail_without_printing_values(self):
        with patch.dict(os.environ, {"JAVA_OPTS": "-Djava.security.properties=/private/value"}, clear=True):
            with self.assertRaisesRegex(ValueError, "JAVA_OPTS") as raised:
                gate.validate_options()
            self.assertNotIn("/private/value", str(raised.exception))

    def test_compiled_global_tls_override_is_rejected_but_plain_text_is_allowed(self):
        # Use real javac output so this tests the deployed class-file boundary.
        self.metadata.stop()
        source = self.home / "TlsFixture.java"
        source.write_text("import javax.net.ssl.*; class TlsFixture { static void unsafe() { "
                          "HttpsURLConnection.setDefaultHostnameVerifier((h,s)->true); } } "
                          "class Harmless { String text = \"setDefaultHostnameVerifier\"; }")
        subprocess.run([os.environ.get("JAVAC", "javac"), "-d", str(self.home), str(source)],
                       capture_output=True, check=True, timeout=30)
        for name, rejected in [("TlsFixture", True), ("Harmless", False)]:
            jar = self.home / (name + ".jar")
            with zipfile.ZipFile(jar, "w") as archive:
                archive.write(self.home / (name + ".class"), "BOOT-INF/classes/" + name + ".class")
            if rejected:
                with self.assertRaisesRegex(ValueError, "overrides global HTTPS"):
                    gate.validate_jar(jar)
            else:
                gate.validate_jar(jar)


class CaTruststoreTest(unittest.TestCase):
    def test_empty_store_fails(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            gate.validate_pem("no certificates here")

    def test_fingerprint_matches_certificate_not_alias(self):
        safe = FIXTURES.joinpath("trusted-root.pem").read_text(encoding="ascii")
        removed = FIXTURES.joinpath("emsign-g3.pem").read_text(encoding="ascii")
        self.assertEqual(1, gate.validate_pem(safe))
        with self.assertRaisesRegex(ValueError, "emSign"):
            gate.validate_pem("Alias name: unrelated\n" + safe + removed)

    def import_cert(self, store, certificate, alias):
        subprocess.run([KEYTOOL, "-importcert", "-noprompt", "-alias", alias,
                        "-file", str(FIXTURES / certificate), "-keystore", str(store),
                        "-storetype", "JKS", "-storepass", "changeit"],
                       check=True, capture_output=True, timeout=30)

    @unittest.skipUnless(KEYTOOL, "keytool required for real keystore checks")
    def test_actual_keystore_with_renamed_removed_root_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "lib/security").mkdir(parents=True)
            store = home / "lib/security/cacerts"
            self.import_cert(store, "trusted-root.pem", "safe")
            def keytool(command, **kwargs):
                return RUN([KEYTOOL, *command[1:]], **kwargs)
            with patch.object(gate.subprocess, "run", side_effect=keytool):
                self.assertEqual(1, gate.validate_java_truststore(home)["checkedCertificates"])
                self.import_cert(store, "emsign-g3.pem", "unrelated-alias")
                with self.assertRaisesRegex(ValueError, "emSign"):
                    gate.validate_java_truststore(home)

    @unittest.skipUnless(KEYTOOL, "keytool required for real keystore checks")
    def test_jssecacerts_has_precedence_over_clean_cacerts(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "lib/security").mkdir(parents=True)
            self.import_cert(home / "lib/security/cacerts", "trusted-root.pem", "safe")
            self.import_cert(home / "lib/security/jssecacerts", "emsign-g3.pem", "custom-root")
            def keytool(command, **kwargs):
                return RUN([KEYTOOL, *command[1:]], **kwargs)
            with patch.object(gate.subprocess, "run", side_effect=keytool):
                with self.assertRaisesRegex(ValueError, "emSign"):
                    gate.validate_java_truststore(home)


if __name__ == "__main__":
    unittest.main()
