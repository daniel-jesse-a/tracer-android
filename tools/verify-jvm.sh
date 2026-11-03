#!/usr/bin/env bash
# Offline compile + unit-test of the pure-Kotlin modules (:core, :events) using the Kotlin
# compiler bundled inside a Gradle distribution. Useful on CI boxes without the Android SDK.
# Usage: tools/verify-jvm.sh [/path/to/gradle-home]
set -euo pipefail
GH="${1:-${GRADLE_HOME:-/opt/gradle}}"
L="$GH/lib"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/verify-jvm"; rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
jar() { ls "$L"/$1 2>/dev/null | head -1; }
STD="$(jar 'kotlin-stdlib-[0-9]*.jar')"
CP_RT="$STD:$(jar 'kotlinx-coroutines-core-jvm-*.jar'):$(jar 'kotlinx-serialization-core-jvm-*.jar'):$(jar 'kotlinx-serialization-json-jvm-*.jar')"
CP_C="$(ls "$L"/*.jar | tr '\n' ':')"
kc() { java -Xmx2g -cp "$CP_C" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"; }
echo "== compiling :core + :events"
kc -cp "$CP_RT" -d "$OUT/main" $(find "$ROOT/core/src/main" "$ROOT/events/src/main" -name '*.kt')
# Android-free logic from the Android modules (pure mapping/format/decimation helpers)
EXTRA_MAIN=(
  collectors/src/main/kotlin/app/tracer/collectors/Mappings.kt
  collectors/src/main/kotlin/app/tracer/collectors/MotionAggregator.kt
  probes/src/main/kotlin/app/tracer/probes/ProbeLogic.kt
  probes/src/main/kotlin/app/tracer/probes/Sntp.kt
  probes/src/main/kotlin/app/tracer/probes/TowerCache.kt
  pairing/src/main/kotlin/app/tracer/pairing/SampleStamps.kt
  app/src/main/kotlin/app/tracer/run/SampleRestamp.kt
  ui/src/main/kotlin/app/tracer/ui/components/chart/Decimation.kt
  ui/src/main/kotlin/app/tracer/ui/event/EventJson.kt
)
EXTRA_TEST=(
  collectors/src/test/kotlin/app/tracer/collectors/MappingsTest.kt
  collectors/src/test/kotlin/app/tracer/collectors/MotionAggregatorTest.kt
  probes/src/test/kotlin/app/tracer/probes/ProbeLogicTest.kt
  probes/src/test/kotlin/app/tracer/probes/SntpTest.kt
  probes/src/test/kotlin/app/tracer/probes/TowerCacheTest.kt
  app/src/test/kotlin/app/tracer/run/SampleRestampTest.kt
  ui/src/test/kotlin/app/tracer/ui/components/chart/DecimateMinMaxTest.kt
)
echo "== compiling Android-free helpers from Android modules"
mkdir -p "$OUT/extra"
kc -cp "$CP_RT:$OUT/main" -d "$OUT/extra" "${EXTRA_MAIN[@]/#/$ROOT/}"
echo "== compiling tests"
kc -Xfriend-paths="$OUT/main,$OUT/extra" -cp "$CP_RT:$OUT/main:$OUT/extra:$(jar 'junit-4*.jar')" -d "$OUT/test" $(find "$ROOT/core/src/test" "$ROOT/events/src/test" -name '*.kt' 2>/dev/null) "${EXTRA_TEST[@]/#/$ROOT/}"
for r in "$ROOT/core/src/test/resources" "$ROOT/events/src/test/resources" "$ROOT/core/src/main/resources"; do [ -d "$r" ] && cp -r "$r"/. "$OUT/test"/; done
TESTS=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g')
echo "== running tests"
java -cp "$CP_RT:$OUT/main:$OUT/extra:$OUT/test:$(jar 'junit-4*.jar'):$(ls "$L"/hamcrest*.jar 2>/dev/null | head -1)" org.junit.runner.JUnitCore $TESTS
