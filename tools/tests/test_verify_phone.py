import importlib.util
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
    def run_runner(self, *arguments, fail_phase=None, fail_restore=False, radio_state=("0", "1", "1")):
        calls = []

        def command(parts, **kwargs):
            calls.append(parts)
            if "instrument" in parts:
                phase = parts[parts.index("phase") + 1]
                if phase == fail_phase:
                    return "INSTRUMENTATION_RESULT: result=FAIL"
                return "INSTRUMENTATION_RESULT: result=PASS"
            if "cat" in parts:
                return "<screen>STALE OFFLINE</screen>"
            if "screencap" in parts:
                return b"screenshot"
            if "settings" in parts:
                return dict(zip(("airplane_mode_on", "wifi_on", "mobile_data"), radio_state))[parts[-1]]
            if fail_restore and parts[-3:] == ["svc", "wifi", "enable"]:
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
        return calls, None

    def phases(self, calls):
        return [parts[parts.index("phase") + 1] for parts in calls if "instrument" in parts]

    def test_default_runs_only_smoke_without_connectivity_or_data_changes(self):
        calls, error = self.run_runner()
        self.assertIsNone(error)
        self.assertEqual(["navigation", "lifecycle", "forecast", "radar", "places-search", "viewport-save", "viewport-restore"], self.phases(calls))
        self.assertTrue(all(parts[:3] == ["adb", "-s", "test-phone"] for parts in calls))
        self.assertFalse(any("settings" in parts or "svc" in parts or "connectivity" in parts for parts in calls))
        seed = next(i for i, parts in enumerate(calls) if "viewport-save" in parts)
        restore = next(i for i, parts in enumerate(calls) if "viewport-restore" in parts)
        self.assertTrue(any(parts[-3:] == ["am", "force-stop", "app.drizziq"] for parts in calls[seed+1:restore]))
        self.assertFalse(any("install" in parts or "uninstall" in parts or "screencap" in parts for parts in calls))
        self.assertEqual(["shell", "am", "start", "-W", "-n", "app.drizziq/.MainActivity"], calls[-1][3:])

    def test_full_runs_integration_and_restores_original_connectivity(self):
        calls, error = self.run_runner("--full")
        self.assertIsNone(error)
        self.assertEqual(["navigation", "lifecycle", "forecast", "radar", "places-search", "viewport-save", "viewport-restore", "main", "persistence", "offline", "restore"], self.phases(calls))
        for suffix in (["connectivity", "airplane-mode", "disable"], ["svc", "wifi", "enable"], ["svc", "data", "enable"]):
            self.assertTrue(any(parts[-3:] == suffix for parts in calls))

    def test_full_failure_attempts_every_restore_and_retains_original_error(self):
        calls, error = self.run_runner("--full", fail_phase="offline", fail_restore=True)
        self.assertIsInstance(error, RuntimeError)
        self.assertIn("offline failed", str(error))
        self.assertTrue(any(parts[-3:] == ["svc", "data", "enable"] for parts in calls))
        self.assertTrue(any("Restoration" in note for note in error.__notes__))

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
        calls, error = self.run_runner("--full", fail_restore=True)
        self.assertIsInstance(error, RuntimeError)
        self.assertIn("Restoration failed", str(error))
        self.assertNotIn("restore", self.phases(calls))
        self.assertTrue(any(parts[-3:] == ["svc", "data", "enable"] for parts in calls))

    def test_place_selection_integration_is_explicit_and_does_not_toggle_permissions_or_settings(self):
        calls,error=self.run_runner("--places-integration")
        self.assertIsNone(error)
        self.assertIn("places-integration",self.phases(calls))
        self.assertFalse(any("grant" in parts or "revoke" in parts or "settings" in parts or "svc" in parts for parts in calls))

    def test_failed_smoke_stops_before_later_phases(self):
        calls, error = self.run_runner(fail_phase="navigation")
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(["navigation"], self.phases(calls))


if __name__ == "__main__":
    unittest.main()
