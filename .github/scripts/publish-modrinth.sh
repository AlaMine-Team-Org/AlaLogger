#!/usr/bin/env bash
#
# Upload one loader's jar to Modrinth as its own version.
#
# Called once per loader and Minecraft version. Two versions per game version
# rather than one with two files, because Modrinth attaches dependencies to a
# VERSION: the Fabric build requires Fabric API and the NeoForge build does not,
# and a shared version could only be wrong for one of them.
#
# The version number is <version>-mc<minecraft>+<loader> (0.1.5-mc26.3+fabric):
# Modrinth refuses a number it already has, so the game version has to be in it
# once the same mod version ships for two of them.
#
# Reads from the environment, so nothing is interpolated into this script by the
# workflow: MODRINTH_TOKEN, MODRINTH_PROJECT, VERSION, MC, JAR, and optionally
# RELEASE_TYPE (release, the default, or beta).
#
# Usage: publish-modrinth.sh <fabric|neoforge>

set -uo pipefail

LOADER="${1:-}"

case "$LOADER" in
    fabric|neoforge) ;;
    *) echo "::error::usage: publish-modrinth.sh <fabric|neoforge>"; exit 2 ;;
esac

for required in MODRINTH_TOKEN MODRINTH_PROJECT VERSION MC JAR; do
    if [ -z "${!required:-}" ]; then
        echo "::error::$required is empty"
        exit 2
    fi
done

RELEASE_TYPE="${RELEASE_TYPE:-release}"
case "$RELEASE_TYPE" in
    release|beta) ;;
    *) echo "::error::RELEASE_TYPE must be release or beta, got '$RELEASE_TYPE'"; exit 2 ;;
esac

[ -f "$JAR" ] || { echo "::error::no such jar: $JAR"; exit 1; }
[ -s NOTES.md ] || { echo "::error::NOTES.md is missing or empty"; exit 1; }

# Fabric API (P7dR8mSH) is a hard dependency of the Fabric build and irrelevant to
# the NeoForge one, which is the whole reason these are two versions.
if [ "$LOADER" = "fabric" ]; then
    DEPENDENCIES='[{"project_id": "P7dR8mSH", "dependency_type": "required"}]'
    NAME="Ala Logger $VERSION-mc$MC [Fabric]"
else
    DEPENDENCIES='[]'
    NAME="Ala Logger $VERSION-mc$MC [NeoForge]"
fi

DATA=$(jq -n \
    --arg name "$NAME" \
    --arg version_number "$VERSION-mc$MC+$LOADER" \
    --arg version_type "$RELEASE_TYPE" \
    --rawfile changelog NOTES.md \
    --arg project_id "$MODRINTH_PROJECT" \
    --arg game_version "$MC" \
    --arg loader "$LOADER" \
    --argjson dependencies "$DEPENDENCIES" \
    '{
      name: $name,
      version_number: $version_number,
      changelog: $changelog,
      dependencies: $dependencies,
      game_versions: [$game_version],
      version_type: $version_type,
      loaders: [$loader],
      featured: false,
      status: "listed",
      project_id: $project_id,
      file_parts: ["file"],
      primary_file: "file"
    }')

# curl --fail-with-body would hide the retry, so the loop is by hand: the response
# body is printed on every attempt. Modrinth says exactly what is wrong, and losing
# that costs a whole release cycle.
for attempt in 1 2 3 4 5; do
    code=$(curl -sS --connect-timeout 20 --max-time 180 \
        -o modrinth_resp.json -w '%{http_code}' -X POST \
        https://api.modrinth.com/v2/version \
        -H "Authorization: $MODRINTH_TOKEN" \
        -H "User-Agent: AlaMine-Team-Org/AlaLogger-release/1.0" \
        --form-string "data=$DATA" \
        -F "file=@$JAR")

    if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
        echo "Modrinth $VERSION-mc$MC+$LOADER ($RELEASE_TYPE) upload OK (HTTP $code)"
        exit 0
    fi

    echo "::warning::Modrinth $LOADER attempt $attempt failed with HTTP $code; body:"
    cat modrinth_resp.json || true
    [ "$attempt" -lt 5 ] && sleep 15
done

echo "::error::Modrinth $LOADER upload failed after 5 attempts (last HTTP $code)."
exit 1
