package day.alacraft.alalogger.redact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This line is the mod's central claim made visible, so it is worth pinning: a
 * player who is told "2 IP addresses, 1 account name were removed" can believe
 * the rest of what the mod says about itself.
 */
class RedactionSummariesTest {

    private Map<String, Integer> summary(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    void counts_are_named_in_the_players_language() {
        String english = RedactionSummaries.describe("en_us", summary("ip", 2, "path", 1));
        String russian = RedactionSummaries.describe("ru_ru", summary("ip", 2, "path", 1));

        assertEquals("2 IP addresses, 1 account names in paths", english);
        assertTrue(russian.contains("2 IP-адресов"), russian);
        assertFalse(russian.contains("redaction."), "the key must never leak into chat: " + russian);
    }

    /** Nothing removed means nothing said — not "removed nothing". */
    @Test
    void an_empty_summary_produces_no_line() {
        assertEquals("", RedactionSummaries.describe("en_us", Map.of()));
        assertEquals("", RedactionSummaries.describe("en_us", null));
    }

    /** Rules that ran but matched nothing must not be listed. */
    @Test
    void zero_counts_are_left_out() {
        assertEquals("1 IP addresses", RedactionSummaries.describe("en_us", summary("ip", 1, "email", 0)));
    }

    /** Every rule the redactor can report has a label in every language. */
    @Test
    void every_rule_key_is_translated_everywhere() {
        for (RedactionRule rule : Redactor.defaultRules()) {
            for (String language : day.alacraft.alalogger.i18n.Messages.SUPPORTED) {
                String line = RedactionSummaries.describe(language, summary(rule.key(), 1));

                assertFalse(line.contains("redaction." + rule.key()),
                        "missing label for rule '" + rule.key() + "' in " + language);
                assertFalse(line.isBlank(), "empty label for rule '" + rule.key() + "' in " + language);
            }
        }
    }

    /** Order follows the rules, so the same log always reads the same way. */
    @Test
    void the_order_is_stable() {
        String first = RedactionSummaries.describe("en_us", summary("ip", 1, "token", 2, "path", 3));
        String second = RedactionSummaries.describe("en_us", summary("ip", 1, "token", 2, "path", 3));

        assertEquals(first, second);
        assertTrue(first.indexOf("IP") < first.indexOf("session"), first);
    }
}
