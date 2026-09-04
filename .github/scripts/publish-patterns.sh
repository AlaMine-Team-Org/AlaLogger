#!/usr/bin/env bash
# =============================================================================
# What "clean" means, in one place.
#
# Two gates need the same answer and ask it at different moments:
#
#   check-publishable.sh  reads the private repository through the allowlist,
#                         before anything is copied out of it;
#   scan-clean.sh         reads the public mirror as a whole, after the copy and
#                         again in the public repository's CI.
#
# While each carried its own patterns they drifted, and a rule added to one was
# simply absent from the other. Source this file instead. It defines patterns and
# the self-tests that prove they still work; it runs nothing on its own.
#
# Usage:  . "$(dirname "$0")/publish-patterns.sh"
# =============================================================================

# Byte-oriented matching below needs a predictable locale. Git Bash often leaves
# LANG unset, CI usually sets a UTF-8 one, and the difference is not academic:
# the character-class form of this check, [\x{0400}-\x{04FF}], aborts with
# "character value in \x{} is too large" under a non-UTF-8 locale. That error
# went to /dev/null and the gate reported a clean tree for months.
export LC_ALL=C

# The Cyrillic block U+0400-U+04FF encodes in UTF-8 as a lead byte D0-D3 followed
# by a continuation byte 80-BF. Matching bytes takes the locale out of the answer
# entirely.
PATTERN_CYRILLIC='[\xd0-\xd3][\x80-\xbf]'

# Names of the tooling, and the phrases that describe having used it. Assembled
# from fragments so that this file - which is published, because the public CI
# runs it - does not itself contain the words it exists to keep out.
PATTERN_TRACES="$(printf '%s|' \
    "c""laude" \
    "a""nthropic" \
    "co""pilot" \
    "c""odex" \
    "chat""gpt" \
    "open""ai" \
    "cursor""\\.so" \
    "co-authored""-by" \
    "generated"" with" \
    "written by an"" ai" \
    "artificial"" intelligence" \
    "machine"" learning" \
    "language"" model" \
    "\\bg""pt\\b" \
    "\\bl""lm\\b" \
    | sed 's/|$//')"

# The bare acronym, matched case-SENSITIVELY and as a whole word. Folding case
# would hit ordinary words in the languages this mod ships - "ai" is a real word
# in several of them - and a gate that cries wolf is a gate people switch off.
PATTERN_BARE_ACRONYM='\bA''I\b'

# Credential shapes. Deliberately specific: a token has a recognisable prefix and
# length, and matching anything looser would drown the report.
PATTERN_CREDENTIALS='PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]+|AKIA[0-9A-Z]{16}'

# Paths that point at this particular machine. Note what is NOT here: a bare
# "C:\Users\". This mod masks exactly that shape, so its redaction rules and
# their fixtures are full of it, and matching it would report the feature as a
# defect. What must never ship is a path naming these folders.
PATTERN_LOCAL_PATHS='_Clode|023_Ala_Logger_studio|0231_AlaLogger_Public_studio|AppData.Local.Temp'

# Filenames that carry secrets regardless of their content.
PATTERN_BAD_FILENAMES='(^|/)\.env($|\.)|\.pem$|\.key$|(^|/)id_rsa|_secret|credentials'

# Work that is not finished. Shipped code only - a note in an internal document
# is a note; the same note in published source is an unfinished edge.
PATTERN_UNFINISHED='\b(TODO|FIXME|XXX|HACK)\b'

# Other people's projects.
#
# This mod is an independent tool built for alacraft.day, and nothing it ships
# should read as a comparison to, or a debt owed to, somebody else's service.
# Naming a competitor in a code comment is how it ends up on a store page: the
# comment gets quoted into a README, the README into a description, and the
# description is read by every player who opens the listing.
#
# The rule is about what we ship, not about what we know: internal research under
# docs/research/ studies other tools by name on purpose, and none of it is
# published - the allowlist in sync_paths.txt does not carry it.
PATTERN_COMPETITORS='mclo\.gs|mclogs|aternos|crash.assistant|paste\.gg|hastebin|pastebin'

# Internal paths. The allowlist in sync_paths.txt cannot carry these across, so a
# hit means somebody added the file to the mirror by hand.
PATTERN_DENIED_PATHS='^(\.c[l]aude|\.agents|research)/|^docs/(PLAN|DEV-RUN)\.md$|^docs/(research|publishing|tools)/|(^|/)C[L]AUDE\.md$'

# ---------------------------------------------------------------- exemptions

# Files whose Cyrillic is the product, not a leak.
#
# The in-game translations are obvious. The named tests are the same class: this
# mod is translated into nine languages and reads log files byte by byte, so a
# test has to feed it Cyrillic - a redaction summary in Russian, a multi-byte
# line to truncate, a placeholder a translator got wrong.
#
# They are listed one by one on purpose. Exempting the whole test tree would let
# a new test written in Russian through unnoticed; this way it fails the gate,
# and adding it here is a deliberate line somebody has to write.
PATTERN_L1_EXEMPT='/lang/[a-z]{2}_[a-z]{2}\.json$|common/src/test/java/day/alacraft/alalogger/(i18n/MessagesTest|logs/LogReaderTest|redact/RedactionSummariesTest|redact/RedactorTest)\.java$'

# Files exempt from the credential scan, and from that scan only.
#
# The redaction rules are this mod's whole point. A rule that masks an AWS key
# has to name the shape of an AWS key, and its test has to feed it a realistic
# one. Scanning those files for credential shapes reports the feature as the
# defect. Everything else still covers them, and a real credential belongs in
# none of these paths anyway.
PATTERN_L3_EXEMPT='/redact/'

# --------------------------------------------------------------- self-checks

# A gate must not fail open. Every scan below swallows grep's exit status,
# because through xargs a broken pattern and an empty result look identical - so
# a typo would match nothing, print nothing, and hand back a green verdict over a
# dirty tree. These probes push known-dirty and known-clean text through the real
# matcher and demand the right answer from both, before any real scanning starts.
#
# Call once, near the top of a gate. Exits 2 on failure; it never returns false.
patterns_selftest() {
    local probe tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN

    if ! printf '\xd0\xa0' | grep -qP "$PATTERN_CYRILLIC"; then
        echo "patterns: FATAL - grep -P cannot match Cyrillic bytes here." >&2
        echo "patterns: aborting rather than reporting a tree as clean." >&2
        exit 2
    fi
    if printf 'plain english\n' | grep -qP "$PATTERN_CYRILLIC"; then
        echo "patterns: FATAL - the Cyrillic pattern matches plain English." >&2
        exit 2
    fi

    # Built from the same fragments as the pattern, so the probe cannot drift
    # away from what it is probing.
    printf '%s\n' "trailer: ""co-authored""-by someone" > "$tmp/dirty"
    printf '%s\n' "an ordinary sentence about log files" > "$tmp/clean"

    probe="$(grep -icE "$PATTERN_TRACES" "$tmp/dirty" || true)"
    [ "$probe" = "1" ] || {
        echo "patterns: FATAL - the trace pattern does not flag a known marker." >&2
        exit 2
    }
    probe="$(grep -icE "$PATTERN_TRACES" "$tmp/clean" || true)"
    [ "$probe" = "0" ] || {
        echo "patterns: FATAL - the trace pattern flags an ordinary sentence." >&2
        exit 2
    }

    printf '%s\n' "this uses A""I to rank problems" > "$tmp/dirty"
    printf '%s\n' "import net.minecraft.world.entity.ai.Goal;" > "$tmp/clean"

    probe="$(grep -cE "$PATTERN_BARE_ACRONYM" "$tmp/dirty" || true)"
    [ "$probe" = "1" ] || {
        echo "patterns: FATAL - the acronym pattern does not flag the bare acronym." >&2
        exit 2
    }
    probe="$(grep -cE "$PATTERN_BARE_ACRONYM" "$tmp/clean" || true)"
    [ "$probe" = "0" ] || {
        echo "patterns: FATAL - the acronym pattern flags a lower-case word." >&2
        exit 2
    }
}
