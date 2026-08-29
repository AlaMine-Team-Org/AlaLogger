#!/usr/bin/env bash
#
# Upload one loader's jar to CurseForge.
#
# Called twice per release, once per loader, as two files under the same project.
# Unlike Modrinth, CurseForge carries the loader in the numeric game-version list
# rather than in dependencies, so the two calls differ only by that list and by
# the label in the display name.
#
# Reads from the environment, so nothing is interpolated into this script by the
# workflow: CURSEFORGE_TOKEN, CURSEFORGE_PROJECT, VERSION, JAR, GAME_VERSIONS,
# LABEL.

set -uo pipefail

for required in CURSEFORGE_TOKEN CURSEFORGE_PROJECT VERSION JAR GAME_VERSIONS LABEL; do
    if [ -z "${!required:-}" ]; then
        echo "::error::$required is empty"
        exit 2
    fi
done

[ -f "$JAR" ] || { echo "::error::no such jar: $JAR"; exit 1; }
[ -s NOTES.md ] || { echo "::error::NOTES.md is missing or empty"; exit 1; }

# The environment tags (Client/Server) became mandatory in 2026-07; without them
# the upload is rejected as errorCode 1021. Do not add a Java version to this list
# either - CurseForge rejects that with errorCode 1009.
METADATA=$(jq -n \
    --rawfile changelog NOTES.md \
    --arg displayName "Ala Logger $VERSION [$LABEL]" \
    --argjson gameVersions "$GAME_VERSIONS" \
    '{
      changelog: $changelog,
      changelogType: "markdown",
      displayName: $displayName,
      gameVersions: $gameVersions,
      releaseType: "release"
    }')

for attempt in 1 2 3 4 5; do
    code=$(curl -sS --connect-timeout 20 --max-time 180 \
        -o cf_resp.json -w '%{http_code}' -X POST \
        "https://minecraft.curseforge.com/api/projects/$CURSEFORGE_PROJECT/upload-file" \
        -H "X-Api-Token: $CURSEFORGE_TOKEN" \
        --form-string "metadata=$METADATA" \
        -F "file=@$JAR")

    if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
        echo "CurseForge $LABEL upload OK (HTTP $code)"
        cat cf_resp.json
        exit 0
    fi

    echo "::warning::CurseForge $LABEL attempt $attempt failed with HTTP $code; body:"
    cat cf_resp.json || true
    [ "$attempt" -lt 5 ] && sleep 15
done

echo "::error::CurseForge $LABEL upload failed after 5 attempts (last HTTP $code)."
exit 1
