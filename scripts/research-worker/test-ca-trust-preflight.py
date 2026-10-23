"""Real PEM/SSLContext and isolated keytool regression checks; no application starts."""
import importlib.util
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ca_gate", ROOT / "ca-trust-preflight.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SAFE = ROOT / "fixtures/trusted-root.pem"
REMOVED = ROOT / "fixtures/emsign-g3.pem"
KEYTOOL = shutil.which("keytool")
RUN = subprocess.run
CONTEXT = ssl.create_default_context


class CaGateTests(unittest.TestCase):
    def test_removed_root_fails_and_trusted_root_passes(self):
        for name, safe in [("emsign-g3.pem", False), ("trusted-root.pem", True)]:
            data = (ROOT / "fixtures" / name).read_text(encoding="ascii")
            if safe:
                self.assertEqual(gate.validate_pem(data), 1)
            else:
                with self.assertRaisesRegex(ValueError, "emSign"):
                    gate.validate_pem(data)

    def test_empty_store_is_not_a_success(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            gate.validate_pem("no certificates here")

    def test_version_floor(self):
        for version in ("2026.10.19", "2026.10.20", "2027.1.1"):
            gate.validate_certifi_version(version)
        for version in ("2026.2.25", "2026.10.18", "2026.10.19rc1", "unknown"):
            with self.assertRaises(ValueError):
                gate.validate_certifi_version(version)

    def test_effective_python_bundle_is_checked_even_with_a_safe_version(self):
        fake = SimpleNamespace(__version__="2026.10.19", where=lambda: str(REMOVED))
        with patch.dict(sys.modules, {"certifi": fake}), patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "emSign"):
                gate.check_python()

    def test_python_extra_bundle_cannot_reintroduce_removed_root(self):
        fake = SimpleNamespace(__version__="2026.10.19", where=lambda: str(SAFE))
        def context(*args, **kwargs):
            return CONTEXT(cafile=kwargs.get("cafile") or str(SAFE))
        for variable in ("REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "SSL_CERT_FILE"):
            with self.subTest(variable=variable), patch.dict(sys.modules, {"certifi": fake}), \
                 patch.dict(os.environ, {variable: str(REMOVED)}, clear=True), \
                 patch.object(gate.ssl, "create_default_context", side_effect=context):
                with self.assertRaisesRegex(ValueError, "emSign"):
                    gate.check_python()

    def test_lazy_ca_directory_or_disabled_tls_requires_review(self):
        fake = SimpleNamespace(__version__="2026.10.19", where=lambda: str(SAFE))
        for environment in ({"SSL_CERT_DIR": "private-path"}, {"PYTHONHTTPSVERIFY": "0"}):
            with patch.dict(sys.modules, {"certifi": fake}), patch.dict(os.environ, environment, clear=True):
                with self.assertRaises(ValueError) as error:
                    gate.check_python()
                self.assertNotIn("private-path", str(error.exception))

    def test_java_override_is_rejected_without_disclosing_value(self):
        for variable in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            with self.assertRaisesRegex(ValueError, variable) as error:
                gate.validate_java_options({variable: "-Djavax.net.ssl.trustStore=/private/secret"})
            self.assertNotIn("/private/secret", str(error.exception))

    def test_unloaded_directory_root_is_also_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unrelated-alias.0"
            shutil.copyfile(REMOVED, path)
            with self.assertRaisesRegex(ValueError, "emSign"):
                gate.validate_ca_directory(directory)

    @unittest.skipUnless(KEYTOOL, "keytool required for actual keystore inspection")
    def test_actual_keystore_alias_changes_do_not_hide_removed_root(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "lib/security").mkdir(parents=True)
            store = home / "lib/security/cacerts"
            self.import_cert(store, SAFE, "legitimate")
            def keytool(command, **kwargs):
                return RUN([KEYTOOL, *command[1:]], **kwargs)
            with patch.object(gate.subprocess, "run", side_effect=keytool):
                self.assertEqual(gate.validate_java_truststore(home)["checkedCertificates"], 1)
                self.import_cert(store, REMOVED, "renamed-do-not-match-an-alias")
                with self.assertRaisesRegex(ValueError, "emSign"):
                    gate.validate_java_truststore(home)

    @unittest.skipUnless(KEYTOOL, "keytool required for actual keystore inspection")
    def test_jssecacerts_takes_precedence_over_clean_cacerts(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "lib/security").mkdir(parents=True)
            self.import_cert(home / "lib/security/cacerts", SAFE, "safe")
            self.import_cert(home / "lib/security/jssecacerts", REMOVED, "custom-root")
            def keytool(command, **kwargs):
                return RUN([KEYTOOL, *command[1:]], **kwargs)
            with patch.object(gate.subprocess, "run", side_effect=keytool):
                with self.assertRaisesRegex(ValueError, "emSign"):
                    gate.validate_java_truststore(home)

    @staticmethod
    def import_cert(store, certificate, alias):
        RUN([KEYTOOL, "-importcert", "-noprompt", "-alias", alias, "-file", str(certificate),
             "-keystore", str(store), "-storetype", "JKS", "-storepass", "changeit"],
            check=True, capture_output=True, timeout=30)


if __name__ == "__main__":
    unittest.main(verbosity=2)
