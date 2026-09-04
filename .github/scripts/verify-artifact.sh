#!/usr/bin/env bash
#
# Look inside the jar before anyone downloads it.
#
# These are the same checks the release workflow runs, available locally so a
# broken artifact is found while it is still cheap to fix - before the tag, not
# after three platforms have a copy of it.
#
# Usage: verify-artifact.sh <version> [tree] [loader]
#        tree defaults to the current directory; loader to "fabric".
#        The jar is expected at <tree>/<loader>/build/libs/alalogger-<loader>-<mc>-<version>.jar
# Exit:  0 good, 1 something is wrong, 2 unusable input.

set -uo pipefail

VERSION="${1:-}"
TREE="${2:-.}"
LOADER="${3:-fabric}"

if [ -z "$VERSION" ]; then
    echo "usage: $(basename "$0") <version> [tree] [loader]" >&2
    exit 2
fi

case "$LOADER" in
    fabric|neoforge) ;;
    *) echo "verify: unknown loader '$LOADER' (fabric or neoforge)" >&2; exit 2 ;;
esac

cd "$TREE" || { echo "verify: cannot enter $TREE" >&2; exit 2; }

command -v unzip >/dev/null 2>&1 || { echo "verify: unzip is not on PATH" >&2; exit 2; }

MC="$(grep -E '^minecraft_version=' gradle.properties | head -1 | cut -d= -f2- | tr -d '\r[:space:]')"
[ -n "$MC" ] || { echo "verify: no minecraft_version in gradle.properties" >&2; exit 2; }

JAR="$LOADER/build/libs/alalogger-$LOADER-$MC-$VERSION.jar"

FAIL=0
bad() { echo "FAIL - $1"; FAIL=1; }
ok()  { echo "  ok   $1"; }

if [ ! -f "$JAR" ]; then
    echo "FAIL - the jar is missing: $JAR" >&2
    echo "       what is in $LOADER/build/libs:" >&2
    ls -la "$LOADER/build/libs/" >&2 2>/dev/null || echo "       (no such directory)" >&2
    exit 1
fi

SIZE="$(wc -c < "$JAR" | tr -d ' ')"
if [ "$SIZE" -lt 10000 ]; then
    bad "the jar is $SIZE bytes - that is not a built mod"
else
    ok "$JAR ($((SIZE / 1024)) KiB)"
fi
# The metadata file differs per loader, and checking the RIGHT one is the point:
# a Fabric jar carrying neoforge.mods.toml, or the reverse, means the wrong
# artifact was picked up somewhere - and the file name alone would not show it.
if [ "$LOADER" = "fabric" ]; then
    META_NAME="fabric.mod.json"
    WRONG_NAME="neoforge.mods.toml"
else
    META_NAME="META-INF/neoforge.mods.toml"
    WRONG_NAME="fabric.mod.json"
fi

META="$(unzip -p "$JAR" "$META_NAME" 2>/dev/null || true)"

if unzip -l "$JAR" 2>/dev/null | grep -q "$WRONG_NAME"; then
    bad "the jar carries $WRONG_NAME - this is not a $LOADER artifact"
fi

if [ -z "$META" ]; then
    bad "$META_NAME is not in the jar"
else
    # The version inside the metadata is what the game and the launchers read.
    # A jar whose file name says one version and whose metadata says another is
    # the kind of thing nobody notices until a bug report quotes the wrong one.
    #
    # One pattern for both formats. JSON writes  "version": "x"  and TOML writes
    # version = "x", so the key may be followed by a closing quote before the
    # separator - which the first version of this pattern missed, reporting a
    # perfectly good Fabric jar as broken.
    if printf '%s' "$META" | grep -qE "version\"?[[:space:]]*[:=][[:space:]]*\"$VERSION\""; then
        ok "$META_NAME carries version $VERSION"
    else
        bad "$META_NAME does not carry version $VERSION"
        printf '%s\n' "$META" | grep -n 'version' | sed 's/^/       /'
    fi

    # Mojibake check. The description now comes from mod_description.txt in UTF-8,
    # which is what removed this failure mode - but gradle.properties is still
    # read as ISO-8859-1 and still supplies mod_name and the author, so a
    # non-ASCII character there would still arrive double-encoded, and the first
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

for lang in en_us ru_ru uk_ua de_de fr_fr es_es ja_jp pt_br zh_cn; do
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
