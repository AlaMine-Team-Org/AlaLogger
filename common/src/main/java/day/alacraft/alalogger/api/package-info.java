/**
 * Client for the AlaCraft Log Checker API — the half of the mod that talks to
 * {@code https://alacraft.day/api/v1}.
 *
 * <p>Three rules hold everywhere in this package, and the rest of the mod is
 * written against them:
 *
 * <ul>
 *   <li><b>Nothing here knows about Minecraft.</b> Uploading a log is HTTP and
 *       JSON. Keeping the game out means this can be tested against a throwaway
 *       {@code HttpServer} in milliseconds instead of a game-test harness.
 *   <li><b>Nothing blocks.</b> Every call returns a {@link
 *       java.util.concurrent.CompletableFuture} and even the gzip pass runs off
 *       the caller's thread. A server that is already dying must not also freeze
 *       because someone asked to share the log about it.
 *   <li><b>Nothing is null.</b> A value that may be absent is an {@link
 *       java.util.Optional}; a list that may be empty is an empty list. The one
 *       exception is documented on the member that allows it.
 * </ul>
 *
 * <p>Failures arrive as a single {@link day.alacraft.alalogger.api.ApiException}
 * carrying an {@link day.alacraft.alalogger.api.ApiError}, whose {@link
 * day.alacraft.alalogger.api.ApiErrorCode} tells apart the cases a player needs
 * told apart — rate limited, log too big, token wrong, site unreachable. That
 * distinction is the point of this client: one "An error occurred" for all four
 * tells the person staring at their broken server nothing at all.
 */
package day.alacraft.alalogger.api;
