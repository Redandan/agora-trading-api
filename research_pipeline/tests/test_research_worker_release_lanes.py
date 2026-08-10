from __future__ import annotations

import hashlib
from pathlib import Path
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
WORKER = REPOSITORY / "scripts" / "research-worker"

CONTROL_UNITS = (
    "agora-research-mcp.service",
    "agora-research-dispatch.service",
    "agora-research-heartbeat.service",
    "agora-research-microstructure-handoff-export.service",
)
EXPORT_UNIT = "agora-research-microstructure-handoff-export.service"
DATA_UNITS = {
    "agora-research-source.service":
        "e03c4ce3c33d21a8e4b787384880355f8286e481f22bd3eff9ec0bcf8fb72f0f",
    "agora-research-evidence-ingest.service":
        "909525bea874ec348573012cf3c0d755fb467528199636b9f9f53857ad7de03d",
    "agora-research-microstructure-source.service":
        "067257baf9905787b491f1566ba86fca4676624e76aedd57c1f9ec653fb83a6f",
    "agora-research-microstructure-intake.service":
        "a8adf884a0c7b086e5fc9a486e16ec7b85b8b9f70a04108df68dc63cad887dba",
}


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ResearchWorkerReleaseLanesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.wrapper = text(REPOSITORY / "scripts/deploy_research_worker_upgrade_ssh.ps1")
        cls.standalone_verifier = text(
            REPOSITORY / "scripts/verify_research_worker_ssh.ps1"
        )
        cls.installer = text(WORKER / "install-upgrade.sh")
        cls.verifier = text(WORKER / "verify-worker.sh")
        cls.carry_unit = text(
            WORKER / "agora-research-dra-crypto-carry-source.service"
        )
        cls.documentation = text(REPOSITORY / "docs/server-research-worker-v2.md")
        cls.deploy_runbook = text(REPOSITORY / "docs/deploy-runbook.md")

    def test_control_units_use_only_fixed_control_lane(self) -> None:
        for name in CONTROL_UNITS:
            with self.subTest(unit=name):
                value = text(WORKER / name)
                self.assertIn("/opt/agora-research-worker/control-current", value)
                self.assertIn(
                    "Documentation=file:/opt/agora-research-worker/control-current/",
                    value,
                )
                if name != EXPORT_UNIT:
                    self.assertNotIn("/opt/agora-research-worker/current", value)
                self.assertIn(
                    "WorkingDirectory=/opt/agora-research-worker/control-current",
                    value,
                )
        for name in ("agora-research-dispatch.service", "agora-research-heartbeat.service"):
            self.assertIn(
                "Environment=APP_DIR=/opt/agora-research-worker/control-current",
                text(WORKER / name),
            )
        self.assertIn(
            "Environment=AGORA_RESEARCH_APP_DIR=/opt/agora-research-worker/control-current",
            text(WORKER / "agora-research-mcp.service"),
        )

    def test_exporter_is_fixed_control_lane_and_exactly_confined(self) -> None:
        value = text(WORKER / EXPORT_UNIT)
        self.assertIn("Type=oneshot", value)
        self.assertIn("User=agora-research", value)
        self.assertIn("Group=agora-research", value)
        self.assertIn("SupplementaryGroups=agora-evidence", value)
        self.assertIn("Restart=no", value)
        self.assertIn("IPAddressDeny=any", value)
        self.assertIn("RestrictAddressFamilies=AF_UNIX", value)
        self.assertIn("CapabilityBoundingSet=\n", value)
        self.assertIn("AmbientCapabilities=\n", value)
        self.assertIn("ProtectHome=yes", value)
        self.assertIn("PrivateDevices=yes", value)
        self.assertIn("NoNewPrivileges=yes", value)
        self.assertIn(
            "ExecStart=/opt/agora-research-worker/venv/bin/python "
            "-m research_pipeline.microstructure_handoff_export",
            value,
        )
        self.assertNotIn("Environment=", value)
        self.assertNotIn("[Install]", value)
        self.assertNotIn("ExecStart=/bin/", value)
        read_only = {
            line.removeprefix("ReadOnlyPaths=")
            for line in value.splitlines()
            if line.startswith("ReadOnlyPaths=")
        }
        self.assertEqual(
            read_only,
            {
                "/etc/agora-research/okx-microstructure-continuous-source-v3.json",
                "/var/lib/agora-research/state/microstructure-v3",
                "/var/lib/agora-evidence-source/microstructure-drop",
                "/etc/agora-research/local-tasks/"
                "microstructure-v3-evidence-diagnostic.v1.json",
                "/opt/agora-research-worker/current",
                "/opt/agora-research-worker/control-current",
            },
        )
        writes = {
            line.removeprefix("ReadWritePaths=")
            for line in value.splitlines()
            if line.startswith("ReadWritePaths=")
        }
        self.assertEqual(
            writes,
            {
                "/var/lib/agora-research/microstructure-v3-handoff-staging",
                "/var/lib/agora-research/microstructure-v3-handoff-export",
            },
        )

    def test_data_plane_units_are_byte_preserved_on_fixed_data_lane(self) -> None:
        for name, expected_hash in DATA_UNITS.items():
            with self.subTest(unit=name):
                raw = (WORKER / name).read_bytes()
                self.assertEqual(expected_hash, hashlib.sha256(raw).hexdigest())
                value = raw.decode("utf-8")
                self.assertIn("/opt/agora-research-worker/current", value)
                self.assertNotIn("/opt/agora-research-worker/control-current", value)

    def test_preserve_mode_is_explicit_only_and_rejects_binding_parameters(self) -> None:
        self.assertIn("[switch]$PreserveBoundDataPlane", self.wrapper)
        self.assertNotIn("$PreserveBoundDataPlane = $env:", self.wrapper)
        self.assertIn(
            "if ($PreserveBoundDataPlane -and $bindingRequested)", self.wrapper
        )
        self.assertIn(
            "if ($PreserveBoundDataPlane -and $PackageOnly)", self.wrapper
        )
        self.assertIn(
            'PRESERVE_BOUND_DATA_PLANE="`$preserve_mode"', self.wrapper
        )
        self.assertIn(
            'case "$PRESERVE_BOUND_DATA_PLANE" in 0|1)', self.installer
        )
        self.assertIn(
            'preserve mode rejects binding creation or replacement parameters',
            self.installer,
        )

    def test_default_upgrade_still_rejects_active_or_failed_source(self) -> None:
        self.assertIn(
            'if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] '
            '&& [ "$microstructure_active" = active ]; then',
            self.installer,
        )
        self.assertIn(
            'systemctl is-failed --quiet "$MICROSTRUCTURE_UNIT"', self.installer
        )
        self.assertIn(
            'fail "microstructure source unit must be inactive before upgrade"',
            self.installer,
        )

    def test_preserve_preflight_binds_release_manifest_state_and_pid(self) -> None:
        required = (
            '[ -L "$WORKER_ROOT/current" ]',
            'case "$preserve_data_current" in',
            'raw_binding != canonical',
            'binding release id does not match data-current',
            'binding manifest hash does not match data-current',
            'bound release provenance does not match manifest bytes',
            'microstructure V3 state inventory is not exact',
            'preserve_binding_sha256=',
            'preserve_binding_bytes=',
            'preserve_state_sha256=',
            'preserve_state_bytes=',
            'preserve_source_main_pid=',
            'ExecMainStartTimestampMonotonic',
        )
        for value in required:
            with self.subTest(value=value):
                self.assertIn(value, self.installer)

    def test_data_root_traversal_is_normalized_only_offline_and_verified(self) -> None:
        expected_metadata = '$WORKER_USER:$EVIDENCE_GROUP:710'
        normalize = (
            'sudo install -d -o "$WORKER_USER" -g "$EVIDENCE_GROUP" '
            '-m 0710 "$DATA_ROOT"'
        )
        self.assertIn(expected_metadata, self.installer)
        self.assertIn(normalize, self.installer)
        self.assertIn(
            'sudo -u "$SOURCE_USER" test -x "$DATA_ROOT"', self.installer
        )
        preserve_start = self.installer.index(
            'if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then'
        )
        release_mutation = self.installer.index(
            'sudo install -d -o root -g root -m 0755 "$RELEASE_DIR"'
        )
        preserve_preflight = self.installer[preserve_start:release_mutation]
        self.assertIn(expected_metadata, preserve_preflight)
        self.assertNotIn(normalize, preserve_preflight)
        self.assertIn(expected_metadata, self.verifier)
        self.assertIn(
            'sudo -u "$SOURCE_USER" test -x "$DATA_ROOT"', self.verifier
        )

    def test_only_control_link_moves_in_preserve_mode(self) -> None:
        self.assertIn(
            'sudo mv -Tf "$next_control_link" "$WORKER_ROOT/control-current"',
            self.installer,
        )
        data_move = 'sudo mv -Tf "$next_data_link" "$WORKER_ROOT/current"'
        self.assertIn(data_move, self.installer)
        link_section = self.installer[
            self.installer.index('next_control_link='):
            self.installer.index('if [ "$binding_requested" = true ]; then')
        ]
        self.assertIn('if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then', link_section)
        self.assertLess(
            link_section.index('if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then'),
            link_section.index(data_move),
        )

    def test_preserve_installs_no_data_plane_unit(self) -> None:
        marker = (
            'if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then\n'
            '  units_to_install=('
        )
        start = self.installer.index(marker)
        preserve_units = self.installer[start:self.installer.index("\nelse", start)]
        for name in CONTROL_UNITS:
            self.assertIn(name, preserve_units)
        for name in DATA_UNITS:
            self.assertNotIn(name, preserve_units)

    def test_exporter_is_installed_verified_but_never_enabled_or_started(self) -> None:
        self.assertIn(EXPORT_UNIT, self.installer)
        self.assertIn(f"/etc/systemd/system/{EXPORT_UNIT}", self.installer)
        for command in (
            f"systemctl enable {EXPORT_UNIT}",
            f"systemctl enable --now {EXPORT_UNIT}",
            f"systemctl start {EXPORT_UNIT}",
            f"systemctl restart {EXPORT_UNIT}",
        ):
            self.assertNotIn(command, self.installer)
        self.assertIn('systemctl cat "$MICROSTRUCTURE_EXPORT_UNIT"', self.verifier)
        self.assertIn(
            "microstructure handoff exporter is not cleanly inactive outside an explicit handoff request",
            self.verifier,
        )

    def test_carry_oneshot_has_effective_timeout_without_lifecycle_authority(self) -> None:
        unit_lines = self.carry_unit.splitlines()
        self.assertEqual(unit_lines.count("Type=oneshot"), 1)
        self.assertEqual(unit_lines.count("Restart=no"), 1)
        self.assertEqual(unit_lines.count("TimeoutStartSec=30m"), 1)
        self.assertFalse(any(line.startswith("RuntimeMaxSec=") for line in unit_lines))
        self.assertNotIn("[Install]", unit_lines)
        self.assertFalse(any(WORKER.glob("agora-research-dra-crypto-carry*.timer")))
        self.assertFalse(any(WORKER.glob("agora-research-dra-crypto-carry*.path")))

        for script in (self.installer, self.verifier):
            with self.subTest(
                script="installer" if script is self.installer else "verifier"
            ):
                self.assertIn(
                    '[ "$(systemctl show "$CARRY_UNIT" '
                    '--property=TimeoutStartUSec --value)" = 30min ]',
                    script,
                )
                self.assertIn(
                    '[ "$(systemctl show "$CARRY_UNIT" '
                    '--property=RuntimeMaxUSec --value)" = infinity ]',
                    script,
                )
                self.assertIn(
                    'carry source effective start timeout is not exactly 30 minutes',
                    script,
                )
                self.assertIn(
                    'carry source retains an effective runtime maximum', script
                )
                self.assertIn('systemctl is-enabled "$CARRY_UNIT"', script)
                self.assertIn('systemctl is-active "$CARRY_UNIT"', script)
                self.assertIn('--property=MainPID --value', script)
                for command in ("enable", "enable --now", "start", "restart"):
                    self.assertNotIn(
                        f'systemctl {command} "$CARRY_UNIT"', script
                    )

        self.assertIn("grep -Fxq 'TimeoutStartSec=30m'", self.verifier)
        self.assertIn("grep -Eq '^RuntimeMaxSec='", self.verifier)

    def test_preserve_has_exact_post_install_invariants(self) -> None:
        required = (
            'data-current link bytes changed during preserve upgrade',
            'data-current resolved release changed during preserve upgrade',
            'binding bytes or SHA-256 changed during preserve upgrade',
            'microstructure state bytes or SHA-256 changed during preserve upgrade',
            'bound release metadata changed during preserve upgrade',
            'microstructure source active state changed during preserve upgrade',
            'microstructure source MainPID changed during preserve upgrade',
            'microstructure source unit properties changed during preserve upgrade',
            'control-current does not resolve to the new release',
        )
        for value in required:
            with self.subTest(value=value):
                self.assertIn(value, self.installer)
        for command in (
            'systemctl stop "$MICROSTRUCTURE_UNIT"',
            'systemctl start "$MICROSTRUCTURE_UNIT"',
            'systemctl restart "$MICROSTRUCTURE_UNIT"',
            'systemctl kill "$MICROSTRUCTURE_UNIT"',
            'systemctl reset-failed "$MICROSTRUCTURE_UNIT"',
            'systemctl enable "$MICROSTRUCTURE_UNIT"',
            'systemctl disable "$MICROSTRUCTURE_UNIT"',
            'systemctl reload "$MICROSTRUCTURE_UNIT"',
        ):
            self.assertNotIn(command, self.installer)

    def test_ordinary_upgrade_converges_both_release_lanes(self) -> None:
        self.assertIn(
            'ordinary upgrade did not switch data-current to the new release',
            self.installer,
        )
        self.assertIn(
            'ordinary upgrade did not switch control-current to the new release',
            self.installer,
        )

    def test_verifier_requires_exact_dual_ids_and_separates_evidence(self) -> None:
        required = (
            'EXPECTED_CONTROL_RELEASE_ID is required',
            'EXPECTED_DATA_RELEASE_ID is required',
            '[ -L "$WORKER_ROOT/control-current" ]',
            '[ -L "$WORKER_ROOT/current" ]',
            'control-current release id does not match the exact expectation',
            'data-current release id does not match the exact expectation',
            'AGORA_RESEARCH_APP_DIR="$control_current"',
            'AGORA_RESEARCH_APP_DIR="$data_current"',
            'trusted control code verified the complete data release source inventory',
            'require_sha256 "$data_current/research_pipeline/',
            'expected_source = sys.argv[3]',
            'cd "$data_current"',
            'control and data-plane systemd release lanes are byte-separated',
        )
        for value in required:
            with self.subTest(value=value):
                self.assertIn(value, self.verifier)

    def test_active_bound_source_can_be_verified_after_its_start_day(self) -> None:
        self.assertIn(
            '"$EXPECT_MICROSTRUCTURE_SOURCE" <<\'PY\'', self.verifier
        )
        self.assertIn(
            'if expected_source != "active" and start_day <=', self.verifier
        )
        self.assertIn(
            'binding forward start day is not strictly future for an inactive source',
            self.verifier,
        )

    def test_standalone_verifier_requires_explicit_active_source_expectations(self) -> None:
        wrapper = self.standalone_verifier
        self.assertIn(
            "[string]$ExpectedControlReleaseId = $env:AGORA_RESEARCH_EXPECTED_CONTROL_RELEASE_ID",
            wrapper,
        )
        self.assertIn(
            "[string]$ExpectedDataReleaseId = $env:AGORA_RESEARCH_EXPECTED_DATA_RELEASE_ID",
            wrapper,
        )
        self.assertIn(
            "[string]$ExpectMicrostructureSource = $env:AGORA_RESEARCH_EXPECT_MICROSTRUCTURE_SOURCE",
            wrapper,
        )
        self.assertEqual(wrapper.count("^[A-Za-z0-9._-]+$"), 2)
        self.assertIn(
            '$ExpectMicrostructureSource -notin @("disabled", "active")', wrapper
        )
        self.assertIn(
            '$intakePreflightFlag = if ($ExpectMicrostructureSource -eq "active") { "1" } else { "0" }',
            wrapper,
        )
        for assignment in (
            "EXPECTED_CONTROL_RELEASE_ID='$ExpectedControlReleaseId'",
            "EXPECTED_DATA_RELEASE_ID='$ExpectedDataReleaseId'",
            "EXPECT_MICROSTRUCTURE_SOURCE='$ExpectMicrostructureSource'",
            "MICROSTRUCTURE_INTAKE_PREFLIGHT='$intakePreflightFlag'",
            "EXPECT_TIMER='$ExpectTimer'",
            "RUN_HEARTBEAT='$runFlag'",
            "RUN_SOURCE_PROBE='$probeFlag'",
        ):
            self.assertIn(assignment, wrapper)
        self.assertIn("[switch]$RunHeartbeat", wrapper)
        self.assertIn("[switch]$RunSourceProbe", wrapper)
        self.assertNotIn("Read-Host", wrapper)
        self.assertNotIn("readlink", wrapper)

        runbook = self.deploy_runbook
        self.assertIn("Explicit standalone Research Worker verification", runbook)
        self.assertIn("-ExpectMicrostructureSource disabled", runbook)
        self.assertIn("-ExpectMicrostructureSource active", runbook)
        self.assertIn("is not strictly read-only", runbook)
        self.assertIn("bounded temporary permission probe", runbook)
        self.assertIn("cleanup trap", runbook)
        self.assertIn("does not write canonical research state or Trading state", runbook)
        self.assertIn("does not prove uninterrupted day completion", runbook)
        self.assertIn("predictive value, PnL, or drawdown", runbook)

    def test_wrapper_runs_exact_verifier_before_provenance_output(self) -> None:
        invocation = (
            "EXPECTED_CONTROL_RELEASE_ID='$ReleaseId' "
            'EXPECTED_DATA_RELEASE_ID="`$expected_data_release"'
        )
        self.assertIn(invocation, self.wrapper)
        verify_index = self.wrapper.index(invocation)
        control_output = self.wrapper.index("RESEARCH_WORKER_CONTROL_RELEASE")
        data_output = self.wrapper.index("RESEARCH_WORKER_DATA_RELEASE")
        self.assertLess(verify_index, control_output)
        self.assertLess(verify_index, data_output)

    def test_package_only_remains_no_host_no_key_and_pre_network(self) -> None:
        self.assertIn("if (-not $PackageOnly)", self.wrapper)
        package_only = self.wrapper.index("if ($PackageOnly) {")
        remote_stage = self.wrapper.index('$stage = "/home/ubuntu/.cache/')
        self.assertLess(package_only, remote_stage)
        section = self.wrapper[package_only:remote_stage]
        self.assertIn("PACKAGE_ONLY=COMPLETE_NO_NETWORK", section)
        self.assertIn("return", section)

    def test_runbook_freezes_opt_in_dual_distribution_package_only(self) -> None:
        command = (
            ".\\scripts\\deploy_research_worker_upgrade_ssh.ps1 "
            "-PackageOnly -IncludeCarryDistribution"
        )
        self.assertIn(command, self.deploy_runbook)
        self.assertIn("`target/microstructure-dist`", self.deploy_runbook)
        self.assertIn("`target/dra-crypto-carry-dist`", self.deploy_runbook)
        self.assertIn("`PACKAGE_ONLY=COMPLETE_NO_NETWORK`", self.deploy_runbook)
        self.assertIn("does not\ninstall a release", self.deploy_runbook)
        self.assertIn("start or enable the inactive carry\noneshot", self.deploy_runbook)
        self.assertIn(
            "Omitting\n`-IncludeCarryDistribution` deliberately retains the microstructure-only",
            self.deploy_runbook,
        )

    def test_documentation_freezes_the_two_lane_boundary(self) -> None:
        self.assertIn("## Immutable dual release lanes", self.documentation)
        self.assertIn("`control-current`", self.documentation)
        self.assertIn("`current` remains the only release", self.documentation)
        self.assertIn("`-PreserveBoundDataPlane`", self.documentation)
        self.assertIn("MainPID, and unit property to remain identical", self.documentation)
        self.assertIn("does not authorize deployment", self.documentation)


if __name__ == "__main__":
    unittest.main()
