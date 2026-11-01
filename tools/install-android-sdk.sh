#!/usr/bin/env bash
# Install the Android SDK packages this project builds against.
# Used by GitHub Actions and usable on a fresh machine: relies on cmdline-tools being installed.
set -euo pipefail

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
export ANDROID_HOME=$sdk

sdkmanager=$(ls "$sdk"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort -V | tail -1 || true)
[[ -n $sdkmanager ]] || {
  echo "sdkmanager not found under $sdk/cmdline-tools" >&2
  exit 1
}

list=$("$sdkmanager" --list 2>/dev/null)
pick() {
  awk -F'|' -v pat="$1" '$0 ~ pat { gsub(/ /, "", $1); print $1 }' <<<"$list" | sort -V | tail -1
}

platform=$(pick 'platforms;android-37')
buildtools=$(pick 'build-tools;36\.0\.0')
[[ -n $platform ]] || {
  echo "no android-37 platform in sdkmanager --list" >&2
  exit 1
}
[[ -n $buildtools ]] || {
  echo "no 36.0.0 build-tools in sdkmanager --list" >&2
  exit 1
}

echo "installing $platform $buildtools"
yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
"$sdkmanager" "$platform" "$buildtools"
