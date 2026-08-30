package day.alacraft.alalogger.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import day.alacraft.alalogger.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * One problem the site recognised in the log, ready to print.
 *
 * <p>The text arrives already translated into the language asked for at upload
 * time. That is deliberate on the site's side — it stores a code and parameters,
 * never a rendered sentence — and it is why the mod does not need its own
 * catalogue of Minecraft failure messages in seven languages.
 *
 * @param code      stable identifier, e.g. {@code out_of_memory}. Safe to branch on; safe to
 *                  use as a translation key if the mod ever wants its own wording.
 * @param severity  {@code error}, {@code warning} or {@code info}. A plain string, not an enum:
 *                  a severity added later must not break parsing of the finding it belongs to.
 * @param message   what is wrong, in the requested language.
 * @param hint      why it happens. May be empty — the site drops a line whose placeholders it
 *                  could not fill rather than printing a half-written sentence.
 * @param line      1-based line in the stored log. Absent when the finding is about the log as
 *                  a whole rather than one place in it.
 * @param count     how many times this was found; occurrences are collapsed into one insight.
 * @param solutions what to do about it, most useful first.
 */
public record Insight(
        String code,
        String severity,
        String message,
        String hint,
        OptionalInt line,
        int count,
        List<Solution> solutions) {

    /**
     * A suggested fix.
     *
     * <p>Text only. The site keeps a finding as a code plus parameters and
     * renders the sentence per viewer, and a "read more" link is part of that
     * rendering rather than of the finding — so no URL crosses the API. This
     * record carried an {@code Optional<String> url} field for a while that
     * nothing on either side ever filled.
     *
     * @param text what to do.
     */
    public record Solution(String text) {

        public Solution {
            text = ChatText.plain(text);
        }
    }

    /**
     * Everything a player will read is cleaned of formatting escapes here rather
     * than at the point it is printed. This is the seam the text crosses on its
     * way in from the network, and it is the only one every platform shares — a
     * Paper build would print these sentences through Adventure and would
     * otherwise have to remember to do this again.
     */
    public Insight {
        code = code == null ? "" : code;
        severity = severity == null ? "info" : severity;
        message = ChatText.plain(message);
        hint = ChatText.plain(hint);
        line = line == null ? OptionalInt.empty() : line;
        solutions = solutions == null ? List.of() : List.copyOf(solutions);
    }

    /** True for findings that explain a failure, as opposed to a warning about one. */
    public boolean isError() {
        return "error".equals(severity);
    }

    static List<Insight> listFrom(Iterable<JsonElement> elements) {
        List<Insight> insights = new ArrayList<>();

        for (JsonElement element : elements) {
            JsonObject json = Json.asObject(element);

            if (json != null) {
                insights.add(from(json));
            }
        }

        return List.copyOf(insights);
    }

    static Insight from(JsonObject json) {
        List<Solution> solutions = new ArrayList<>();

        for (JsonElement element : Json.array(json, "solutions")) {
            JsonObject solution = Json.asObject(element);

            if (solution != null) {
                solutions.add(new Solution(Json.string(solution, "text")));
            }
        }

        return new Insight(
                Json.string(json, "code"),
                Json.string(json, "severity"),
                Json.string(json, "message"),
                Json.string(json, "hint"),
                Json.optionalInt(json, "line"),
                // A finding always happened at least once; a missing count is a
                // wire quirk, not a reason to print "0 occurrences".
                Math.max(1, Json.intValue(json, "count", 1)),
                solutions);
    }
}
