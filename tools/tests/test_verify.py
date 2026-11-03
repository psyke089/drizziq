from importlib.machinery import SourceFileLoader
import importlib.util
from pathlib import Path
import os
import subprocess
import sys
import unittest
from unittest.mock import patch

LOADER = SourceFileLoader("verify", str(Path(__file__).parents[1] / "verify"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class VerificationCommandTest(unittest.TestCase):
    def invoke(self, arguments, statuses=(0,), environment=None):
        with (
            patch.object(sys, "argv", ["verify", *arguments]),
            patch.dict(os.environ, environment or {}, clear=True),
            patch.object(
                verify.subprocess,
                "run",
                side_effect=[subprocess.CompletedProcess([], code) for code in statuses],
            ) as run,
        ):
            result = verify.main()
            return result, run.call_args_list

    def test_default_runs_tooling_tests_then_complete_gradle_gate(self):
        result, calls = self.invoke(["--offline"], (0, 0))
        self.assertEqual(0, result)
        self.assertIn("unittest", calls[0].args[0])
        self.assertEqual(["--no-daemon", "verifyLocal", "--offline"], calls[1].args[0][1:])

    def test_tooling_failure_stops_before_gradle(self):
        result, calls = self.invoke([], (1,))
        self.assertEqual(1, result)
        self.assertEqual(1, len(calls))

    def test_focused_test_forwards_filter_without_running_tooling_suite(self):
        result, calls = self.invoke(["--gradle-only", "testDebugUnitTest", "--tests", "app.drizziq.DataTest"], (7,))
        self.assertEqual(7, result)
        self.assertEqual(1, len(calls))
        self.assertEqual(["--no-daemon", "testDebugUnitTest", "--tests", "app.drizziq.DataTest"], calls[0].args[0][1:])

    def test_explicit_environment_takes_precedence_over_workstation_defaults(self):
        environment = {"JAVA_HOME": "/custom/jdk", "ANDROID_USER_HOME": "/custom/android", "GRADLE_USER_HOME": "/custom/gradle"}
        _, calls = self.invoke(["--gradle-only", "tasks"], environment=environment)
        for key, value in environment.items():
            self.assertEqual(value, calls[0].kwargs["env"][key])

    def test_workstation_defaults_select_jdk21_and_standard_android_user_home(self):
        with patch.object(verify.Path, "is_dir", return_value=True), patch.object(verify.Path, "exists", return_value=True):
            _, calls = self.invoke(["--gradle-only", "tasks"])
        self.assertEqual("/usr/lib/jvm/java-21-openjdk", calls[0].kwargs["env"]["JAVA_HOME"])
        self.assertNotIn("ANDROID_USER_HOME", calls[0].kwargs["env"])

    def test_focused_invocation_without_task_fails_before_starting_gradle(self):
        with self.assertRaises(SystemExit):
            self.invoke(["--gradle-only", "--offline"])


if __name__ == "__main__":
    unittest.main()
