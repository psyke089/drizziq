#!/usr/bin/env python3
"""Run installed phone checks. Default: smoke; --full also changes data/connectivity."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--full", action="store_true", help="Opt in to favourites, persistence, offline and recovery checks")
    parser.add_argument("--reports", type=Path, default=ROOT / "tmp/verification")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    args.reports.mkdir(parents=True, exist_ok=True)

    def command(*parts):
        return subprocess.check_output(adb + list(parts), text=True, timeout=240).strip()

    def phase(name):
        output = command("shell", "am", "instrument", "-w", "-e", "phase", name,
                         "app.drizziq.test/app.drizziq.PhoneExercise")
        (args.reports / f"device-{name}.txt").write_text(output + "\n")
        print(output, flush=True)
        if "INSTRUMENTATION_RESULT: result=PASS" not in output:
            raise RuntimeError(f"{name} failed; see {args.reports / f'device-{name}.txt'}")

    def full_checks():
        airplane = command("shell", "settings", "get", "global", "airplane_mode_on") == "1"
        wifi = command("shell", "settings", "get", "global", "wifi_on") in ("1", "2")
        data = command("shell", "settings", "get", "global", "mobile_data") == "1"
        original_error = None
        try:
            phase("main")
            command("shell", "am", "force-stop", "app.drizziq")
            phase("persistence")
            command("shell", "cmd", "connectivity", "airplane-mode", "enable")
            command("shell", "svc", "wifi", "disable")
            command("shell", "svc", "data", "disable")
            command("shell", "am", "force-stop", "app.drizziq")
            phase("offline")
            command("shell", "am", "start", "-W", "-n", "app.drizziq/.MainActivity")
            command("shell", "uiautomator", "dump", "/sdcard/drizziq-verification.xml")
            screen = command("exec-out", "cat", "/sdcard/drizziq-verification.xml")
            if "STALE" not in screen or "OFFLINE" not in screen:
                raise RuntimeError("Offline screen must show STALE and OFFLINE")
            (args.reports / "drizziq-offline.png").write_bytes(subprocess.check_output(adb + ["exec-out", "screencap", "-p"], timeout=30))
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
            phase("restore")

    phase("navigation")
    phase("lifecycle")
    phase("forecast")
    if args.full:
        full_checks()
    # Instrumentation can leave the launcher in front. Return to the app explicitly.
    command("shell", "am", "start", "-W", "-n", "app.drizziq/.MainActivity")


if __name__ == "__main__":
    main()
