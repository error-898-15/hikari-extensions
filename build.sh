#!/usr/bin/env bash
set -euo pipefail

KOTLINC_DIR="./kotlinc"
DEPS_DIR="./deps"
NATIVE_CS3_DIR="./native-cs3"

mkdir -p "$DEPS_DIR" "$NATIVE_CS3_DIR" build

fetch() {
  local out="$DEPS_DIR/$1" url="$2"
  [ -f "$out" ] || curl -fsSL -o "$out" "$url"
}

echo "fetching compile deps..."
fetch "json-20231013.jar" "https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar"
fetch "kotlinx-coroutines-core-jvm-1.9.0.jar" "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar"
fetch "okhttp-4.12.0.jar" "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
fetch "okio-jvm-3.6.0.jar" "https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
fetch "kotlinx-serialization-core-jvm-1.7.1.jar" "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.1/kotlinx-serialization-core-jvm-1.7.1.jar"
fetch "kotlinx-serialization-json-jvm-1.7.1.jar" "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.1/kotlinx-serialization-json-jvm-1.7.1.jar"
fetch "android-4.1.1.4.jar" "https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar"
fetch "cloudstream3.jar" "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/deps/cloudstream3.jar"
fetch "r8-8.3.37.jar" "https://repo1.maven.org/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar"

CP="$DEPS_DIR/json-20231013.jar:$DEPS_DIR/kotlinx-coroutines-core-jvm-1.9.0.jar:$DEPS_DIR/okhttp-4.12.0.jar:$DEPS_DIR/okio-jvm-3.6.0.jar:$DEPS_DIR/kotlinx-serialization-core-jvm-1.7.1.jar:$DEPS_DIR/kotlinx-serialization-json-jvm-1.7.1.jar"
EXTRA_CP="$DEPS_DIR/cloudstream3.jar:$DEPS_DIR/android-4.1.1.4.jar"

if [ ! -x "$KOTLINC_DIR/bin/kotlinc" ]; then
  echo "fetching kotlinc 2.4.10..."
  curl -fsSL -o kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v2.4.10/kotlin-compiler-2.4.10.zip
  unzip -q kotlinc.zip
  chmod +x "$KOTLINC_DIR/bin/"* || true
fi

rm -rf build
mkdir -p build/sdk-out build/ext-out build/dex-out build/pkg

echo "compiling sdk..."
"$KOTLINC_DIR/bin/kotlinc" -jvm-default=disable -cp "$CP:$DEPS_DIR/android-4.1.1.4.jar" -d build/sdk-out \
  sdk/HikariProvider.kt sdk/HikariNet.kt stubs/HttpStub.kt stubs/WebViewResolverStub.kt stubs/HikariAppStub.kt
jar cf build/sdk.jar -C build/sdk-out .

BUILT=""
for dir in */; do
  [ -f "$dir/manifest.json" ] || continue
  name="${dir%/}"
  echo "building $name"
  rm -rf build/ext-out build/dex-out build/pkg
  mkdir -p build/ext-out build/dex-out build/pkg

  "$KOTLINC_DIR/bin/kotlinc" -jvm-default=disable -cp "build/sdk.jar:$EXTRA_CP:$CP" -d build/ext-out \
    "$dir"src/com/hikari/ext/providers/*.kt
  jar cf "build/$name.jar" -C build/ext-out .
  cp "build/$name.jar" "$name.jar"

  java -cp "$DEPS_DIR/r8-8.3.37.jar" com.android.tools.r8.D8 --release \
    --lib "$DEPS_DIR/android-4.1.1.4.jar" \
    --classpath build/sdk.jar \
    --classpath "$DEPS_DIR/cloudstream3.jar" \
    --classpath "$DEPS_DIR/android-4.1.1.4.jar" \
    --classpath "$DEPS_DIR/json-20231013.jar" \
    --classpath "$DEPS_DIR/kotlinx-coroutines-core-jvm-1.9.0.jar" \
    --classpath "$DEPS_DIR/okhttp-4.12.0.jar" \
    --classpath "$DEPS_DIR/okio-jvm-3.6.0.jar" \
    --classpath "$DEPS_DIR/kotlinx-serialization-core-jvm-1.7.1.jar" \
    --classpath "$DEPS_DIR/kotlinx-serialization-json-jvm-1.7.1.jar" \
    --output build/dex-out "build/$name.jar"

  cp build/dex-out/classes.dex build/pkg/classes.dex
  cp "$dir/manifest.json" build/pkg/manifest.json
  [ -f "$dir/icon.png" ] && cp "$dir/icon.png" build/pkg/icon.png

  if [ -f "$name/bridge-cs3.conf" ] && [ -f "$name/bridge-sources.txt" ]; then
    urlencode() {
      local s="$1" enc="" c h
      while [ -n "$s" ]; do
        c="${s:0:1}"
        case "$c" in
          [a-zA-Z0-9._~-]) enc+="$c" ;;
          *) printf -v h '%%%02X' "'$c"; enc+="$h" ;;
        esac
        s="${s:1}"
      done
      echo "$enc"
    }

    . "$name/bridge-cs3.conf"
    mkdir -p "build/pkg/cs3/$bridge_subdir"
    while IFS=$'\t' read -r upstream packaged; do
      [ -z "$upstream" ] && continue
      packaged="${packaged:-$upstream}"
      if curl -fsSL --retry 2 --retry-delay 1 -o "build/pkg/cs3/$bridge_subdir/$packaged" \
        "https://raw.githubusercontent.com/$bridge_repo/builds/$(urlencode "$upstream")"; then
        cp "build/pkg/cs3/$bridge_subdir/$packaged" "$NATIVE_CS3_DIR/${name}-${packaged}"
      else
        echo "Warning: skipped $upstream (not found upstream)"
        rm -f "build/pkg/cs3/$bridge_subdir/$packaged"
      fi
    done < "$name/bridge-sources.txt"
  fi

  jar cf "$name.hiki" -C build/pkg .
  echo "built $name.hiki"
  BUILT="$BUILT $name"
done

python3 - <<PY
import json, glob, hashlib, os

built = "${BUILT}".strip().split()
plugins = []

for name in built:
    hiki_path = f"{name}.hiki"
    if not os.path.exists(hiki_path):
        continue
    with open(f"{name}/manifest.json") as f:
        m = json.load(f)
    with open(hiki_path, "rb") as f:
        h = hashlib.sha256(f.read()).hexdigest()

    plugins.append({
        "id": m.get("id", name),
        "name": m.get("name", name),
        "version": m.get("version", 1),
        "description": m.get("description", ""),
        "authors": m.get("authors", []),
        "iconUrl": m.get("iconUrl"),
        "file": f"{name}.hiki",
        "hash": h,
        "type": m.get("type", "provider"),
        "language": m.get("language", "multi")
    })

repo = {
    "name": "Hikari Extensions Repository",
    "description": "Extensions repository for Hikari",
    "manifestVersion": 1,
    "plugins": plugins
}

with open("repo.json", "w") as f:
    json.dump(repo, f, indent=2)

with open("repo-desktop.json", "w") as f:
    json.dump(repo, f, indent=2)

print(f"Generated repo.json with {len(plugins)} extensions.")
PY
