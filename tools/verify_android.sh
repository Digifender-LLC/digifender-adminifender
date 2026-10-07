#!/usr/bin/env bash
# Run from any directory. Use the repository wrapper and keep device evidence explicit.
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-all}"
if (($#)); then shift; fi

unit_tasks=(:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest)
build_tasks=(:core:data:lintDebug :core:data:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease)
case "$mode" in
  unit) tasks=("${unit_tasks[@]}") ;;
  build) tasks=("${build_tasks[@]}") ;;
  all) tasks=("${unit_tasks[@]}" "${build_tasks[@]}") ;;
  connected) tasks=(:core:data:connectedDebugAndroidTest) ;;
  *)
    echo 'Usage: tools/verify_android.sh [all|unit|build|connected] [Gradle options...]' >&2
    exit 2
    ;;
esac

cd "$repo_dir/android"
exec ./gradlew --no-daemon "$@" "${tasks[@]}"
