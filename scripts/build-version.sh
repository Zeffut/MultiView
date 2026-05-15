#!/usr/bin/env bash
# Build MultiView for a specific Minecraft version.
#
# Usage:
#   ./scripts/build-version.sh <version>            # build for that version
#   ./scripts/build-version.sh --all                # build for every version
#   ./scripts/build-version.sh --list               # list known versions
#
# Each version's properties live in versions/<version>.properties.
# The script swaps the project root gradle.properties in place, runs the
# Gradle build, and copies the produced jar into build/libs/multiview-<modver>-mc<MC>.jar.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSIONS_DIR="$ROOT/versions"
GRADLE_PROPS="$ROOT/gradle.properties"
ORIG_BACKUP="$ROOT/.gradle.properties.original"

list_versions() {
    ls -1 "$VERSIONS_DIR"/*.properties 2>/dev/null \
        | sed -e 's|.*/||' -e 's|\.properties$||' \
        | sort -V
}

restore_original() {
    if [ -f "$ORIG_BACKUP" ]; then
        cp "$ORIG_BACKUP" "$GRADLE_PROPS"
        rm "$ORIG_BACKUP"
    fi
}
trap restore_original EXIT

build_one() {
    local version="$1"
    local props="$VERSIONS_DIR/$version.properties"
    if [ ! -f "$props" ]; then
        echo "X No config for version '$version' (looked in $props)" >&2
        return 1
    fi

    echo "=== Building MultiView for MC $version ==="

    # Backup current gradle.properties on first call
    if [ ! -f "$ORIG_BACKUP" ]; then
        cp "$GRADLE_PROPS" "$ORIG_BACKUP"
    fi

    # Merge version properties into gradle.properties
    python3 - "$GRADLE_PROPS" "$props" <<'PY'
import sys
gp_path, vp_path = sys.argv[1], sys.argv[2]
gp = {}
preserved_lines = []
for line in open(gp_path):
    s = line.strip()
    if not s or s.startswith("#"):
        preserved_lines.append(line.rstrip("\n"))
        continue
    if "=" in s:
        k, v = s.split("=", 1)
        gp[k.strip()] = v.strip()

for line in open(vp_path):
    s = line.strip()
    if not s or s.startswith("#"): continue
    if "=" in s:
        k, v = s.split("=", 1)
        gp[k.strip()] = v.strip()

with open(gp_path, "w") as f:
    for line in preserved_lines:
        f.write(line + "\n")
    f.write("\n")
    for k, v in gp.items():
        f.write(f"{k}={v}\n")
PY

    # Build — propagate failure explicitly even though set -e is active.
    if ! (cd "$ROOT" && ./gradlew clean build); then
        echo "ERROR: gradlew build failed for MC ${version}" >&2
        exit 1
    fi

    # Rename produced jar with version suffix
    local modver
    modver=$(grep "^mod_version=" "$GRADLE_PROPS" | cut -d= -f2)
    local src="$ROOT/build/libs/multiview-${modver}.jar"
    local dest="$ROOT/build/libs/multiview-${modver}-mc${version}.jar"
    if [ -f "$src" ] && [ "$src" != "$dest" ]; then
        cp "$src" "$dest"
        echo "OK Built -> $dest"
    fi
}

case "${1:-}" in
    --list)
        echo "Available versions:"
        list_versions | sed 's/^/  /'
        ;;
    --all)
        for v in $(list_versions); do
            build_one "$v"
        done
        ;;
    "")
        echo "Usage: $0 <version> | --all | --list"
        echo
        echo "Available versions:"
        list_versions | sed 's/^/  /'
        exit 1
        ;;
    *)
        build_one "$1"
        ;;
esac
