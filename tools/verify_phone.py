#!/usr/bin/env python3
"""Exercise the installed debug/test APKs; restore the phone's radio settings."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])

    def command(*parts):
        return subprocess.check_output(adb + list(parts), text=True).strip()

    reports = ROOT / "docs/verification"
    reports.mkdir(parents=True, exist_ok=True)

    def phase(name):
        output = command("shell", "am", "instrument", "-w", "-e", "phase", name,
                         "app.drizziq.test/app.drizziq.PhoneExercise")
        (reports / f"device-{name}.txt").write_text(output + "\n")
        print(output, flush=True)
        assert "INSTRUMENTATION_RESULT: result=PASS" in output, f"{name} failed"

    airplane = command("shell", "settings", "get", "global", "airplane_mode_on") == "1"
    wifi = command("shell", "settings", "get", "global", "wifi_on") in ("1", "2")
    data = command("shell", "settings", "get", "global", "mobile_data") == "1"
    try:
        phase("main")
        phase("forecast")
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
        assert "STALE" in screen and "OFFLINE" in screen
        (reports / "drizziq-offline.png").write_bytes(subprocess.check_output(adb + ["exec-out", "screencap", "-p"]))
    finally:
        command("shell", "cmd", "connectivity", "airplane-mode", "enable" if airplane else "disable")
        command("shell", "svc", "wifi", "enable" if wifi else "disable")
        command("shell", "svc", "data", "enable" if data else "disable")
        command("shell", "rm", "-f", "/sdcard/drizziq-verification.xml")
    if not airplane and (wifi or data):
        phase("restore")


if __name__ == "__main__":
    main()
