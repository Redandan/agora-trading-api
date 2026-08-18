from __future__ import annotations

from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
CLI_PATH = (
    REPO_ROOT
    / "src"
    / "main"
    / "java"
    / "com"
    / "agora"
    / "research"
    / "BtcDonchianStandaloneHistoricalCli.java"
)


class BtcDonchianStandaloneHistoricalCliTest(unittest.TestCase):
    def test_cli_is_offline_create_new_and_manifest_bound(self) -> None:
        if not CLI_PATH.is_file():
            self.skipTest("Java sources are intentionally absent from the worker package")
        source = CLI_PATH.read_text(encoding="utf-8")
        self.assertIn("0e76ef3bdcf4e30ae352cadd04eafdf4677cede3ac2b976790528ddc3c906ee8", source)
        self.assertIn("BtcDonchianShadowEngine", source)
        self.assertIn("StandardOpenOption.CREATE_NEW", source)
        self.assertIn("DIRECT_CLASSPATH_NO_MAVEN_EXEC_NO_SPRING", (
            REPO_ROOT
            / "research_pipeline"
            / "examples"
            / "btc-donchian-20d-10d-standalone-historical.v1.manifest.json"
        ).read_text(encoding="utf-8"))
        self.assertNotIn("SpringApplication", source)
        self.assertNotIn("JdbcTemplate", source)
        self.assertNotIn("RestTemplate", source)
        self.assertNotIn("WebClient", source)
        self.assertNotIn("new GateDecision(Map.copyOf(gates)", source)


if __name__ == "__main__":
    unittest.main()
