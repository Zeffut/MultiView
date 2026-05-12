#!/usr/bin/env bash
# Run MergeIntegrationTest against every configured MC version.
#
# For each versions/<mc>.properties:
#   1. swap gradle.properties to that version
#   2. run ./gradlew test --tests MergeIntegrationTest
#   3. record verdict (PASS/FAIL/SKIP) + duration
#   4. restore original gradle.properties on exit
#
# Prints a summary table at the end. Exits non-zero if any version FAILs.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSIONS_DIR="$ROOT/versions"
GRADLE_PROPS="$ROOT/gradle.properties"
ORIG_BACKUP="$ROOT/.gradle.properties.test-orig"
RESULTS_DIR="$ROOT/build/multiversion-test"

mkdir -p "$RESULTS_DIR"

cleanup() {
    if [ -f "$ORIG_BACKUP" ]; then
        cp "$ORIG_BACKUP" "$GRADLE_PROPS"
        rm "$ORIG_BACKUP"
    fi
}
trap cleanup EXIT

cp "$GRADLE_PROPS" "$ORIG_BACKUP"

swap_props() {
    local props="$1"
    python3 - "$GRADLE_PROPS" "$props" <<'PY'
import sys
gp_path, vp_path = sys.argv[1], sys.argv[2]
gp = {}
preserved = []
for line in open(gp_path):
    s = line.strip()
    if not s or s.startswith("#"):
        preserved.append(line.rstrip("\n")); continue
    if "=" in s:
        k, v = s.split("=", 1); gp[k.strip()] = v.strip()
for line in open(vp_path):
    s = line.strip()
    if not s or s.startswith("#"): continue
    if "=" in s:
        k, v = s.split("=", 1); gp[k.strip()] = v.strip()
with open(gp_path, "w") as f:
    for line in preserved: f.write(line + "\n")
    f.write("\n")
    for k, v in gp.items(): f.write(f"{k}={v}\n")
PY
}

# Discover versions
versions=()
for f in "$VERSIONS_DIR"/*.properties; do
    [ -e "$f" ] || continue
    versions+=("$(basename "$f" .properties)")
done

if [ ${#versions[@]} -eq 0 ]; then
    echo "No versions/*.properties files found." >&2
    exit 2
fi

statuses=()
times=()
all_stats=()

echo "==> Testing ${#versions[@]} MC version(s): ${versions[*]}"

for v in "${versions[@]}"; do
    props="$VERSIONS_DIR/$v.properties"
    log="$RESULTS_DIR/$v.log"
    echo ""
    echo "================================================================"
    echo "  MC $v"
    echo "================================================================"

    swap_props "$props"
    # Wipe stale test report so we never read stats from a prior run.
    rm -f "$ROOT/build/test-results/test/TEST-fr.zeffut.multiview.merge.MergeIntegrationTest.xml"

    java_version=$(grep "^java_version=" "$GRADLE_PROPS" 2>/dev/null | cut -d= -f2)
    java_home=""
    if [ "$java_version" = "25" ] && [ -d "$HOME/.local/jdks" ]; then
        java_home=$(ls -d "$HOME/.local/jdks"/jdk*25*/Contents/Home 2>/dev/null | head -1 || true)
    fi

    start=$(date +%s)
    # cleanTest forces re-run; without it Gradle caches test outputs across property swaps
    # (since it doesn't track gradle.properties as an input).
    if [ -n "$java_home" ]; then
        echo "  [info] Using JAVA_HOME=$java_home"
        JAVA_HOME="$java_home" "$ROOT/gradlew" cleanTest test --tests MergeIntegrationTest \
            -Dorg.gradle.daemon=false > "$log" 2>&1 || true
    else
        "$ROOT/gradlew" cleanTest test --tests MergeIntegrationTest \
            -Dorg.gradle.daemon=false > "$log" 2>&1 || true
    fi
    end=$(date +%s)
    dur=$((end - start))

    # Verdict logic:
    #   FAIL   = BUILD FAILED, OR test report shows failures
    #   PASS   = BUILD SUCCESSFUL and tests=1 with 0 failures/errors in report XML
    #   SKIP   = no test ran (file missing, @EnabledIf returned false)
    verdict="FAIL"
    if grep -q "BUILD SUCCESSFUL" "$log"; then
        report_xml="$ROOT/build/test-results/test/TEST-fr.zeffut.multiview.merge.MergeIntegrationTest.xml"
        if [ -f "$report_xml" ]; then
            if grep -q 'tests="1"' "$report_xml" && grep -q 'failures="0"' "$report_xml" && grep -q 'errors="0"' "$report_xml"; then
                verdict="PASS"
            elif grep -q 'tests="0"' "$report_xml" || grep -q 'skipped="1"' "$report_xml"; then
                verdict="SKIP"
            else
                verdict="FAIL"
            fi
        else
            verdict="SKIP"
        fi
    fi

    # Stats come from the test report XML system-out (where stdout is captured).
    stats=""
    if [ -f "$report_xml" ]; then
        stats=$(grep -oE "\[REPORT\] (merged total ticks|entities merged uuid|blocks LWW overwrites|globals deduped)=[^]]*" "$report_xml" \
            | sed 's/\[REPORT\] //' \
            | sed 's/blocks LWW overwrites/lwwOverwrites/; s/entities merged uuid/entUuid/; s/merged total ticks/ticks/; s/globals deduped/deduped/' \
            | tr '\n' ' ' || true)
    fi
    # Save full report path for the summary footer.
    if [ -f "$report_xml" ]; then
        cp "$report_xml" "$RESULTS_DIR/$v-report.xml" 2>/dev/null || true
    fi

    statuses+=("$verdict")
    times+=("$dur")
    all_stats+=("$stats")

    echo "  [verdict] $verdict in ${dur}s — log: $log"
done

# Summary
echo ""
echo "================================================================"
echo "                       SUMMARY"
echo "================================================================"
printf "%-12s %-7s %-8s %s\n" "MC version" "Verdict" "Time" "Stats"
echo "----------------------------------------------------------------"
overall_pass=true
i=0
for v in "${versions[@]}"; do
    s="${statuses[$i]}"
    t="${times[$i]}s"
    st="${all_stats[$i]:-(no stats)}"
    printf "%-12s %-7s %-8s %s\n" "$v" "$s" "$t" "$st"
    if [ "$s" = "FAIL" ]; then
        overall_pass=false
    fi
    i=$((i + 1))
done

if $overall_pass; then
    echo ""
    echo "All versions PASSED."
    exit 0
else
    echo ""
    echo "Some versions FAILED — see logs in $RESULTS_DIR/"
    exit 1
fi
