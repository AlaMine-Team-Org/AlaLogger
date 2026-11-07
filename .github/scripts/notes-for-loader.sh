#!/usr/bin/env bash
#
# The release notes one loader's file gets.
#
# The notes are written once, as the body of the release tag, and they are the
# changelog of every file the release publishes. Most of what changed is the
# same for every loader and is written without naming one. What is true for
# one loader only - the loader was added, a requirement moved - goes in a block
# that only that loader's files show:
#
#   <!-- only: forge -->
#   - **Forge.** Ala Logger now runs on Forge.
#   <!-- end -->
#
# A block may name several loaders (<!-- only: fabric neoforge -->). The marker
# lines are HTML comments, so a page that shows the notes unfiltered - the one
# GitHub Release that carries every jar - shows every block and no markers.
#
# Usage: notes-for-loader.sh <fabric|neoforge|forge> [notes-file]   (stdout)
#        notes-for-loader.sh --self-test
# Exit:  0 done, 2 unusable input (unknown loader, unbalanced or nested block).

set -uo pipefail

LOADERS="fabric neoforge forge"

render() {  # $1 loader, stdin notes -> stdout notes for that loader
    awk -v loader="$1" -v known="$LOADERS" '
        BEGIN { n = split(known, k, " "); for (i = 1; i <= n; i++) ok[k[i]] = 1 }
        /^[[:space:]]*<!--[[:space:]]*only:/ {
            if (inblock) { print "nested block at line " NR > "/dev/stderr"; bad = 1; exit 2 }
            line = $0
            sub(/^[[:space:]]*<!--[[:space:]]*only:[[:space:]]*/, "", line)
            sub(/[[:space:]]*-->[[:space:]]*$/, "", line)
            m = split(line, names, /[[:space:]]+/)
            if (m == 0) { print "empty loader list at line " NR > "/dev/stderr"; bad = 1; exit 2 }
            keep = 0
            for (i = 1; i <= m; i++) {
                if (!(names[i] in ok)) { print "unknown loader \"" names[i] "\" at line " NR > "/dev/stderr"; bad = 1; exit 2 }
                if (names[i] == loader) keep = 1
            }
            inblock = 1
            next
        }
        /^[[:space:]]*<!--[[:space:]]*end[[:space:]]*-->[[:space:]]*$/ {
            if (!inblock) { print "end without a block at line " NR > "/dev/stderr"; bad = 1; exit 2 }
            inblock = 0
            next
        }
        {
            if (inblock && !keep) next
            # Removed blocks leave blank lines behind; keep at most one in a row.
            if ($0 ~ /^[[:space:]]*$/) { if (blank) next; blank = 1 } else blank = 0
            print
        }
        END {
            if (bad) exit 2
            if (inblock) { print "block not closed at the end of the notes" > "/dev/stderr"; exit 2 }
        }'
}

self_test() {
    local sample out fail=0
    # Tokens that are not substrings of each other: "Forge" is part of
    # "NeoForge", and a sample built on those words tested nothing.
    sample='## 1.0 - Title

### Added

<!-- only: forge -->
- LINE-FORGE
<!-- end -->
- LINE-SHARED

<!-- only: fabric neoforge -->
| ROW-FABRIC-NEOFORGE |
<!-- end -->
<!-- only: forge -->
| ROW-FORGE |
<!-- end -->'

    out="$(printf '%s\n' "$sample" | render forge)"
    case "$out" in *"LINE-FORGE"*"LINE-SHARED"*"ROW-FORGE"*) ;; *) echo "self-test: forge lost its own lines" >&2; fail=1 ;; esac
    case "$out" in *"ROW-FABRIC-NEOFORGE"*|*"<!--"*) echo "self-test: forge got another loader's line or a marker" >&2; fail=1 ;; esac

    out="$(printf '%s\n' "$sample" | render fabric)"
    case "$out" in *"LINE-SHARED"*"ROW-FABRIC-NEOFORGE"*) ;; *) echo "self-test: fabric lost its own lines" >&2; fail=1 ;; esac
    case "$out" in *"LINE-FORGE"*|*"ROW-FORGE"*|*"<!--"*) echo "self-test: fabric got forge lines or a marker" >&2; fail=1 ;; esac

    # Broken markup has to be refused, not rendered.
    printf '<!-- only: forge -->\n- x\n' | render forge >/dev/null 2>&1 && { echo "self-test: an unclosed block was accepted" >&2; fail=1; }
    printf '<!-- only: quilt -->\n- x\n<!-- end -->\n' | render forge >/dev/null 2>&1 && { echo "self-test: an unknown loader was accepted" >&2; fail=1; }
    printf '<!-- only: forge -->\n<!-- only: fabric -->\n<!-- end -->\n<!-- end -->\n' | render forge >/dev/null 2>&1 && { echo "self-test: a nested block was accepted" >&2; fail=1; }
    printf -- '- x\n<!-- end -->\n' | render forge >/dev/null 2>&1 && { echo "self-test: a stray end was accepted" >&2; fail=1; }

    return $fail
}

if [ "${1:-}" = "--self-test" ]; then
    self_test && { echo "notes-for-loader: self-test passed"; exit 0; }
    exit 2
fi

LOADER="${1:-}"
case " $LOADERS " in
    *" $LOADER "*) ;;
    *) echo "usage: $(basename "$0") <${LOADERS// /|}> [notes-file]" >&2; exit 2 ;;
esac

self_test || { echo "notes-for-loader: refusing to filter with a filter that fails its own self-test" >&2; exit 2; }

if [ -n "${2:-}" ]; then
    render "$LOADER" < "$2"
else
    render "$LOADER"
fi
