import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("verify_phone", Path(__file__).parents[1] / "verify_phone.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class PhoneRunnerTest(unittest.TestCase):
    STATE_FILES = [
        "shared_prefs/places.xml",
        "shared_prefs/places.xml.bak",
        "shared_prefs/radar-viewport.xml",
        "shared_prefs/radar-viewport.xml.bak",
    ]

    def run_runner(
        self,
        *arguments,
        fail_phase=None,
        fail_restore=None,
        fail_state=None,
        missing_state=(),
        radio_state=("0", "1", "1"),
        screen="<screen>STALE Temperature 12°</screen>",
    ):
        calls = []

        def command(parts, **kwargs):
            calls.append(parts)
            joined = " ".join(parts)
            if "instrument" in parts:
                phase = parts[parts.index("phase") + 1]
                if phase == fail_phase:
                    return "INSTRUMENTATION_RESULT: result=FAIL"
                return "INSTRUMENTATION_RESULT: result=PASS"
            if "run-as" in joined:
                if parts[-1] == "pwd":
                    return "/data/user/0/app.drizziq"
                if "ls" in parts:
                    if any(parts[-1].endswith(f"/{name}") for name in missing_state):
                        raise subprocess.CalledProcessError(1, parts)
                    return parts[-1]
                if "cat" in parts:
                    return f"<preferences>{parts[-1]}</preferences>"
                if "sh" in parts or "run-as" in parts[-1]:
                    if fail_state and fail_state in parts[-1]:
                        raise subprocess.CalledProcessError(1, parts)
                    return ""
                if "rm" in parts:
                    if fail_state and parts[-1].endswith(f"/{fail_state}"):
                        raise subprocess.CalledProcessError(1, parts)
                    return ""
            if "cat" in parts:
                return screen
            if "screencap" in parts:
                return b"screenshot"
            if "settings" in parts:
                return dict(zip(("airplane_mode_on", "wifi_on", "mobile_data"), radio_state))[parts[-1]]
            if fail_restore and parts[-3:] == list(fail_restore):
                raise subprocess.CalledProcessError(1, parts)
            return ""

        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(runner, "ROOT", Path(temporary)),
            patch.object(sys, "argv", ["verify_phone.py", "--serial", "test-phone", *arguments]),
            patch.object(runner.subprocess, "check_output", side_effect=command),
            patch("builtins.print"),
        ):
            try:
                runner.main()
            except Exception as error:
                return calls, error
            finally:
                report = Path(temporary) / "tmp/verification/timings.json"
                self.timing_report = json.loads(report.read_text()) if report.exists() else None
        return calls, None

    def test_fixture_mode_is_default_and_live_transport_requires_explicit_selection(self):
        for arguments, expected in (((), "true"), (("--live",), "false")):
            with self.subTest(arguments=arguments):
                calls, error = self.run_runner(*arguments)
                self.assertIsNone(error)
                instruments = [parts for parts in calls if "instrument" in parts]
                self.assertTrue(all("fixture" in parts for parts in instruments))
                self.assertTrue(all(parts[parts.index("fixture") + 1] == expected for parts in instruments))

    def test_timings_retain_failed_phase_and_environment_without_retry(self):
        calls, error = self.run_runner("--phase", "navigation", "--phase", "radar", fail_phase="radar")
        self.assertIsInstance(error, RuntimeError)
        self.assertIsNotNone(self.timing_report)
        self.assertEqual(["navigation", "radar"], [row["phase"] for row in self.timing_report["phases"]])
        self.assertEqual([True, False], [row["passed"] for row in self.timing_report["phases"]])
        self.assertIn("device", self.timing_report)
        self.assertIn("cache", self.timing_report)
        self.assertGreaterEqual(self.timing_report["seconds"], 0)

    def phases(self, calls):
        return [parts[parts.index("phase") + 1] for parts in calls if "instrument" in parts]

    def state_restores(self, calls):
        joined = [" ".join(parts) for parts in calls]
        return [
            parts[-1].rsplit(" ", 1)[-1].strip("'")
            for parts, line in zip(calls, joined)
            if "run-as" in line and ("sh -c 'cat >" in line or " rm -f " in line)
        ]

    def test_default_runs_only_smoke_without_connectivity_or_data_changes(self):
        calls, error = self.run_runner()
        self.assertIsNone(error)
        self.assertEqual(["smoke"], self.phases(calls))
        self.assertFalse(any("settings" in parts or "svc" in parts or "connectivity" in parts for parts in calls))
        self.assertFalse(any("install" in parts or "uninstall" in parts or "screencap" in parts for parts in calls))
        self.assertEqual(self.STATE_FILES, self.state_restores(calls))

    def test_snapshot_precedes_every_instrumentation_phase(self):
        calls, error = self.run_runner()
        self.assertIsNone(error)
        snapshot = next(i for i, parts in enumerate(calls) if "run-as" in parts and "cat" in parts)
        first_phase = next(i for i, parts in enumerate(calls) if "instrument" in parts)
        self.assertLess(snapshot, first_phase)
        self.assertEqual(["shell", "am", "force-stop", "app.drizziq"], calls[0][3:])

    def test_full_runs_integration_and_restores_original_connectivity(self):
        calls, error = self.run_runner("--full")
        self.assertIsNone(error)
        self.assertEqual(["smoke", "main", "persistence", "offline", "stopped-network", "restore"], self.phases(calls))
        for suffix in (["connectivity", "airplane-mode", "disable"], ["svc", "wifi", "enable"], ["svc", "data", "enable"]):
            self.assertTrue(any(parts[-3:] == suffix for parts in calls))

    def test_focused_adds_registered_checks_after_smoke(self):
        calls, error = self.run_runner("--focused")
        self.assertIsNone(error)
        self.assertEqual(
            ["smoke", *runner.FOCUSED_PHASES],
            self.phases(calls),
        )

    def test_unknown_phase_is_rejected_before_contacting_the_device(self):
        with self.assertRaises(ValueError):
            runner.validate_phase("review-unknown")

    def test_full_failure_attempts_every_restore_and_retains_original_error(self):
        calls, error = self.run_runner("--full", fail_phase="offline", fail_restore=("svc", "wifi", "enable"), fail_state="places.xml")
        self.assertIsInstance(error, RuntimeError)
        self.assertIn("offline failed", str(error))
        self.assertTrue(any(parts[-3:] == ["svc", "data", "enable"] for parts in calls))
        self.assertTrue(any("Restoration" in note for note in error.__notes__))
        self.assertTrue(any("places.xml" in note for note in error.__notes__))
        self.assertEqual(self.STATE_FILES, self.state_restores(calls))

    def test_each_state_restore_failure_is_attempted_and_reported(self):
        for failed in runner.PREFERENCE_FILES:
            with self.subTest(failed=failed):
                calls, error = self.run_runner(fail_phase="smoke", fail_state=failed)
                self.assertIsInstance(error, RuntimeError)
                self.assertIn("smoke failed", str(error))
                self.assertTrue(any(failed in note for note in error.__notes__))
                self.assertEqual(self.STATE_FILES, self.state_restores(calls))

    def test_absent_original_state_and_backups_are_removed_during_cleanup(self):
        calls, error = self.run_runner(missing_state=("places.xml", "places.xml.bak"))
        self.assertIsNone(error)
        joined = [" ".join(parts) for parts in calls]
        self.assertTrue(any(" rm -f shared_prefs/places.xml" in line for line in joined))
        self.assertTrue(any(" rm -f shared_prefs/places.xml.bak" in line for line in joined))
        self.assertTrue(any("cat > shared_prefs/radar-viewport.xml" in line for line in joined))
        self.assertTrue(any("cat > shared_prefs/radar-viewport.xml.bak" in line for line in joined))

    def test_targeted_failure_still_restores_original_state(self):
        for failed in ("navigation", "radar", "viewport-restore"):
            with self.subTest(failed=failed):
                calls, error = self.run_runner("--phase", failed, fail_phase=failed)
                self.assertIsInstance(error, RuntimeError)
                self.assertIn(f"{failed} failed", str(error))
                self.assertEqual(self.STATE_FILES, self.state_restores(calls))

    def test_full_preserves_initially_disabled_connectivity_without_running_recovery(self):
        calls, error = self.run_runner("--full", radio_state=("1", "0", "0"))
        self.assertIsNone(error)
        self.assertNotIn("restore", self.phases(calls))
        changes = [parts[-3:] for parts in calls if "connectivity" in parts or "svc" in parts]
        self.assertEqual([
            ["connectivity", "airplane-mode", "enable"],
            ["svc", "wifi", "disable"],
            ["svc", "data", "disable"],
        ], changes[-3:])

    def test_failed_restoration_is_reported_even_when_all_checks_passed(self):
        calls, error = self.run_runner("--full", fail_restore=("svc", "wifi", "enable"))
        self.assertIsInstance(error, RuntimeError)
        self.assertIn("Restoration failed", str(error))
        self.assertNotIn("restore", self.phases(calls))
        self.assertTrue(any(parts[-3:] == ["svc", "data", "enable"] for parts in calls))

    def test_each_connectivity_restore_failure_is_attempted_and_reported(self):
        actions = (
            ("connectivity", "airplane-mode", "disable"),
            ("svc", "wifi", "enable"),
            ("svc", "data", "enable"),
        )
        for failed in actions:
            with self.subTest(failed=failed):
                calls, error = self.run_runner("--full", fail_restore=failed)
                self.assertIsInstance(error, RuntimeError)
                self.assertIn("Restoration failed", str(error))
                for attempted in actions:
                    self.assertTrue(any(parts[-3:] == list(attempted) for parts in calls))

    def test_offline_screen_requires_stale(self):
        calls, error = self.run_runner("--full", screen="<screen>Temperature 12°</screen>")
        self.assertIsInstance(error, RuntimeError)
        self.assertIn("STALE", str(error))

    def test_place_selection_integration_is_explicit_and_does_not_toggle_permissions_or_settings(self):
        calls,error=self.run_runner("--places-integration")
        self.assertIsNone(error)
        self.assertIn("places-integration",self.phases(calls))
        self.assertFalse(any("grant" in parts or "revoke" in parts or "settings" in parts or "svc" in parts for parts in calls))

    def test_failed_smoke_stops_before_later_phases(self):
        calls, error = self.run_runner(fail_phase="smoke")
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(["smoke"], self.phases(calls))


if __name__ == "__main__":
    unittest.main()
