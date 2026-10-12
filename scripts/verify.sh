#!/usr/bin/env bash
# Full local verification: frontend static checks, then JVM tests + debug lint + debug APK.
# Extra Gradle tasks may be passed as arguments, e.g. `scripts/verify.sh :app:assembleRelease`.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# The system JAVA_HOME may be an older JDK; AGP 8 needs the bundled JDK 17.
jdk="$(ls -d "$root"/.tools/jdk-17* 2>/dev/null | head -n 1 || true)"
if [ -n "$jdk" ]; then export JAVA_HOME="$jdk"; fi
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f android/local.properties ]; then
  echo "ANDROID_HOME is not set and android/local.properties is missing" >&2
  exit 1
fi

npm run build

if [ $# -eq 0 ]; then set -- :app:testDebugUnitTest :app:lintDebug :app:assembleDebug; fi
"$root/.tools/gradle-8.5/bin/gradle" -p android --console=plain "$@"
