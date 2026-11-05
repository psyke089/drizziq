#!/usr/bin/env python3
"""Run installed phone checks. Default: smoke; --focused adds regression checks; --full also changes data/connectivity."""
import argparse
import os
from pathlib import Path
import subprocess
import json
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "app.drizziq"

# Keep in sync with the registry in PhoneExercise. Instrumentation phases outside
# these groups are rejected instead of falling through to PASS.
SMOKE_PHASES = ("smoke",)
FOCUSED_PHASES = (
    "hourly-refresh",
    "forecast-status",
    "radar-corruption",
    "fixture-states",
    "lifecycle",
)
# Longer checks are opt-in by name; cosmetic layout checks have been retired.
EXTRA_PHASES = (
    "navigation", "forecast", "radar", "radar-loading", "places-search",
    "viewport-save", "viewport-restore", "static-assets",
    "day-forecast", "header-weather", "fixture-forecast", "fixture-ui",
)
INTEGRATION_PHASES = ("places-integration",)
FULL_PHASES = ("main", "persistence", "offline", "stopped-network", "restore")
# Diagnostic probes report measurements or exercise gestures outside suite gating.
PROBES = ("recenter-probe", "geocoding-probe", "station-benchmark", "radar-gestures")
KNOWN_PHASES = frozenset(
    SMOKE_PHASES + FOCUSED_PHASES + EXTRA_PHASES + INTEGRATION_PHASES + FULL_PHASES + PROBES
)

# Selected place and favourites live in places.xml; the saved map camera lives in
# radar-viewport.xml. Android promotes a stale .bak file to the main file on load,
# so both stores are snapshotted together with their backups before any Activity
# can change them.
PREFERENCE_FILES = (
    "places.xml",
    "places.xml.bak",
    "radar-viewport.xml",
    "radar-viewport.xml.bak",
)


def validate_phase(name):
    if name not in KNOWN_PHASES:
        raise ValueError(f"Unknown phone phase: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog="Phases: " + ", ".join(sorted(KNOWN_PHASES)))
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--focused", action="store_true", help="Also run the maintained focused regression checks")
    parser.add_argument("--live", action="store_true", help="Use real providers instead of isolated local fixtures")
    parser.add_argument("--phase", action="append", help="Run only this registered phase; repeat for multiple phases")
    parser.add_argument("--places-integration",action="store_true",help="Opt in to selecting/searching/saving test places; original places and map camera are restored")
    parser.add_argument("--full", action="store_true", help="Opt in to favourites, persistence, offline and recovery checks")
    parser.add_argument("--reports", type=Path, default=ROOT / "tmp/verification")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    args = parser.parse_args()
    for name in args.phase or ():
        validate_phase(name)
        if name in FULL_PHASES:
            parser.error(f"{name} is full-integration-only; use --full so radio settings are restored")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    args.reports.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    report = {"phases": [], "fixture": not args.live, "setup": "Build/install excluded"}

    def environment():
        def optional(*parts):
            try:
                return command(*parts)
            except Exception as error:
                return f"Unavailable: {type(error).__name__}"
        report["device"] = optional("shell", "getprop", "ro.product.model")
        report["api"] = optional("shell", "getprop", "ro.build.version.sdk")
        package = optional("shell", "dumpsys", "package", PACKAGE)
        report["version"] = [line.strip() for line in package.splitlines() if "versionName=" in line or "versionCode=" in line]
        report["cache"] = optional("shell", "run-as", PACKAGE, "du", "-sk", "cache", "files")
        report["network"] = "isolated fixture transport" if not args.live else "live providers; device connectivity unchanged"

    def save_timings():
        report["seconds"] = time.monotonic() - started
        text = json.dumps(report, indent=2) + "\n"
        (args.reports / "timings.json").write_text(text)
        (args.reports / f"timings-{time.time_ns()}.json").write_text(text)

    def command(*parts, input=None):
        return subprocess.check_output(adb + list(parts), text=True, timeout=240, input=input).strip()

    def phase(name, live=False):
        validate_phase(name)
        row = {"phase": name, "fixture": not (args.live or live), "passed": False}
        phase_started = time.monotonic()
        try:
            output = command("shell", "am", "instrument", "-w", "-e", "phase", name,
                             "-e", "fixture", str(row["fixture"]).lower(),
                             "app.drizziq.test/app.drizziq.PhoneExercise")
            (args.reports / f"device-{name}.txt").write_text(output + "\n")
            print(output, flush=True)
            row["passed"] = "INSTRUMENTATION_RESULT: result=PASS" in output.splitlines()
            if not row["passed"]:
                raise RuntimeError(f"{name} failed; see {args.reports / f'device-{name}.txt'}")
        except BaseException as error:
            row["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            row["seconds"] = time.monotonic() - phase_started
            report["phases"].append(row)

    def read_original_state():
        command("shell", "am", "force-stop", PACKAGE)
        try:
            command("shell", "run-as", PACKAGE, "pwd")
        except subprocess.CalledProcessError as error:
            raise RuntimeError(
                "Cannot snapshot original app state; install the debuggable debug app"
            ) from error
        state = {}
        for name in PREFERENCE_FILES:
            path = f"shared_prefs/{name}"
            try:
                command("shell", "run-as", PACKAGE, "ls", path)
            except subprocess.CalledProcessError:
                state[name] = None
            else:
                try:
                    state[name] = command("shell", "run-as", PACKAGE, "cat", path)
                except subprocess.CalledProcessError as error:
                    raise RuntimeError(f"Cannot snapshot {path}") from error
        return state

    def restore_original_state(state):
        failures = []
        try:
            command("shell", "am", "force-stop", PACKAGE)
        except Exception as error:
            failures.append(f"force-stop: {error}")
        for name, content in state.items():
            path = f"shared_prefs/{name}"
            try:
                if content is None:
                    command("shell", "run-as", PACKAGE, "rm", "-f", path)
                else:
                    command(
                        "shell",
                        f"run-as {PACKAGE} sh -c 'cat > {path}'",
                        input=content,
                    )
            except Exception as error:
                failures.append(f"{path}: {error}")
        # Fixture-only state is separate from real preferences and never survives a run.
        try:
            command("shell", "run-as", PACKAGE, "rm", "-rf", "cache/phone-fixture",
                    "shared_prefs/phone-fixture-places.xml", "shared_prefs/phone-fixture-places.xml.bak",
                    "shared_prefs/phone-fixture-radar-viewport.xml", "shared_prefs/phone-fixture-radar-viewport.xml.bak",
                    "shared_prefs/phone-fixture-radar-test.xml", "shared_prefs/phone-fixture-radar-test.xml.bak",
                    "shared_prefs/phone-fixture-location-permission.xml", "shared_prefs/phone-fixture-location-permission.xml.bak")
        except Exception as error:
            failures.append(f"fixture cleanup: {error}")
        # Instrumentation can leave the launcher in front. Return to the app explicitly.
        try:
            command("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        except Exception as error:
            failures.append(f"app start: {error}")
        return failures

    def full_checks():
        airplane = command("shell", "settings", "get", "global", "airplane_mode_on") == "1"
        wifi = command("shell", "settings", "get", "global", "wifi_on") in ("1", "2")
        data = command("shell", "settings", "get", "global", "mobile_data") == "1"
        original_error = None
        try:
            phase("main", live=True)
            command("shell", "am", "force-stop", PACKAGE)
            phase("persistence", live=True)
            command("shell", "cmd", "connectivity", "airplane-mode", "enable")
            command("shell", "svc", "wifi", "disable")
            command("shell", "svc", "data", "disable")
            command("shell", "am", "force-stop", PACKAGE)
            phase("offline", live=True)
            command("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            command("shell", "uiautomator", "dump", "/sdcard/drizziq-verification.xml")
            screen = command("exec-out", "cat", "/sdcard/drizziq-verification.xml")
            if "STALE" not in screen:
                raise RuntimeError("Offline screen must show STALE")
            if "Temperature " not in screen or "No forecast received" in screen:
                raise RuntimeError("Offline screen must retain cached forecast values")
            (args.reports / "drizziq-offline.png").write_bytes(subprocess.check_output(adb + ["exec-out", "screencap", "-p"], timeout=30))
            phase("stopped-network", live=True)
        except BaseException as error:
            original_error = error
            raise
        finally:
            failures = []
            for parts in (
                ("shell", "cmd", "connectivity", "airplane-mode", "enable" if airplane else "disable"),
                ("shell", "svc", "wifi", "enable" if wifi else "disable"),
                ("shell", "svc", "data", "enable" if data else "disable"),
                ("shell", "rm", "-f", "/sdcard/drizziq-verification.xml"),
            ):
                try:
                    command(*parts)
                except Exception as error:
                    failures.append(f"{' '.join(parts)}: {error}")
            if failures:
                message = "Restoration failed: " + "; ".join(failures)
                if original_error is not None:
                    original_error.add_note(message)
                else:
                    raise RuntimeError(message)
        if not airplane and (wifi or data):
            phase("restore", live=True)

    state = None
    original_error = None
    try:
        state = read_original_state()
        environment()
        for name in args.phase or SMOKE_PHASES:
            if name == "viewport-restore":
                command("shell", "am", "force-stop", PACKAGE)
            phase(name)
        if args.focused:
            for name in FOCUSED_PHASES:
                if not args.live or name not in {"fixture-forecast", "fixture-states", "fixture-ui"}:
                    phase(name)
        if args.places_integration:
            phase("places-integration", live=True)
        if args.full:
            full_checks()
    except BaseException as error:
        original_error = error
        raise
    finally:
        try:
            failures = restore_original_state(state) if state is not None else []
            report["passed"] = original_error is None and not failures
            if failures:
                report["cleanup_failures"] = failures
                message = "Restoration failed: " + "; ".join(failures)
                if original_error is not None:
                    original_error.add_note(message)
                else:
                    raise RuntimeError(message)
        finally:
            save_timings()


if __name__ == "__main__":
    main()
