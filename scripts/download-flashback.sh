#!/usr/bin/env bash
# Download and verify the Flashback jar declared by a target version config.
# Usage: ./scripts/download-flashback.sh <minecraft-version>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?Usage: $0 <minecraft-version>}"
PROPS="$ROOT/versions/$VERSION.properties"

if [[ ! -f "$PROPS" ]]; then
    echo "No config for version '$VERSION': $PROPS" >&2
    exit 1
fi

property() {
    local key="$1"
    python3 - "$PROPS" "$key" <<'PY'
import sys
path, key = sys.argv[1:]
properties = {}
for line in open(path, encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        name, value = line.split("=", 1)
        properties[name] = value
if key not in properties:
    raise SystemExit(f"Missing required property: {key}")
print(properties[key].replace("${flashback_version}", properties.get("flashback_version", "")))
PY
}

filename="$(property flashback_filename_pattern)"
url="$(property flashback_download_url)"
expected_sha512="$(property flashback_sha512)"
project_id="$(property flashback_modrinth_project_id)"
version_id="$(property flashback_modrinth_version_id)"
destination="$ROOT/libs/$filename"
expected_url="https://cdn.modrinth.com/data/$project_id/versions/$version_id/$filename"

if [[ "$url" != "$expected_url" ]]; then
    echo "Flashback URL does not match pinned Modrinth project/version IDs in $PROPS" >&2
    exit 1
fi

if [[ ! "$expected_sha512" =~ ^[[:xdigit:]]{128}$ ]]; then
    echo "Invalid flashback_sha512 in $PROPS; expected 128 hexadecimal characters" >&2
    exit 1
fi

mkdir -p "$(dirname "$destination")"
if [[ -f "$destination" ]] && printf '%s  %s\n' "$expected_sha512" "$destination" | sha512sum --check --status; then
    echo "Flashback dependency already verified: $destination"
    exit 0
fi

rm -f "$destination"
temporary="$(mktemp "${destination}.download.XXXXXX")"
trap 'rm -f "$temporary"' EXIT
curl --fail --location --retry 3 --retry-all-errors --output "$temporary" "$url"
printf '%s  %s\n' "$expected_sha512" "$temporary" | sha512sum --check
mv "$temporary" "$destination"
trap - EXIT
echo "Downloaded and verified Flashback: $destination"
