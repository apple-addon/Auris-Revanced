#!/usr/bin/env bash
# Rebuild the exact rebranded JNI host DEX archives from the supplied trees.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-/tmp/opencode/reference-host-build}
APKTOOL=${APKTOOL:-/tmp/opencode/tools/apktool.jar}
JAVA_HOME=${JAVA_HOME:-/tmp/opencode/jdk21}
PAWS_TREE=${PAWS_TREE:-/root/apks/paws_auris}
HIBY_TREE=${HIBY_TREE:-/root/apks/hibs_rebranded}
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$WORK"
mkdir -p "$WORK"
cp -a "$PAWS_TREE" "$WORK/paws"
cp -a "$HIBY_TREE" "$WORK/hiby"
mkdir -p "$WORK/paws/smali/com/auris/bridge" "$WORK/hiby/smali_classes3/com/hiby/music/sdk/bridge"
cp "$ROOT/native/reference_hosts/paws/com/auris/bridge/NativeLoader.smali" \
  "$WORK/paws/smali/com/auris/bridge/NativeLoader.smali"
cp "$ROOT/native/reference_hosts/hiby/com/hiby/music/sdk/bridge/NativeLoader.smali" \
  "$WORK/hiby/smali_classes3/com/hiby/music/sdk/bridge/NativeLoader.smali"

java -Xmx3g -jar "$APKTOOL" b -f -j 1 "$WORK/paws" -o "$WORK/paws.apk"
java -Xmx3g -jar "$APKTOOL" b -f -j 1 "$WORK/hiby" -o "$WORK/hiby.apk"

python3 - "$ROOT" "$WORK" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys
root, work = map(Path, sys.argv[1:])
for name in ("paws", "hiby"):
    output = root / "app/src/main/assets/reference" / f"{name}_host.apk"
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(work / f"{name}.apk") as source, ZipFile(output, "w", ZIP_DEFLATED, compresslevel=9) as target:
        for info in source.infolist():
            if info.filename.startswith("classes") and info.filename.endswith(".dex"):
                target.writestr(info.filename, source.read(info.filename))
        target.writestr("AndroidManifest.xml", source.read("AndroidManifest.xml"))
    print(f"{output}: {output.stat().st_size} bytes")
PY
