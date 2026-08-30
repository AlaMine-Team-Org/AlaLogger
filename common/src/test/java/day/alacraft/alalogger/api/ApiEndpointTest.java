package day.alacraft.alalogger.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one answer both the config and the client build on.
 *
 * <p>The reason this class exists is worth restating in a test: while the two
 * had separate copies of these rules, the config accepted a value the client
 * then threw on — from inside the mod's entrypoint, which meant a server that
 * would not start. The last two tests here are the ones that keep that shut.
 */
class ApiEndpointTest {

    @Test
    void acceptsTheAddressesPeopleActuallyConfigure() {
        assertEquals("https://alacraft.day/api/v1",
                ApiEndpoint.normalise("https://alacraft.day/api/v1").orElseThrow());
        assertEquals("http://127.0.0.1:8123/api/v1",
                ApiEndpoint.normalise("http://127.0.0.1:8123/api/v1").orElseThrow());
        assertEquals("https://logs.example.org/api/v1",
                ApiEndpoint.normalise("  https://logs.example.org/api/v1  ").orElseThrow());
    }

    @Test
    @DisplayName("trailing slashes go, because every path is appended with a leading one")
    void stripsTrailingSlashes() {
        assertEquals("https://alacraft.day/api/v1",
                ApiEndpoint.normalise("https://alacraft.day/api/v1//").orElseThrow());
    }

    @Test
    @DisplayName("a fragment would swallow every path appended to this value")
    void dropsAFragment() {
        // Paths are appended by concatenation, so "#notes" + "/logs" leaves the
        // endpoint inside the fragment, which is never transmitted: upload,
        // limits, insights and delete all became one request to the base URL,
        // and nothing anywhere said so.
        assertEquals("https://alacraft.day/api/v1",
                ApiEndpoint.normalise("https://alacraft.day/api/v1#notes").orElseThrow());
        assertEquals("https://alacraft.day/api/v1",
                ApiEndpoint.normalise("https://alacraft.day/api/v1/#notes").orElseThrow(),
                "and the slash it was hiding still goes");
    }

    @Test
    @DisplayName("a query string does the same, one degree less completely")
    void dropsAQueryString() {
        assertEquals("https://alacraft.day/api/v1",
                ApiEndpoint.normalise("https://alacraft.day/api/v1?token=abc").orElseThrow());
    }

    @Test
    @DisplayName("credentials in the address are dropped, because they are never sent anyway")
    void dropsCredentials() {
        // The JDK's HTTP client discards userinfo, so these buy nothing. What
        // they did buy was a password printed into latest.log on startup - and
        // latest.log is the file this mod uploads.
        assertEquals("https://logs.example.org/api/v1",
                ApiEndpoint.normalise("https://user:pass@logs.example.org/api/v1").orElseThrow());
        assertEquals("https://logs.example.org/api/v1",
                ApiEndpoint.normalise("https://sometoken@logs.example.org/api/v1").orElseThrow(),
                "including the bare-token shape, which the redaction rules do not match");
    }

    @Test
    void keepsAPortAndRefusesAnImpossibleOne() {
        assertEquals("http://127.0.0.1:8123/api/v1",
                ApiEndpoint.normalise("http://127.0.0.1:8123/api/v1").orElseThrow());
        assertEquals("http://[::1]:8123/api/v1",
                ApiEndpoint.normalise("http://[::1]:8123/api/v1").orElseThrow(), "IPv6");

        // java.net.URI parses the digits without judging them, so this used to
        // reach the HTTP client and surface at the first upload as an internal
        // error, when the advice is "fix apiBaseUrl".
        assertTrue(ApiEndpoint.normalise("https://alacraft.day:99999/api").isEmpty());
        assertTrue(ApiEndpoint.normalise("https://alacraft.day:0/api").isEmpty());
    }

    @Test
    void rejectsWhatCannotBeUsed() {
        assertTrue(ApiEndpoint.normalise(null).isEmpty());
        assertTrue(ApiEndpoint.normalise("   ").isEmpty(), "blank");
        assertTrue(ApiEndpoint.normalise("alacraft.day/api/v1").isEmpty(), "no scheme");
        assertTrue(ApiEndpoint.normalise("ftp://alacraft.day/api").isEmpty(), "not http(s)");
        assertTrue(ApiEndpoint.normalise("https:///api/v1").isEmpty(), "no host");
        assertTrue(ApiEndpoint.normalise("http://").isEmpty(), "nothing but a scheme");
        assertTrue(ApiEndpoint.normalise("not a url at all").isEmpty(), "not a URL");
        assertTrue(ApiEndpoint.normalise("///").isEmpty(), "slashes only");
    }

    @Test
    @DisplayName("the client refuses exactly what this rejects")
    void theClientAgrees() {
        // Both directions. A value this accepts must build a client, and a value
        // it rejects must not - otherwise the config would hand the builder
        // something the builder throws on, which is the failure this class was
        // extracted to prevent.
        assertEquals("alacraft.day",
                AlaLoggerApi.builder(ApiEndpoint.normalise("https://alacraft.day/api/v1/").orElseThrow())
                        .build().host());

        for (String rejected : new String[] {"", "alacraft.day/api/v1", "ftp://alacraft.day/api"}) {
            assertTrue(ApiEndpoint.normalise(rejected).isEmpty(), rejected);
            assertThrows(IllegalArgumentException.class, () -> AlaLoggerApi.builder(rejected), rejected);
        }
    }
}
