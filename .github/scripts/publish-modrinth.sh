#!/usr/bin/env bash
#
# Upload one loader's jar to Modrinth as its own version.
#
# Called twice per release, once per loader. Two versions rather than one with two
# files, because Modrinth attaches dependencies to a VERSION: the Fabric build
# requires Fabric API and the NeoForge build does not, and a shared version could
# only be wrong for one of them.
#
# Reads from the environment, so nothing is interpolated into this script by the
# workflow: MODRINTH_TOKEN, MODRINTH_PROJECT, VERSION, MC, JAR.
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

[ -f "$JAR" ] || { echo "::error::no such jar: $JAR"; exit 1; }
[ -s NOTES.md ] || { echo "::error::NOTES.md is missing or empty"; exit 1; }

# Fabric API (P7dR8mSH) is a hard dependency of the Fabric build and irrelevant to
# the NeoForge one, which is the whole reason these are two versions.
if [ "$LOADER" = "fabric" ]; then
    DEPENDENCIES='[{"project_id": "P7dR8mSH", "dependency_type": "required"}]'
    NAME="Ala Logger $VERSION [Fabric]"
else
    DEPENDENCIES='[]'
    NAME="Ala Logger $VERSION [NeoForge]"
fi

DATA=$(jq -n \
    --arg name "$NAME" \
    --arg version_number "$VERSION+$LOADER" \
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
      version_type: "release",
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
        echo "Modrinth $LOADER upload OK (HTTP $code)"
        exit 0
    fi

    echo "::warning::Modrinth $LOADER attempt $attempt failed with HTTP $code; body:"
    cat modrinth_resp.json || true
    [ "$attempt" -lt 5 ] && sleep 15
done

echo "::error::Modrinth $LOADER upload failed after 5 attempts (last HTTP $code)."
exit 1
