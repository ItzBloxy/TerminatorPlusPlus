package net.nuggetmc.tplus.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.nuggetmc.tplus.TerminatorPlus;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
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

                return new String[]{
                        textures.get("value").getAsString(),
                        textures.get("signature").getAsString()
                };
            } catch (Exception e) {
                TerminatorPlus.LOGGER.warn("Could not fetch skin for '{}': {}", name, e.toString());
                return null;
            }
        });
    }

    private static JsonObject readJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStreamReader reader = new InputStreamReader(response.body())) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
