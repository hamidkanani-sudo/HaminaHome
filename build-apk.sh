#!/usr/bin/env bash
set -euo pipefail

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.9 is required. On GitHub Actions, use the included build-apk.yml workflow." >&2
  exit 1
fi

gradle --no-daemon :app:assembleDebug
printf '\nAPK created at: app/build/outputs/apk/debug/app-debug.apk\n'
