#!/usr/bin/env bash
# Publish MultiView jars to Modrinth — one Modrinth version per MC range.
#
# Usage:
#   ./scripts/publish-modrinth.sh <mod_version> [--dry-run]
#
# Requires:
#   - MODRINTH_TOKEN in .env (scopes: WRITE_PROJECTS / CREATE_VERSION)
#   - build/libs/multiview-<version>-mc<range>.jar built for each range (see build-version.sh)
#
# For each range it clones the metadata (name, game_versions, dependencies, loaders)
# from the project's most recent existing version of that same range, so the upload
# stays consistent with prior releases. Only the version number, changelog and file change.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
DRY_RUN="false"
[ "${2:-}" = "--dry-run" ] && DRY_RUN="true"
[ "${1:-}" = "--dry-run" ] && { DRY_RUN="true"; VERSION=""; }

if [ -z "$VERSION" ]; then
    VERSION=$(grep "^mod_version=" gradle.properties | cut -d= -f2)
fi

# Load MODRINTH_TOKEN from .env
if [ -f .env ]; then
    # shellcheck disable=SC1091
    set -a; . ./.env; set +a
fi
if [ -z "${MODRINTH_TOKEN:-}" ]; then
    echo "ERROR: MODRINTH_TOKEN not set (expected in .env)." >&2
    exit 1
fi

PROJECT_SLUG="multiview"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/libs}"

# Changelog for this release (Modrinth markdown). Edit per release.
CHANGELOG=$(cat <<'EOF'
## 0.5.0

### Added
- Stable Minecraft 26.2 support with Flashback 0.43.2.
- **Silent background auto-update.** MultiView now keeps itself — and other Zeffut Modrinth mods present in your `mods/` folder — up to date automatically. On startup it hashes the local jars, asks Modrinth for the latest build matching your Minecraft version and loader, downloads verified updates, and swaps them in at game shutdown (a detached helper finishes the swap if a jar is still locked, e.g. on Windows). It runs entirely in the background. Opt-out with `auto_update: false` in `config/multiview-telemetry.json` or `-Dautoupdate.enabled=false`; scope it with the `update_owner`, `update_all`, and `update_exclude` settings.

### Changed
- **Multi-row selection** replaces the per-replay checkboxes: left-click replay rows to build the merge set (selected rows are highlighted), double-click still opens a replay, and a single selection keeps the Flashback Open/Edit/Delete buttons working.
- Removed the first-run telemetry chat message. Telemetry stays anonymous and opt-out via `/mv telemetry off` (and `-Dmultiview.telemetry=false`), documented in the README.

### Fixed
- The **Merge** button no longer overlaps the Flashback search/sort row and resizes responsively with the window.
- The **Merge** button is correctly greyed out when the selected replays are not from the same recording moment (derived from the immutable replay file name, not the file last-modified time) and for empty (0-tick) replays.
EOF
)

# range_key : jar suffix / dist filename
RANGES=("1.21.11" "1.21.9" "26.1" "26.2")

for range in "${RANGES[@]}"; do
    jar="${ARTIFACT_DIR}/multiview-${VERSION}-mc${range}.jar"
    if [ ! -f "$jar" ]; then
        echo "ERROR: missing jar $jar — build it first (./scripts/build-version.sh ${range})." >&2
        exit 1
    fi

    echo "==> Publishing MC ${range}: $(basename "$jar")"

    # Build the version metadata JSON by cloning the latest existing version of this range.
    data_json=$(VERSION="$VERSION" RANGE="$range" CHANGELOG="$CHANGELOG" PROJECT_SLUG="$PROJECT_SLUG" python3 - <<'PY'
import json, os, urllib.request

slug = os.environ["PROJECT_SLUG"]
version = os.environ["VERSION"]
rng = os.environ["RANGE"]
changelog = os.environ["CHANGELOG"]

# Fetch existing versions to clone metadata for this range.
url = f"https://api.modrinth.com/v2/project/{slug}/version"
with urllib.request.urlopen(url) as r:
    versions = json.load(r)

# Find the most recent existing version whose number ends with +mc<range>.
suffix = f"+mc{rng}"
prior = next((v for v in versions if v["version_number"].endswith(suffix)), None)

if prior:
    name = prior["name"]
    # Replace the old mod version in the display name with the new one.
    old_ver = prior["version_number"].split("+")[0]
    name = name.replace(old_ver, version)
    game_versions = prior["game_versions"]
    deps = [{"project_id": d.get("project_id"),
             "version_id": d.get("version_id"),
             "dependency_type": d.get("dependency_type", "required")}
            for d in prior.get("dependencies", []) if d.get("project_id") or d.get("version_id")]
    loaders = prior["loaders"]
else:
    # Fallback defaults if no prior version of this range exists.
    label = {"1.21.11": "MC 1.21.11", "1.21.9": "MC 1.21.9 / 1.21.10", "26.1": "MC 26.1", "26.2": "MC 26.2"}.get(rng, f"MC {rng}")
    name = f"MultiView {version} — {label}"
    game_versions = {"1.21.11": ["1.21.11"],
                     "1.21.9": ["1.21.9", "1.21.10"],
                     "26.1": ["26.1", "26.1.1", "26.1.2"],
                     "26.2": ["26.2"]}.get(rng, [rng])
    deps = [{"project_id": "2sJTwAvJ", "dependency_type": "required"}]
    loaders = ["fabric"]

data = {
    "name": name,
    "version_number": f"{version}+mc{rng}",
    "changelog": changelog,
    "dependencies": deps,
    "game_versions": game_versions,
    "version_type": "release",
    "loaders": loaders,
    "featured": True,
    "project_id": None,  # filled below via slug -> id
    "file_parts": ["file"],
    "primary_file": "file",
}

# Resolve project id from slug.
with urllib.request.urlopen(f"https://api.modrinth.com/v2/project/{slug}") as r:
    proj = json.load(r)
data["project_id"] = proj["id"]

print(json.dumps(data))
PY
)

    if [ "$DRY_RUN" = "true" ]; then
        echo "  [dry-run] would POST:"
        echo "$data_json" | python3 -m json.tool | sed 's/^/    /'
        continue
    fi

    resp=$(curl -s -w '\n%{http_code}' -X POST "https://api.modrinth.com/v2/version" \
        -H "Authorization: ${MODRINTH_TOKEN}" \
        -F "data=${data_json};type=application/json" \
        -F "file=@${jar};type=application/java-archive")
    code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')
    if [ "$code" = "200" ] || [ "$code" = "201" ]; then
        vid=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id','?'))" 2>/dev/null || echo "?")
        echo "  OK published ${VERSION}+mc${range} (version id: ${vid})"
    else
        echo "  ERROR HTTP $code:" >&2
        echo "$body" | head -c 800 >&2; echo >&2
        exit 1
    fi
done

echo "All versions published for ${VERSION}."
