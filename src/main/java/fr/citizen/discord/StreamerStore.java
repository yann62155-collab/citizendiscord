package fr.citizen.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Données Twitch séparées de la whitelist Minecraft. */
public final class StreamerStore {
    public static final class Streamer {
        @SerializedName("discord_id") public String discordId;
        @SerializedName("twitch_login") public String login;
        @SerializedName("live") public Boolean live;

        Streamer(String discordId, String login) {
            this.discordId = discordId;
            this.login = login;
        }
    }

    private static final class Data {
        @SerializedName("streamers") List<Streamer> streamers = new ArrayList<>();
        @SerializedName("welcome_sent") Set<String> welcomeSent = new LinkedHashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Data data = new Data();

    private static Path file() { return DiscordConfig.dir().resolve("streamers.json"); }

    public static synchronized void load() {
        try {
            Files.createDirectories(DiscordConfig.dir());
            if (Files.exists(file())) {
                try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                    Data loaded = GSON.fromJson(r, Data.class);
                    if (loaded != null) {
                        if (loaded.streamers == null) loaded.streamers = new ArrayList<>();
                        if (loaded.welcomeSent == null) loaded.welcomeSent = new LinkedHashSet<>();
                        data = loaded;
                    }
                }
            } else save();
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible de lire streamers.json", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DiscordConfig.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            DiscordConfig.restrictFile(file());
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible d'écrire streamers.json", e);
        }
    }

    public static synchronized boolean markWelcomeSent(String discordId) {
        if (!data.welcomeSent.add(discordId)) return false;
        save();
        return true;
    }

    public static synchronized void unmarkWelcomeSent(String discordId) {
        if (data.welcomeSent.remove(discordId)) save();
    }

    public static synchronized Streamer findByDiscord(String discordId) {
        return data.streamers.stream().filter(s -> discordId.equals(s.discordId)).findFirst().orElse(null);
    }

    public static synchronized Streamer register(String discordId, String login) {
        Streamer s = findByDiscord(discordId);
        if (s == null) {
            s = new Streamer(discordId, login);
            s.live = false;
            data.streamers.add(s);
        } else if (!login.equalsIgnoreCase(s.login)) {
            s.login = login;
            s.live = false;
        }
        save();
        return s;
    }

    public static synchronized boolean remove(String discordId) {
        boolean removed = data.streamers.removeIf(s -> discordId.equals(s.discordId));
        if (removed) save();
        return removed;
    }

    public static synchronized List<Streamer> all() {
        return new ArrayList<>(data.streamers);
    }

    /** Enregistre l'état observé et renvoie l'ancien état (null lors du premier relevé). */
    public static synchronized Boolean updateLive(String discordId, boolean live) {
        Streamer s = findByDiscord(discordId);
        if (s == null) return null;
        Boolean previous = s.live;
        s.live = live;
        if (previous == null || previous != live) save();
        return previous;
    }
}
