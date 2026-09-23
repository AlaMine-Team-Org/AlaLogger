#!/usr/bin/env bash
#
# Upload one loader's jar to CurseForge.
#
# Called once per loader and Minecraft version, as separate files under the same
# project. Unlike Modrinth, CurseForge carries the loader and the game version in
# the numeric game-version list rather than in dependencies, so the calls differ
# only by that list and by the display name - which names the game version too,
# or one mod version for two game versions shows up as two identical names on
# the files page.
#
# Reads from the environment, so nothing is interpolated into this script by the
# workflow: CURSEFORGE_TOKEN, CURSEFORGE_PROJECT, VERSION, MC, JAR,
# GAME_VERSIONS, LABEL, and optionally RELEASE_TYPE (release, the default, or
# beta).

set -uo pipefail

for required in CURSEFORGE_TOKEN CURSEFORGE_PROJECT VERSION MC JAR GAME_VERSIONS LABEL; do
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

# The environment tags (Client/Server) became mandatory in 2026-07; without them
# the upload is rejected as errorCode 1021. Do not add a Java version to this list
# either - CurseForge rejects that with errorCode 1009.
METADATA=$(jq -n \
    --rawfile changelog NOTES.md \
    --arg displayName "Ala Logger $VERSION-mc$MC [$LABEL]" \
    --arg releaseType "$RELEASE_TYPE" \
    --argjson gameVersions "$GAME_VERSIONS" \
    '{
      changelog: $changelog,
      changelogType: "markdown",
      displayName: $displayName,
      gameVersions: $gameVersions,
      releaseType: $releaseType
    }')

for attempt in 1 2 3 4 5; do
    code=$(curl -sS --connect-timeout 20 --max-time 180 \
        -o cf_resp.json -w '%{http_code}' -X POST \
        "https://minecraft.curseforge.com/api/projects/$CURSEFORGE_PROJECT/upload-file" \
        -H "X-Api-Token: $CURSEFORGE_TOKEN" \
        --form-string "metadata=$METADATA" \
        -F "file=@$JAR")

    if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
        echo "CurseForge $LABEL $MC ($RELEASE_TYPE) upload OK (HTTP $code)"
        cat cf_resp.json
        exit 0
    fi

    echo "::warning::CurseForge $LABEL attempt $attempt failed with HTTP $code; body:"
    cat cf_resp.json || true
    [ "$attempt" -lt 5 ] && sleep 15
done

echo "::error::CurseForge $LABEL upload failed after 5 attempts (last HTTP $code)."
exit 1
