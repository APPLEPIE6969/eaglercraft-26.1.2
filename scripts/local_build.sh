#!/usr/bin/env bash
# Local replica of the CI build pipeline (ci.yml "Build with Gradle" job).
# Restores sources/libs from Mojang's piston-meta when missing, applies the
# TeaVM jar patches, builds the DFU + teavm-patches jars, compiles the
# bundle and applies patch_default_methods.py. Output: a deployable
# public/classes.js.
#
# Usage: scripts/local_build.sh [--refresh-public]
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME_LOCAL=/home/z/my-project/tools/jdk26
export JAVA_HOME="$JAVA_HOME_LOCAL"

# ── 1. Minecraft client jar ─────────────────────────────────────────────
if [ ! -s minecraft-26.1.2.jar ]; then
  echo "[local-build] downloading Minecraft 26.1.2 client JAR from Mojang..."
  VERSION_URL=$(curl -sL "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" | python3 -c "
import json,sys
data=json.load(sys.stdin)
for v in data.get('versions',[]):
  if v.get('id')=='26.1.2':
    print(v.get('url','')); break
")
  CLIENT_URL=$(curl -sL "$VERSION_URL" | python3 -c "
import json,sys
print(json.load(sys.stdin).get('downloads',{}).get('client',{}).get('url',''))")
  curl -sL -o minecraft-26.1.2.jar "$CLIENT_URL"
fi
echo "[local-build] client jar: $(stat -c%s minecraft-26.1.2.jar) bytes"

# ── 2. Dependency libraries ─────────────────────────────────────────────
mkdir -p sources/libs
if [ ! -s sources/libs/gson-2.13.2.jar ]; then
  echo "[local-build] downloading MC dependency libraries..."
  cp minecraft-26.1.2.jar sources/libs/minecraft-26.1.2.jar
  python3 - <<'EOF'
import urllib.request, json, os, time
with urllib.request.urlopen('https://piston-meta.mojang.com/v1/packages/978e6c0dd077d0f8d5d53887454928f1d3687ba8/26.1.2.json') as r:
    data = json.loads(r.read())
libs_to_download = [
  'com.mojang:logging', 'com.mojang:brigadier', 'com.mojang:datafixerupper',
  'com.mojang:authlib', 'com.mojang:blocklist', 'com.mojang:patchy',
  'com.mojang:text2speech', 'com.mojang:jtracy',
  'com.google.code.gson:gson', 'com.google.guava:guava', 'com.google.guava:failureaccess',
  'org.apache.commons:commons-lang3', 'org.apache.commons:commons-compress',
  'commons-io:commons-io', 'commons-codec:commons-codec',
  'it.unimi.dsi:fastutil',
  'org.apache.logging.log4j:log4j-api', 'org.apache.logging.log4j:log4j-core',
  'org.jspecify:jspecify',
  'io.netty:netty-buffer', 'io.netty:netty-common', 'io.netty:netty-transport',
  'io.netty:netty-codec-base', 'io.netty:netty-codec-compression', 'io.netty:netty-handler',
  'net.sf.jopt-simple:jopt-simple'
]
for lib in data.get('libraries', []):
    name = lib.get('name', '')
    if 'natives-' in name:
        continue
    if not any(name.startswith(p) for p in libs_to_download):
        continue
    artifact = lib.get('downloads', {}).get('artifact', {})
    url = artifact.get('url', '')
    if url:
        filename = url.split('/')[-1]
        if os.path.exists(f'sources/libs/{filename}'):
            continue
        print(f'  {filename}...')
        for attempt in range(5):
            try:
                urllib.request.urlretrieve(url, f'sources/libs/{filename}')
                break
            except Exception as e:
                if attempt >= 4:
                    raise
                time.sleep(5)
print('  done')
EOF
fi

# ── 3. TeaVM jar patches (pristine from Maven Central, patched locally) ──
# Persistent locations: the sandbox wipes /tmp and ~/.gradle between
# sessions, so the pristine downloads and the done-marker live outside
# both - re-running after a wipe must not re-fetch from Maven Central
# (which rate-limits this sandbox IP after a few killed runs).
PRISTINE_DIR=/home/z/my-project/tools/pristine
PRISTINE_MARKER=/home/z/my-project/tools/.teavm_patched_marker
mkdir -p "$PRISTINE_DIR"
patch_teavm_artifact() {
  local group_path="$1" artifact="$2" version="$3" script="$4"
  local cache_jar
  cache_jar=$(find "$HOME/.gradle/caches/modules-2" -path "*${group_path}/${artifact}/${version}/*" -name "${artifact}-${version}.jar" -not -name "*sources*" -type f 2>/dev/null | head -1)
  local pristine="$PRISTINE_DIR/${artifact}.jar"
  local url_group
  url_group=$(echo "$group_path" | tr '.' '/')
  if [ ! -s "$pristine" ]; then
    curl -sfL -o "$pristine" "https://repo1.maven.org/maven2/${url_group}/${artifact}/${version}/${artifact}-${version}.jar"
  fi
  python3 "$script" "$pristine" "$pristine.patched"
  if [ -n "$cache_jar" ]; then
    cp "$pristine.patched" "$cache_jar"
  else
    local sha1 seed_dir
    sha1=$(sha1sum "$pristine" | cut -d' ' -f1)
    seed_dir="$HOME/.gradle/caches/modules-2/files-2.1/${group_path}/${artifact}/${version}/${sha1}"
    mkdir -p "$seed_dir"
    cp "$pristine.patched" "$seed_dir/${artifact}-${version}.jar"
  fi
  echo "[local-build] patched $artifact"
}
if [ ! -s "$PRISTINE_MARKER" ]; then
  ./gradlew :sources:dependencies --configuration teavmClasspath --no-daemon >/dev/null 2>&1 || true
  patch_teavm_artifact org.teavm teavm-core 0.15.0 scripts/patch_teavm_jar.py
  patch_teavm_artifact org.teavm teavm-classlib 0.15.0 scripts/patch_classlib_jar.py
  patch_teavm_artifact org.teavm teavm-relocated-libs-asm 0.15.0 scripts/patch_teavm_asm.py
  touch "$PRISTINE_MARKER"
fi

# ── 4. DFU patches ──────────────────────────────────────────────────────
DFU_JAR=$(find sources/libs -name "datafixerupper-*.jar" -not -name "*sources*" -type f 2>/dev/null | head -1)
if [ -n "$DFU_JAR" ]; then
  python3 scripts/patch_dfu_final.py "$DFU_JAR" sources/teavm/patch/dfu-classes
fi

# ── 5. teavm-patches.jar ────────────────────────────────────────────────
mkdir -p sources/teavm/patch/classes sources/libs
( cd sources/teavm/patch
  "$JAVA_HOME/bin/javac" --release 25 --patch-module java.base="$(pwd)" -d classes \
    $(find java -name "*.java" -type f)
  "$JAVA_HOME/bin/jar" cf ../../libs/teavm-patches.jar -C classes . )

# ── 6. TeaVM bundle ─────────────────────────────────────────────────────
echo "[local-build] generating classes.js (this takes a few minutes)..."
./gradlew --no-daemon :sources:generateJavaScript 2>&1 | tail -3

# ── 7. Default-method patch ─────────────────────────────────────────────
python3 scripts/patch_default_methods.py \
  sources/build/generated/teavm/js/classes.js \
  sources/build/generated/teavm/js/classes.js

echo "[local-build] bundle ready: sources/build/generated/teavm/js/classes.js ($(stat -c%s sources/build/generated/teavm/js/classes.js) bytes)"

# ── 8. Optionally refresh public/ ───────────────────────────────────────
if [ "${1:-}" = "--refresh-public" ]; then
  cp sources/build/generated/teavm/js/classes.js public/classes.js
  cp sources/build/generated/teavm/js/classes.js.map public/classes.js.map 2>/dev/null || true
  echo "[local-build] public/classes.js refreshed"
fi
