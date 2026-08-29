package day.alacraft.alalogger.redact;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of running a log through the redaction pipeline.
 *
 * @param content        the text that is safe to send over the network
 * @param summary        rule key to number of replacements, in rule order; every
 *                       rule is present, including the ones that found nothing
 * @param truncatedBytes the log was longer than the byte limit and was cut
 * @param truncatedLines the log had more lines than the line limit and was cut
 * @param lines          line count of the final content
 */
public record RedactionResult(
        String content,
        Map<String, Integer> summary,
        boolean truncatedBytes,
        boolean truncatedLines,
        int lines) {

    public RedactionResult {
        // Copied through LinkedHashMap rather than Map.copyOf: the summary is
        // read back in rule order to build the chat notice, and Map.copyOf
        // deliberately randomises iteration order between JVM runs.
        summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    /**
     * Rules that actually matched, for the "removed …" notice.
     *
     * <p>Zero-count rules are dropped so the chat message never reads "0 IP
     * addresses removed". Rule order is preserved, so two uploads of the same
     * log describe themselves identically.
     */
    public Map<String, Integer> matchedSummary() {
        Map<String, Integer> matched = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : summary.entrySet()) {
            if (entry.getValue() > 0) {
                matched.put(entry.getKey(), entry.getValue());
            }
        }
        return matched;
    }

    public int totalRedactions() {
        int total = 0;
        for (int count : summary.values()) {
            total += count;
        }
        return total;
    }

    /** True when the log carried nothing sensitive — worth saying out loud. */
    public boolean isClean() {
        return totalRedactions() == 0;
    }
}
