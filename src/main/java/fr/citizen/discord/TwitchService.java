package fr.citizen.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Client Twitch Helix utilisant un app access token (aucun compte streameur requis). */
public final class TwitchService {
    private static final String HELIX = "https://api.twitch.tv/helix";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String clientId;
    private volatile String clientSecret;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;

    public TwitchService(String clientId, String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    /** Met à jour les identifiants sans redémarrer le bot. */
    public synchronized void configure(String clientId, String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.accessToken = null;
        this.tokenExpiresAt = 0;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public boolean validLogin(String login) throws Exception {
        if (!isConfigured()) return false;
        String safeLogin = URLEncoder.encode(login, StandardCharsets.UTF_8);
        HttpResponse<String> response = helixGet("/users?login=" + safeLogin);
        if (response.statusCode() == 401) {
            accessToken = null;
            response = helixGet("/users?login=" + safeLogin);
        }
        if (response.statusCode() >= 400) throw new IllegalStateException("Twitch Helix HTTP " + response.statusCode());
        JsonArray data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
        return data != null && !data.isEmpty();
    }

    public JsonObject liveStream(String login) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Identifiants Twitch non configurés");
        String safeLogin = URLEncoder.encode(login, StandardCharsets.UTF_8);
        HttpResponse<String> response = helixGet("/streams?user_login=" + safeLogin);
        if (response.statusCode() == 401) {
            accessToken = null;
            response = helixGet("/streams?user_login=" + safeLogin);
        }
        if (response.statusCode() >= 400) throw new IllegalStateException("Twitch Helix HTTP " + response.statusCode());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        return data == null || data.isEmpty() ? null : data.get(0).getAsJsonObject();
    }

    private HttpResponse<String> helixGet(String path) throws Exception {
        String token = token();
        HttpRequest request = HttpRequest.newBuilder(URI.create(HELIX + path))
                .timeout(Duration.ofSeconds(15))
                .header("Client-Id", clientId)
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private synchronized String token() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return accessToken;
        String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new IllegalStateException("Authentification Twitch refusée (HTTP " + response.statusCode() + ")");
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        accessToken = body.get("access_token").getAsString();
        tokenExpiresAt = System.currentTimeMillis() + body.get("expires_in").getAsLong() * 1000L;
        return accessToken;
    }
}
