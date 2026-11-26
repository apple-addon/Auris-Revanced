#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
BUILD_DIR=$(mktemp -d "$ROOT/tools/tracker/.build.XXXXXX")
trap 'rm -rf -- "$BUILD_DIR"' EXIT

# Build in a disposable copy: the vendored source tree and app build files stay
# untouched, while the smoke binary still exercises exactly libopenmpt 0.8.10.
cp -a "$ROOT/native/libopenmpt" "$BUILD_DIR/libopenmpt"

MAKE_OPTIONS=(
  STATIC_LIB=1
  SHARED_LIB=0
  TEST=0
  EXAMPLES=0
  OPENMPT123=0
  NO_ZLIB=1
  NO_MINIZ=1
  NO_MPG123=1
  NO_MINIMP3=1
  NO_OGG=1
  NO_VORBIS=1
  NO_VORBISFILE=1
  NO_STBVORBIS=1
  NO_FLAC=1
  NO_SNDFILE=1
  NO_SDL2=1
  NO_PORTAUDIO=1
  NO_PORTAUDIOCPP=1
  NO_PULSEAUDIO=1
)

JOBS=${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '2')}
make -C "$BUILD_DIR/libopenmpt" -j"$JOBS" "${MAKE_OPTIONS[@]}" bin/libopenmpt.a

CXX=${CXX:-c++}

${CXX} \
  -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$BUILD_DIR/libopenmpt" \
  -I"$ROOT/app/src/main/cpp/tracker" \
  "$ROOT/app/src/main/cpp/tracker/tracker_decoder.cpp" \
  "$ROOT/tools/tracker/tracker_smoke.cpp" \
  "$BUILD_DIR/libopenmpt/bin/libopenmpt.a" \
  -pthread -ldl -lm \
  -o "$BUILD_DIR/tracker_smoke"

"$BUILD_DIR/tracker_smoke" "$ROOT/tools/tracker/fixtures/tiny.mod"

# Host-side JNI + JVM contract tests. This deliberately avoids Gradle: it links
# the same app tracker sources against the already-built static libopenmpt and
# runs the focused JUnit tests on one worker.
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME=$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")
fi

${CXX} \
  -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -I"$BUILD_DIR/libopenmpt" \
  -I"$ROOT/app/src/main/cpp/tracker" \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  "$ROOT/app/src/main/cpp/tracker/tracker_decoder.cpp" \
  "$ROOT/app/src/main/cpp/tracker/tracker_jni.cpp" \
  "$BUILD_DIR/libopenmpt/bin/libopenmpt.a" \
  -pthread -ldl -lm \
  -o "$BUILD_DIR/libbitchord_tracker.so"

GRADLE_CACHE=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
latest_jar() {
  local group=$1 pattern=$2 jar
  jar=$(find "$GRADLE_CACHE/$group" -type f -name "$pattern" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tail -1)
  if [[ -z "$jar" ]]; then
    echo "missing Gradle cache jar: $group:$pattern" >&2
    exit 1
  fi
  printf '%s' "$jar"
}

KOTLIN_COMPILER=$(latest_jar org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.3.20.jar')
KOTLIN_STDLIB=$(latest_jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.3.20.jar')
KOTLIN_REFLECT=$(latest_jar org.jetbrains.kotlin/kotlin-reflect 'kotlin-reflect-2.1.0.jar')
KOTLIN_SCRIPT_RUNTIME=$(latest_jar org.jetbrains.kotlin/kotlin-script-runtime 'kotlin-script-runtime-2.3.20.jar')
KOTLIN_ANNOTATIONS=$(latest_jar org.jetbrains/annotations 'annotations-23.0.0.jar')
COROUTINES=$(latest_jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.11.0.jar')
TROVE=$(find "$HOME/.gradle/wrapper/dists" -type f -name 'trove4j-*.jar' | sort | tail -1)
JUNIT=$(latest_jar junit/junit 'junit-4.13.2.jar')
HAMCREST=$(latest_jar org.hamcrest/hamcrest-core 'hamcrest-core-1.3.jar')

ANDROID_JAR=
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
  if [[ -n "$sdk" ]]; then
    candidate=$(find "$sdk/platforms" -type f -name android.jar 2>/dev/null | sort -V | tail -1 || true)
    if [[ -n "$candidate" ]]; then
      ANDROID_JAR=$candidate
      break
    fi
  fi
done
if [[ -z "$ANDROID_JAR" ]]; then
  echo 'Android SDK android.jar not found' >&2
  exit 1
fi

for module in media3-common media3-extractor media3-container; do
  aar=$(find "$GRADLE_CACHE/androidx.media3/$module/1.11.0" -type f -name '*.aar' | head -1)
  unzip -p "$aar" classes.jar > "$BUILD_DIR/$module.jar"
done

# Robolectric's AndroidTestEnvironment needs androidx.test classes that ship as
# AARs, so extract their classes.jar files too instead of only collecting *.jar.
ANDROIDX_TEST_JARS=
while IFS= read -r aar; do
  name=$(basename -- "$(dirname -- "$aar")")
  unzip -p "$aar" classes.jar > "$BUILD_DIR/androidx-test-$name.jar"
  ANDROIDX_TEST_JARS+="$BUILD_DIR/androidx-test-$name.jar:"
done < <(find "$GRADLE_CACHE/androidx.test" -type f -name '*.aar' | sort)

ROBOLECTRIC_JARS=$(find "$GRADLE_CACHE/org.robolectric" -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tr '\n' ':')
COMPILE_CP="$ANDROID_JAR:$BUILD_DIR/media3-common.jar:$BUILD_DIR/media3-extractor.jar:$BUILD_DIR/media3-container.jar:$ANDROIDX_TEST_JARS$KOTLIN_STDLIB:$KOTLIN_ANNOTATIONS:$JUNIT:$HAMCREST:$ROBOLECTRIC_JARS"
KOTLINC_CP="$KOTLIN_COMPILER:$KOTLIN_STDLIB:$KOTLIN_REFLECT:$KOTLIN_SCRIPT_RUNTIME:$COROUTINES:$TROVE:$KOTLIN_ANNOTATIONS"

java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$COMPILE_CP" \
  -d "$BUILD_DIR/classes" \
  "$ROOT"/app/src/main/java/com/music/bitchord/playback/tracker/*.kt \
  "$ROOT"/app/src/test/java/com/music/bitchord/playback/tracker/*.kt

# Robolectric supplies a working android.net.Uri on the JVM. Keep the real
# android-all ahead of the SDK stub and run offline against the local .m2 copy.
# The tests pin sdk = 35, so select the matching android-all artifact rather
# than the newest one in the local repository.
ANDROID_ALL=$(find "${ROBOLECTRIC_DEP_DIR:-$HOME/.m2/repository}/org/robolectric" -type f -path '*android-all-instrumented/15-robolectric-*' -iname 'android-all*.jar' 2>/dev/null | sort -V | tail -1)
if [[ -z "$ANDROID_ALL" ]]; then
  ANDROID_ALL=$(find "$HOME/.m2/repository/org/robolectric" -type f -path '*android-all-instrumented/15-robolectric-*' -iname 'android-all*.jar' 2>/dev/null | sort -V | tail -1)
fi
if [[ -z "$ANDROID_ALL" ]]; then
  echo 'Robolectric android-all jar not found' >&2
  exit 1
fi

RUNTIME_JARS=$(find "$GRADLE_CACHE" -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tr '\n' ':')
RUNTIME_CP="$BUILD_DIR/classes:$ANDROID_ALL:$ANDROID_JAR:$BUILD_DIR/media3-common.jar:$BUILD_DIR/media3-extractor.jar:$BUILD_DIR/media3-container.jar:$ANDROIDX_TEST_JARS$KOTLIN_STDLIB:$KOTLIN_ANNOTATIONS:$JUNIT:$HAMCREST:$COROUTINES:$ROBOLECTRIC_JARS:$RUNTIME_JARS"
ARG_FILE="$BUILD_DIR/java.args"
printf -- '-cp\n%s\n' "$RUNTIME_CP" > "$ARG_FILE"

# Robolectric's offline resolver expects the directory that directly contains
# android-all-*.jar, not the Maven repository root.
ROBOLECTRIC_DEP_DIR=$(dirname -- "$ANDROID_ALL")

java @"$ARG_FILE" \
  -Djava.library.path="$BUILD_DIR" \
  -Dtracker.fixture="$ROOT/tools/tracker/fixtures/tiny.mod" \
  -Drobolectric.offline=true \
  -Drobolectric.dependency.dir="$ROBOLECTRIC_DEP_DIR" \
  org.junit.runner.JUnitCore \
  com.music.bitchord.playback.tracker.TrackerExtractorsFactoryTest \
  com.music.bitchord.playback.tracker.TrackerExtractorTest
