#!/usr/bin/env bash
# =============================================================================
# Cleanliness gate for the PUBLIC mirror of Ala Logger.
#
# This scans a whole working tree and reports every violation as file:line. Its
# companion, check-publishable.sh, scans the PRIVATE repository through the
# allowlist in sync_paths.txt - before anything is copied. The two are not
# redundant: the allowlist check answers "is what we are about to send clean?",
# and this one answers "is what is actually in the public repository clean?".
# Only the second one sees a file somebody added to the mirror by hand.
#
# It runs in two places, identically:
#   1. locally, from the publish skill, before the release commit;
#   2. in the public repository's CI (.github/workflows/guard.yml), on every
#      push - the net under a hand-made push that skipped the skill.
#
# Layers:
#   L1  Cyrillic            the public repository is English-only.
#   L2  tooling traces      no marker of the tooling used while writing it.
#   L3  secrets             tokens, keys and paths from a real machine.
#   L4  denylisted paths    internal documents that must never be published.
#
# Usage: scan-clean.sh [tree]      (default: the current directory)
# Exit:  0 clean, 1 findings, 2 the scanner itself is unusable.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=publish-patterns.sh
. "$HERE/publish-patterns.sh"

patterns_selftest

ROOT="${1:-.}"
cd "$ROOT" || { echo "scan: cannot enter $ROOT" >&2; exit 2; }

# ------------------------------------------------------------------ file list

# Ask git, so .gitignore is honoured for free: build/ and .gradle/ in a mirror
# that has been built in cannot trip the gate, and the exclusion list here can
# never drift from the ignore file as new build directories appear.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    mapfile -t ALL < <(git ls-files --cached --others --exclude-standard)
else
    mapfile -t ALL < <(find . -type d \( -name .git -o -name build -o -name .gradle \) \
        -prune -o -type f -print | sed 's|^\./||')
fi

if [ "${#ALL[@]}" -eq 0 ]; then
    echo "scan: the tree lists no files - refusing to report an empty tree as clean" >&2
    exit 2
fi

mapfile -t FILES < <(printf '%s\n' "${ALL[@]}" \
    | grep -viE '\.(png|jpg|jpeg|webp|gif|ico|jar|zip|ogg|wav|class|woff2?|ttf)$' || true)

# These two files carry every pattern they search for. Scanning them would report
# the scanner as the violation.
mapfile -t FILES < <(printf '%s\n' "${FILES[@]}" \
    | grep -vE '(^|/)(scan-clean\.sh|publish-patterns\.sh|guard\.yml)$' || true)

FAIL=0

# ------------------------------------------------------------------ machinery

# scan <label> <grep-flags> <pattern> [list-var]
scan() {
    local label="$1" flags="$2" pattern="$3" listvar="${4:-FILES}"
    local -n list="$listvar"
    local hits

    [ "${#list[@]}" -eq 0 ] && return 0

    hits="$(printf '%s\0' "${list[@]}" | xargs -0 grep -HnI $flags -e "$pattern" 2>/dev/null || true)"

    if [ -n "$hits" ]; then
        printf 'FAIL - %s\n' "$label"
        printf '%s\n' "$hits" | sed 's/^/   /'
        FAIL=1
    fi
}

# scan_names <label> <pattern>   - matches the path itself, not the contents.
scan_names() {
    local label="$1" pattern="$2" hits
    hits="$(printf '%s\n' "${ALL[@]}" | grep -iE "$pattern" || true)"
    if [ -n "$hits" ]; then
        printf 'FAIL - %s\n' "$label"
        printf '%s\n' "$hits" | sed 's/^/   /'
        FAIL=1
    fi
}

# --------------------------------------------------------------------- layers

mapfile -t L1_FILES < <(printf '%s\n' "${FILES[@]}" | grep -vE "$PATTERN_L1_EXEMPT" || true)
scan "L1 Cyrillic (the public repository is English-only)" "-P" "$PATTERN_CYRILLIC" L1_FILES

scan "L2 tooling named in a published file" "-iE" "$PATTERN_TRACES"
scan "L2 bare acronym (read the line before deciding)" "-E" "$PATTERN_BARE_ACRONYM"
scan "L2 another project named in a published file" "-iE" "$PATTERN_COMPETITORS"

mapfile -t L3_FILES < <(printf '%s\n' "${FILES[@]}" | grep -vE "$PATTERN_L3_EXEMPT" || true)
scan "L3 credential in a published file" "-E" "$PATTERN_CREDENTIALS" L3_FILES

scan "L3 path from the development machine" "-E" "$PATTERN_LOCAL_PATHS"
scan_names "L3 a filename that should never be published" "$PATTERN_BAD_FILENAMES"
scan_names "L4 an internal path reached the public tree" "$PATTERN_DENIED_PATHS"

# ------------------------------------------------------------------- verdict

echo
if [ "$FAIL" -eq 0 ]; then
    echo "Clean - ${#FILES[@]} text files scanned, nothing found."
fi
exit "$FAIL"
