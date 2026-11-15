package fr.citizen.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Whitelist Citizen (config/citizendiscord/whitelist.json).
 * Les pseudos sont comparés sous forme « normalisée » pour que les joueurs
 * console (Geyser/ViaProxy : préfixe, espaces du gamertag, majuscules) passent aussi.
 */
public class WhitelistStore {

    public static final String SOURCE_DISCORD = "discord";
    public static final String SOURCE_MANUEL = "manuel";

    public static class Entry {
        @SerializedName("pseudo") public String pseudo;
        @SerializedName("discord_id") public String discordId;
        @SerializedName("source") public String source;
        @SerializedName("ajoute_le") public String date;
    }

    /** Pseudos choisis par un membre Discord avec /pseudo. */
    public static class Link {
        @SerializedName("java") public String java;
        @SerializedName("console") public String console;
    }

    private static class Data {
        @SerializedName("joueurs") List<Entry> joueurs = new ArrayList<>();
        @SerializedName("liens_discord") Map<String, Link> liens = new HashMap<>();
        @SerializedName("prevenus_en_prive") Set<String> prevenus = new HashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Data data = new Data();

    private static Path file() { return DiscordConfig.dir().resolve("whitelist.json"); }

    /** Minuscules, sans préfixe console, espaces remplacés par « _ ». */
    public static String normalize(String name) {
        if (name == null) return "";
        String n = name.trim();
        for (String p : DiscordConfig.get().prefixesConsole) {
            if (!p.isEmpty() && n.regionMatches(true, 0, p, 0, p.length()) && n.length() > p.length()) {
                n = n.substring(p.length());
                break;
            }
        }
        return n.replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    public static synchronized void load() {
        try {
            if (Files.exists(file())) {
                try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null) {
                        if (d.joueurs == null) d.joueurs = new ArrayList<>();
                        if (d.liens == null) d.liens = new HashMap<>();
                        if (d.prevenus == null) d.prevenus = new HashSet<>();
                        data = d;
                    }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible de lire whitelist.json", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DiscordConfig.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible d'écrire whitelist.json", e);
        }
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    public static synchronized boolean contains(String pseudo) {
        String n = normalize(pseudo);
        for (Entry e : data.joueurs) if (normalize(e.pseudo).equals(n)) return true;
        return false;
    }

    public static synchronized Entry find(String pseudo) {
        String n = normalize(pseudo);
        for (Entry e : data.joueurs) if (normalize(e.pseudo).equals(n)) return e;
        return null;
    }

    public static synchronized List<Entry> all() { return new ArrayList<>(data.joueurs); }

    /** Ajout manuel (commande admin). @return false si déjà présent */
    public static synchronized boolean addManual(String pseudo) {
        if (contains(pseudo)) return false;
        Entry e = new Entry();
        e.pseudo = pseudo.trim();
        e.source = SOURCE_MANUEL;
        e.date = now();
        data.joueurs.add(e);
        save();
        return true;
    }

    /** @return l'entrée retirée, ou null */
    public static synchronized Entry remove(String pseudo) {
        Entry e = find(pseudo);
        if (e != null) {
            data.joueurs.remove(e);
            save();
        }
        return e;
    }

    public static synchronized List<String> pseudosOf(String discordId) {
        List<String> out = new ArrayList<>();
        for (Entry e : data.joueurs) if (discordId.equals(e.discordId)) out.add(e.pseudo);
        return out;
    }

    public static synchronized Set<String> discordIds() {
        Set<String> out = new HashSet<>();
        for (Entry e : data.joueurs) if (SOURCE_DISCORD.equals(e.source) && e.discordId != null) out.add(e.discordId);
        return out;
    }

    /** Pseudo déjà pris par un autre membre Discord ? */
    public static synchronized String ownerOf(String pseudo) {
        Entry e = find(pseudo);
        if (e != null && e.discordId != null) return e.discordId;
        String n = normalize(pseudo);
        for (Map.Entry<String, Link> l : data.liens.entrySet()) {
            if (n.equals(normalize(l.getValue().java)) || n.equals(normalize(l.getValue().console))) return l.getKey();
        }
        return null;
    }

    /** @return true si le membre n'avait pas encore reçu le message privé (et le marque comme prévenu) */
    public static synchronized boolean markWarned(String discordId) {
        boolean first = data.prevenus.add(discordId);
        if (first) save();
        return first;
    }

    public static synchronized void unmarkWarned(String discordId) {
        if (data.prevenus.remove(discordId)) save();
    }

    public static synchronized Link link(String discordId) { return data.liens.get(discordId); }

    public static synchronized void setLink(String discordId, String pseudo, boolean console) {
        Link l = data.liens.computeIfAbsent(discordId, k -> new Link());
        if (console) l.console = pseudo; else l.java = pseudo;
        save();
    }

    /**
     * Met les pseudos whitelistés d'un membre Discord exactement à « wanted ».
     * @return [ajoutés, retirés]
     */
    public static synchronized List<List<String>> syncDiscordMember(String discordId, Collection<String> wanted) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Set<String> wantedNorm = new HashSet<>();
        for (String w : wanted) wantedNorm.add(normalize(w));

        Iterator<Entry> it = data.joueurs.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (discordId.equals(e.discordId) && SOURCE_DISCORD.equals(e.source) && !wantedNorm.contains(normalize(e.pseudo))) {
                it.remove();
                removed.add(e.pseudo);
            }
        }
        for (String w : wanted) {
            Entry existing = find(w);
            if (existing == null) {
                Entry e = new Entry();
                e.pseudo = w;
                e.discordId = discordId;
                e.source = SOURCE_DISCORD;
                e.date = now();
                data.joueurs.add(e);
                added.add(w);
            } else if (existing.discordId == null) {
                existing.discordId = discordId; // ajout manuel rattaché au membre
            }
        }
        if (!added.isEmpty() || !removed.isEmpty()) save();
        return List.of(added, removed);
    }
}
