package net.nuggetmc.tplus.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.nuggetmc.tplus.TerminatorPlus;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Looks up a player's skin texture from Mojang's session API.
 *
 * <p>Spec section 7: this performs blocking HTTP and must never run on the server
 * thread. Everything here returns a future; callers resume on the server thread with
 * {@code server.execute}.
 */
public final class MojangSkins {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * A dedicated single-thread executor rather than the common ForkJoinPool: these tasks
     * block on HTTP for up to ten seconds each, and the common pool is sized for CPU work
     * and shared with the rest of the JVM. Daemon so it never holds shutdown open.
     */
    private static final Executor LOOKUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TerminatorPlus skin lookup");
        thread.setDaemon(true);
        return thread;
    });

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    /**
     * {@code unsigned=false}, as upstream had it. Signed, and it has to be.
     *
     * <p>This was briefly changed to {@code unsigned=true} on the theory that a client rejects a
     * signature it cannot validate. It does not: {@code SkinManager} logs "Profile contained
     * invalid signature for textures property" and then registers the textures anyway, and
     * authlib's {@code unpackTextures} never branches on the signature state at all. Meanwhile
     * an <b>unsigned</b> property renders nothing, with no message on either side.
     *
     * <p>So the signature being bound to the original account's profile id, which a bot's fresh
     * id never matches, costs one warning per skin in the client log and nothing else. That is
     * the trade, and it is upstream's.
     */
    private static final String SESSION_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";

    private MojangSkins() {
    }

    /**
     * Resolves {@code name} to a {value, signature} texture pair.
     *
     * @return a future of the pair, or of {@code null} when the player does not exist
     *         or Mojang cannot be reached. Never completes exceptionally.
     */
    public static CompletableFuture<String[]> fetch(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uuid = readJson(UUID_URL + name).get("id").getAsString();

                JsonObject profile = readJson(String.format(SESSION_URL, uuid));
                JsonObject textures = profile.getAsJsonArray("properties").get(0).getAsJsonObject();

                // The signature is absent from an unsigned response, and null is what
                // Property takes for "unsigned". Read defensively rather than assumed, so a
                // future switch back to signed textures does not need this line changed.
                JsonElement signature = textures.get("signature");

                return new String[]{
                        textures.get("value").getAsString(),
                        signature == null || signature.isJsonNull() ? null : signature.getAsString()
                };
            } catch (Exception e) {
                TerminatorPlus.LOGGER.warn("Could not fetch skin for '{}': {}", name, e.toString());
                return null;
            }
        }, LOOKUP_EXECUTOR);
    }

    /**
     * A JSON body from {@code url}, or an exception naming what came back instead.
     *
     * <p>Two things this does that the first draft did not, both found when a skin lookup
     * failed in play-testing with nothing but a Gson parse error to go on. It checks the status
     * code, so a rate limit or an outage reads as "HTTP 429" rather than "malformed JSON at
     * line 1 column 12". And it reads the body as UTF-8 explicitly: {@code InputStreamReader}
     * with no charset uses the platform default, which on a Windows JVM is not UTF-8, and a
     * skin payload is base64 in an ASCII envelope only until a name is not.
     */
    private static JsonObject readJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url
                    + " -- " + abbreviate(response.body()));
        }

        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Unparseable response from " + url
                    + " -- " + abbreviate(response.body()), e);
        }
    }

    /** Enough of a body to recognise it in a log line, and no more. */
    private static String abbreviate(String body) {
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
