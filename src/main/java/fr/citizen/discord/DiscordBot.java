package fr.citizen.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bot Discord minimal, sans aucune librairie externe (HttpClient + WebSocket de Java 21, Gson de Minecraft).
 * - écoute les changements de rôles (GUILD_MEMBER_UPDATE) → rôle Aventurier = whitelist
 * - commande Discord /pseudo pour lier son pseudo PC ou son gamertag console
 * - synchronisation complète à chaque connexion du bot
 */
public class DiscordBot implements WebSocket.Listener {

    private static final String API = "https://discord.com/api/v10";
    private static final String GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final int INTENTS = 1 | 2; // GUILDS | GUILD_MEMBERS (intent privilégié à activer)
    private static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern IN_BRACKETS = Pattern.compile("[\\[(]\\s*([A-Za-z0-9_]{3,16})\\s*[\\])]");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Pseudo Java ou gamertag console (espaces autorisés). */
    private static final Pattern ANY_NAME = Pattern.compile("^[A-Za-z0-9_ ]{3,20}$");

    private final DiscordConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CitizenDiscord-Bot");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;
    private volatile boolean connected;
    private volatile WebSocket ws;
    private volatile Integer seq;
    private volatile String roleId;
    private volatile String appId;
    private volatile String botName = "?";
    private ScheduledFuture<?> heartbeat;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final StringBuilder buffer = new StringBuilder();

    public DiscordBot(DiscordConfig cfg) { this.cfg = cfg; }

    public boolean isConnected() { return connected; }
    public String roleId() { return roleId; }
    public String botName() { return botName; }

    // ------------------------------------------------------------------ cycle de vie

    public void start() {
        running = true;
        exec.execute(this::connect);
    }

    public void stop() {
        running = false;
        connected = false;
        if (heartbeat != null) heartbeat.cancel(true);
        WebSocket w = ws;
        if (w != null) {
            try { w.sendClose(1000, "arret").orTimeout(3, TimeUnit.SECONDS).join(); } catch (Exception ignored) {}
        }
        exec.shutdownNow();
    }

    private void connect() {
        if (!running) return;
        try {
            if (roleId == null) resolveRole();
            ws = http.newWebSocketBuilder().buildAsync(URI.create(GATEWAY), this).join();
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Connexion à Discord impossible ({}), nouvel essai dans 30 s", e.getMessage());
            scheduleReconnect(30);
        }
    }

    private void scheduleReconnect(int seconds) {
        connected = false;
        if (heartbeat != null) heartbeat.cancel(false);
        if (running && !exec.isShutdown()) exec.schedule(this::connect, seconds, TimeUnit.SECONDS);
    }

    private void resolveRole() throws Exception {
        if (cfg.idRole != null && !cfg.idRole.isBlank()) {
            roleId = cfg.idRole.trim();
            return;
        }
        JsonElement roles = rest("GET", "/guilds/" + cfg.idServeurDiscord + "/roles", null);
        if (roles != null && roles.isJsonArray()) {
            for (JsonElement r : roles.getAsJsonArray()) {
                JsonObject o = r.getAsJsonObject();
                if (o.get("name").getAsString().equalsIgnoreCase(cfg.nomRole)) {
                    roleId = o.get("id").getAsString();
                    CitizenDiscord.LOGGER.info("[CitizenDiscord] Rôle « {} » trouvé (id {})", cfg.nomRole, roleId);
                    return;
                }
            }
        }
        throw new IllegalStateException("rôle « " + cfg.nomRole + " » introuvable sur le serveur Discord");
    }

    // ------------------------------------------------------------------ WebSocket

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String msg = buffer.toString();
            buffer.setLength(0);
            try { handle(JsonParser.parseString(msg).getAsJsonObject()); }
            catch (Exception e) { CitizenDiscord.LOGGER.warn("[CitizenDiscord] Message Discord illisible", e); }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
        connected = false;
        switch (code) {
            case 4004 -> {
                CitizenDiscord.LOGGER.error("[CitizenDiscord] Token du bot invalide ! Vérifie token_bot dans config.json puis /cdiscord reload");
                running = false;
                return null;
            }
            case 4014 -> {
                CitizenDiscord.LOGGER.error("[CitizenDiscord] Active « SERVER MEMBERS INTENT » dans le portail développeur Discord (onglet Bot) puis /cdiscord reload");
                running = false;
                return null;
            }
            default -> {
                if (running) CitizenDiscord.LOGGER.warn("[CitizenDiscord] Connexion Discord fermée ({} {}), reconnexion…", code, reason);
            }
        }
        scheduleReconnect(5);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        CitizenDiscord.LOGGER.warn("[CitizenDiscord] Erreur Discord : {}", error.toString());
        scheduleReconnect(10);
    }

    private synchronized void send(JsonObject payload) {
        WebSocket w = ws;
        if (w == null) return;
        String text = payload.toString();
        sendChain = sendChain.handle((a, b) -> null).thenCompose(v -> w.sendText(text, true));
    }

    private void handle(JsonObject msg) {
        int op = msg.get("op").getAsInt();
        if (msg.has("s") && !msg.get("s").isJsonNull()) seq = msg.get("s").getAsInt();
        switch (op) {
            case 10 -> { // HELLO
                long interval = msg.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                if (heartbeat != null) heartbeat.cancel(false);
                heartbeat = exec.scheduleAtFixedRate(this::sendHeartbeat, (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                identify();
            }
            case 1 -> sendHeartbeat();
            case 7, 9 -> { // RECONNECT / INVALID SESSION
                WebSocket w = ws;
                if (w != null) w.abort();
                scheduleReconnect(op == 9 ? 5 : 1);
            }
            case 0 -> dispatch(msg.get("t").getAsString(), msg.get("d"));
            default -> { }
        }
    }

    private void sendHeartbeat() {
        JsonObject o = new JsonObject();
        o.addProperty("op", 1);
        if (seq == null) o.add("d", com.google.gson.JsonNull.INSTANCE); else o.addProperty("d", seq);
        send(o);
    }

    private void identify() {
        JsonObject props = new JsonObject();
        props.addProperty("os", "linux");
        props.addProperty("browser", "citizendiscord");
        props.addProperty("device", "citizendiscord");
        JsonObject d = new JsonObject();
        d.addProperty("token", cfg.tokenBot.trim());
        d.addProperty("intents", INTENTS);
        d.add("properties", props);
        JsonObject o = new JsonObject();
        o.addProperty("op", 2);
        o.add("d", d);
        send(o);
    }

    private void dispatch(String type, JsonElement d) {
        switch (type) {
            case "READY" -> {
                JsonObject o = d.getAsJsonObject();
                appId = o.getAsJsonObject("application").get("id").getAsString();
                botName = o.getAsJsonObject("user").get("username").getAsString();
                connected = true;
                CitizenDiscord.LOGGER.info("[CitizenDiscord] Bot Discord connecté : {}", botName);
                exec.execute(() -> {
                    registerSlashCommands();
                    fullSync();
                });
            }
            case "GUILD_MEMBER_UPDATE" -> {
                JsonObject o = d.getAsJsonObject();
                if (cfg.idServeurDiscord.equals(str(o, "guild_id"))) exec.execute(() -> applyMember(o, true));
            }
            case "GUILD_MEMBER_REMOVE" -> {
                JsonObject o = d.getAsJsonObject();
                if (cfg.idServeurDiscord.equals(str(o, "guild_id")) && cfg.retirerSiQuitte) {
                    String id = o.getAsJsonObject("user").get("id").getAsString();
                    exec.execute(() -> removeMember(id, "a quitté le Discord"));
                }
            }
            case "INTERACTION_CREATE" -> {
                JsonObject o = d.getAsJsonObject();
                exec.execute(() -> handleInteraction(o));
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ logique whitelist

    /** Relit tous les membres du Discord (au démarrage / reconnexion / /cdiscord sync). */
    public void fullSync() {
        if (!isConnected()) return;
        exec.execute(() -> {
            try {
                Set<String> withRole = new HashSet<>();
                String after = "0";
                while (true) {
                    JsonElement page = rest("GET", "/guilds/" + cfg.idServeurDiscord + "/members?limit=1000&after=" + after, null);
                    if (page == null || !page.isJsonArray() || page.getAsJsonArray().isEmpty()) break;
                    JsonArray arr = page.getAsJsonArray();
                    for (JsonElement m : arr) {
                        JsonObject mo = m.getAsJsonObject();
                        if (hasRole(mo)) withRole.add(mo.getAsJsonObject("user").get("id").getAsString());
                        applyMember(mo, false);
                        after = mo.getAsJsonObject("user").get("id").getAsString();
                    }
                    if (arr.size() < 1000) break;
                }
                if (cfg.retirerSiRoleRetire) {
                    for (String id : WhitelistStore.discordIds()) if (!withRole.contains(id)) removeMember(id, "n'a plus le rôle " + cfg.nomRole);
                }
                CitizenDiscord.LOGGER.info("[CitizenDiscord] Synchronisation terminée : {} membre(s) avec le rôle {}", withRole.size(), cfg.nomRole);
            } catch (Exception e) {
                CitizenDiscord.LOGGER.warn("[CitizenDiscord] Synchronisation impossible : {}", e.getMessage());
            }
        });
    }

    private boolean hasRole(JsonObject member) {
        if (roleId == null || !member.has("roles")) return false;
        for (JsonElement r : member.getAsJsonArray("roles")) if (roleId.equals(r.getAsString())) return true;
        return false;
    }

    private void applyMember(JsonObject member, boolean live) {
        JsonObject user = member.getAsJsonObject("user");
        if (user == null || (user.has("bot") && user.get("bot").getAsBoolean())) return;
        String id = user.get("id").getAsString();

        if (!hasRole(member)) {
            if (live && cfg.retirerSiRoleRetire) removeMember(id, "n'a plus le rôle " + cfg.nomRole);
            return;
        }

        List<String> wanted = new ArrayList<>();
        WhitelistStore.Link link = WhitelistStore.link(id);
        if (link != null) {
            if (link.java != null) wanted.add(link.java);
            if (link.console != null) wanted.add(link.console);
        }
        if (wanted.isEmpty() && cfg.pseudoDepuisSurnom) {
            String guess = guessPseudo(str(member, "nick"));
            if (guess == null) guess = guessPseudo(str(user, "global_name"));
            if (guess != null) {
                String owner = WhitelistStore.ownerOf(guess);
                if (owner == null || owner.equals(id)) wanted.add(guess);
            }
        }
        if (wanted.isEmpty()) {
            if (live) log("⚠️ <@" + id + "> a le rôle **" + cfg.nomRole + "** mais je ne connais pas son pseudo : il doit taper `/pseudo`.");
            return;
        }
        List<List<String>> res = WhitelistStore.syncDiscordMember(id, wanted);
        for (String a : res.get(0)) log("✅ **" + a + "** ajouté à la whitelist (<@" + id + ">)");
        for (String r : res.get(1)) log("➖ **" + r + "** retiré de la whitelist (<@" + id + ">, pseudo changé)");
        CitizenDiscord.kickOnline(res.get(1));
    }

    private void removeMember(String discordId, String why) {
        List<List<String>> res = WhitelistStore.syncDiscordMember(discordId, List.of());
        for (String r : res.get(1)) log("❌ **" + r + "** retiré de la whitelist (<@" + discordId + "> " + why + ")");
        CitizenDiscord.kickOnline(res.get(1));
    }

    /** Devine le pseudo Minecraft depuis le surnom Discord : « Ishawakiii », « Jean (Ishawakiii) », « Ishawakiii | Roi »… */
    static String guessPseudo(String s) {
        if (s == null) return null;
        s = s.trim();
        if (MC_NAME.matcher(s).matches()) return s;
        Matcher b = IN_BRACKETS.matcher(s);
        if (b.find()) return b.group(1);
        Matcher t = TOKEN.matcher(s);
        return t.find() ? t.group() : null;
    }

    // ------------------------------------------------------------------ commande Discord /pseudo

    private void registerSlashCommands() {
        try {
            JsonObject pseudo = new JsonObject();
            pseudo.addProperty("type", 3);
            pseudo.addProperty("name", "pseudo");
            pseudo.addProperty("description", "Ton pseudo Minecraft PC, ou ton gamertag Xbox / PlayStation / Switch / téléphone");
            pseudo.addProperty("required", true);
            pseudo.addProperty("min_length", 3);
            pseudo.addProperty("max_length", 20);

            JsonObject plateforme = new JsonObject();
            plateforme.addProperty("type", 3);
            plateforme.addProperty("name", "plateforme");
            plateforme.addProperty("description", "Sur quoi tu joues (PC par défaut)");
            plateforme.addProperty("required", false);
            JsonArray choices = new JsonArray();
            choices.add(choice("PC (Java)", "java"));
            choices.add(choice("Console / téléphone (Bedrock)", "console"));
            plateforme.add("choices", choices);

            JsonArray options = new JsonArray();
            options.add(pseudo);
            options.add(plateforme);

            JsonObject cmd = new JsonObject();
            cmd.addProperty("name", "pseudo");
            cmd.addProperty("type", 1);
            cmd.addProperty("description", "Lier ton pseudo Minecraft à la whitelist du serveur Citizen");
            cmd.add("options", options);

            JsonArray all = new JsonArray();
            all.add(cmd);
            rest("PUT", "/applications/" + appId + "/guilds/" + cfg.idServeurDiscord + "/commands", all);
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Impossible de créer la commande /pseudo (le bot a-t-il été invité avec applications.commands ?) : {}", e.getMessage());
        }
    }

    private static JsonObject choice(String name, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", value);
        return o;
    }

    private void handleInteraction(JsonObject i) {
        try {
            if (i.get("type").getAsInt() != 2) return;
            JsonObject data = i.getAsJsonObject("data");
            if (!"pseudo".equals(str(data, "name"))) return;
            JsonObject member = i.getAsJsonObject("member");
            if (member == null) { reply(i, "Utilise cette commande sur le serveur Discord Citizen."); return; }
            String id = member.getAsJsonObject("user").get("id").getAsString();

            String pseudo = null;
            boolean console = false;
            if (data.has("options")) {
                for (JsonElement e : data.getAsJsonArray("options")) {
                    JsonObject opt = e.getAsJsonObject();
                    if ("pseudo".equals(str(opt, "name"))) pseudo = opt.get("value").getAsString().trim();
                    if ("plateforme".equals(str(opt, "name"))) console = "console".equals(opt.get("value").getAsString());
                }
            }
            if (pseudo == null || !ANY_NAME.matcher(pseudo).matches() || (!console && !MC_NAME.matcher(pseudo).matches())) {
                reply(i, console
                        ? "❌ Gamertag invalide (3 à 20 caractères : lettres, chiffres, espaces, _)."
                        : "❌ Pseudo invalide (3 à 16 caractères : lettres, chiffres, _). Tu joues sur console ? Ajoute `plateforme: Console`.");
                return;
            }
            String owner = WhitelistStore.ownerOf(pseudo);
            if (owner != null && !owner.equals(id)) {
                reply(i, "❌ Le pseudo **" + pseudo + "** est déjà lié à un autre membre. Contacte le staff si c'est bien le tien.");
                return;
            }
            WhitelistStore.setLink(id, pseudo, console);
            String plat = console ? "console" : "PC";
            if (hasRole(member)) {
                applyMember(member, true);
                reply(i, "✅ **" + pseudo + "** (" + plat + ") est dans la whitelist ! Tu peux te connecter au serveur Citizen.");
            } else {
                reply(i, "📜 Pseudo **" + pseudo + "** (" + plat + ") enregistré. Dès que le staff te donne le rôle **"
                        + cfg.nomRole + "**, tu seras automatiquement whitelisté.");
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Erreur commande /pseudo", e);
        }
    }

    private void reply(JsonObject interaction, String content) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("flags", 64); // visible seulement par le joueur
        JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        rest("POST", "/interactions/" + str(interaction, "id") + "/" + str(interaction, "token") + "/callback", body);
    }

    // ------------------------------------------------------------------ outils

    public void log(String text) {
        CitizenDiscord.LOGGER.info("[CitizenDiscord] {}", text.replace("**", ""));
        if (cfg.idSalonLogs == null || cfg.idSalonLogs.isBlank()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("content", text);
            JsonObject mentions = new JsonObject();
            mentions.add("parse", new JsonArray()); // ne ping personne
            body.add("allowed_mentions", mentions);
            rest("POST", "/channels/" + cfg.idSalonLogs + "/messages", body);
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Salon de logs inaccessible : {}", e.getMessage());
        }
    }

    private JsonElement rest(String method, String path, JsonElement body) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bot " + cfg.tokenBot.trim())
                    .header("User-Agent", "DiscordBot (https://github.com/yann62155-collab, 1.0) CitizenDiscord")
                    .header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) {
                double wait = 1;
                try { wait = JsonParser.parseString(res.body()).getAsJsonObject().get("retry_after").getAsDouble(); } catch (Exception ignored) {}
                Thread.sleep((long) (wait * 1000) + 100);
                continue;
            }
            if (res.statusCode() >= 400) throw new IllegalStateException("HTTP " + res.statusCode() + " " + res.body());
            return res.body() == null || res.body().isBlank() ? null : JsonParser.parseString(res.body());
        }
        throw new IllegalStateException("trop de requêtes (rate limit Discord)");
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
