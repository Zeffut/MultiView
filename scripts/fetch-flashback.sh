#!/usr/bin/env bash
# Download Flashback jar variants for every MC version we target.
# Reads each versions/<mc>.properties to know which Flashback build to grab.
#
# Usage:
#   ./scripts/fetch-flashback.sh           # download all
#   ./scripts/fetch-flashback.sh <mc>      # one version
#   ./scripts/fetch-flashback.sh --list    # list which jars are present

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/libs"
VERSIONS_DIR="$ROOT/versions"
mkdir -p "$LIBS"

fetch_for_mc() {
    local mc="$1"
    local props="$VERSIONS_DIR/$mc.properties"
    if [ ! -f "$props" ]; then
        echo "X No config for MC $mc" >&2
        return 1
    fi
    local fb_version
    fb_version=$(grep "^flashback_version=" "$props" | cut -d= -f2)
    if [ -z "$fb_version" ]; then
        echo "X $mc: flashback_version not set" >&2
        return 1
    fi
    # The MC version inside the Flashback jar filename matches the build's
    # minecraft_version (not always the same as the directory name — e.g. the
    # 1.21.9 properties file actually targets MC 1.21.10).
    local mc_target
    mc_target=$(grep "^minecraft_version=" "$props" | cut -d= -f2)
    local dest="$LIBS/Flashback-${fb_version}-for-MC${mc_target}.jar"

    if [ -f "$dest" ]; then
        echo "= Already present: $(basename "$dest")"
        return 0
    fi

    # Find the matching Modrinth version entry whose game_versions contains
    # our MC target.
    echo "-> Fetching Flashback $fb_version for MC $mc_target …"
    local url
    url=$(curl -fsS "https://api.modrinth.com/v2/project/flashback/version" \
        | python3 -c "
import json, sys
versions = json.load(sys.stdin)
target_fb = '$fb_version'
target_mc = '$mc_target'
for v in versions:
    if v['version_number'] == target_fb and target_mc in v['game_versions']:
        for f in v['files']:
            if f.get('primary'):
                print(f['url']); sys.exit(0)
        print(v['files'][0]['url']); sys.exit(0)
sys.exit(1)
")
    if [ -z "${url:-}" ]; then
        echo "X Could not find Flashback $fb_version for MC $mc_target on Modrinth" >&2
        return 1
    fi
    curl -fsSL -o "$dest" "$url"
    echo "OK Saved $(basename "$dest")"
}

case "${1:-}" in
    --list)
        echo "Flashback jars present in libs/:"
        ls -1 "$LIBS"/Flashback-*.jar 2>/dev/null | sed 's|.*/|  |' || echo "  (none)"
        ;;
    "")
        for props in "$VERSIONS_DIR"/*.properties; do
            mc=$(basename "$props" .properties)
            fetch_for_mc "$mc" || true
        done
        ;;
    *)
        fetch_for_mc "$1"
        ;;
esac
