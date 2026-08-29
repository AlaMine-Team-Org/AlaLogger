#!/usr/bin/env bash
#
# Look inside the jar before anyone downloads it.
#
# These are the same checks the release workflow runs, available locally so a
# broken artifact is found while it is still cheap to fix - before the tag, not
# after three platforms have a copy of it.
#
# Usage: verify-artifact.sh <version> [tree]
#        tree defaults to the current directory; the jar is expected at
#        <tree>/fabric/build/libs/alalogger-fabric-<mc>-<version>.jar
# Exit:  0 good, 1 something is wrong, 2 unusable input.

set -uo pipefail

VERSION="${1:-}"
TREE="${2:-.}"

if [ -z "$VERSION" ]; then
    echo "usage: $(basename "$0") <version> [tree]" >&2
    exit 2
fi

cd "$TREE" || { echo "verify: cannot enter $TREE" >&2; exit 2; }

command -v unzip >/dev/null 2>&1 || { echo "verify: unzip is not on PATH" >&2; exit 2; }

MC="$(grep -E '^minecraft_version=' gradle.properties | head -1 | cut -d= -f2- | tr -d '\r[:space:]')"
[ -n "$MC" ] || { echo "verify: no minecraft_version in gradle.properties" >&2; exit 2; }

JAR="fabric/build/libs/alalogger-fabric-$MC-$VERSION.jar"

FAIL=0
bad() { echo "FAIL - $1"; FAIL=1; }
ok()  { echo "  ok   $1"; }

if [ ! -f "$JAR" ]; then
    echo "FAIL - the jar is missing: $JAR" >&2
    echo "       what is in fabric/build/libs:" >&2
    ls -la fabric/build/libs/ >&2 2>/dev/null || echo "       (no such directory)" >&2
    exit 1
fi

SIZE="$(wc -c < "$JAR" | tr -d ' ')"
if [ "$SIZE" -lt 10000 ]; then
    bad "the jar is $SIZE bytes - that is not a built mod"
else
    ok "$JAR ($((SIZE / 1024)) KiB)"
fi

META="$(unzip -p "$JAR" fabric.mod.json 2>/dev/null || true)"
if [ -z "$META" ]; then
    bad "fabric.mod.json is not in the jar"
else
    # The version inside the metadata is what the game and the launchers read.
    # A jar whose file name says one version and whose metadata says another is
    # the kind of thing nobody notices until a bug report quotes the wrong one.
    if printf '%s' "$META" | grep -q "\"version\"[[:space:]]*:[[:space:]]*\"$VERSION\""; then
        ok "fabric.mod.json carries version $VERSION"
    else
        bad "fabric.mod.json does not carry version $VERSION"
        printf '%s\n' "$META" | grep -n '"version"' | sed 's/^/       /'
    fi

    # Mojibake check. gradle.properties is read as ISO-8859-1, so a non-ASCII
    # character in the description arrives here double-encoded - and the first
    # place it is ever seen is a store page.
    if printf '%s' "$META" | grep -qP '[\xc3][\x80-\xbf][\xc2-\xc3][\x80-\xbf]'; then
        bad "the metadata looks double-encoded - check gradle.properties for non-ASCII"
    else
        ok "the metadata is not double-encoded"
    fi
fi

LIST="$(unzip -l "$JAR" 2>/dev/null || true)"

# The icon is what the launcher shows in the mod list. Its absence is invisible
# in testing and obvious to every player.
if printf '%s' "$LIST" | grep -q 'assets/alalogger/icon-512.png'; then
    ok "the icon is in the jar"
else
    bad "the icon is missing from the jar"
fi

for lang in en_us ru_ru uk_ua de_de fr_fr es_es ja_jp; do
    if printf '%s' "$LIST" | grep -q "assets/alalogger/lang/$lang.json"; then
        ok "translation $lang"
    else
        bad "translation $lang is missing from the jar"
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    echo "The artifact is what it claims to be."
    exit 0
fi

echo "The artifact is not publishable."
exit 1
