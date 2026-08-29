package day.alacraft.alalogger.redact;

import day.alacraft.alalogger.i18n.Messages;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns "what was stripped" into a sentence for the player.
 *
 * <p>Lives here, away from any Minecraft type, because it is the one line that
 * has to be exactly right: the whole trust argument of this mod is that secrets
 * are removed before the file leaves the machine, and this is where that claim
 * is either made concrete ("2 IP addresses, 1 account name") or left as a
 * promise. Being plain logic, it can be tested rather than eyeballed.
 */
public final class RedactionSummaries {

    private RedactionSummaries() {
    }

    /**
     * A comma-separated description of what was removed, in the player's
     * language, or an empty string when nothing matched.
     *
     * <p>Empty means silence: a log with nothing sensitive in it should not
     * produce a line saying so. Announcing "removed nothing" on every upload
     * would train people to skip the line on the day it matters.
     *
     * @param summary rule key to number of replacements, as produced by
     *                {@link RedactionResult#matchedSummary()}
     */
    public static String describe(String language, Map<String, Integer> summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }

        return summary.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + Messages.get(language, "redaction." + entry.getKey()))
                .collect(Collectors.joining(", "));
    }
}
