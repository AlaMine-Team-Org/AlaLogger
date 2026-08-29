package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionPattern;
import day.alacraft.alalogger.redact.RegexRule;

import java.util.List;

/**
 * The world seed, which a Minecraft crash report prints in full.
 *
 * <p>{@code -- System Details --} carries {@code World Seed: 7026191857309640518}
 * whenever the crash came from an integrated server — confirmed on real 26.x
 * crash reports. On a server that one number is enough to regenerate the world
 * offline and walk straight to every base, stronghold and buried structure on it;
 * seed-reversing tools do the rest.
 *
 * <p>Weighed against that: a seed has never helped anyone read a stack trace.
 * There is no diagnostic loss to trade off, so the rule is unconditional — no
 * exemption for single-player worlds, because the log cannot tell us whether
 * other people play on that world.
 */
public final class SeedRule extends RegexRule {

    private static final List<RedactionPattern> PATTERNS = List.of(
            // Seeds are signed 64-bit, so the minus sign is part of the value and
            // has to be inside the match — otherwise a negative seed comes out as
            // "World Seed: -********" and the sign alone halves the search space.
            RedactionPattern.ofIgnoreCase(
                    "World Seed:\\s*-?[0-9]+",
                    "World Seed: ********",
                    "World Seed:"));

    @Override
    public String key() {
        return "seed";
    }

    @Override
    protected List<RedactionPattern> patterns() {
        return PATTERNS;
    }
}
