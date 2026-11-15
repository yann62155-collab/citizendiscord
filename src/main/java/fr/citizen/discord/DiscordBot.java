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
public class DiscordBot {

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

    /** Commandes Discord traitées à part : jamais bloquées par une synchro ou des messages privés. */
    private final ExecutorService interactions = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "CitizenDiscord-Commandes");
        t.setDaemon(true);
        return t;
    });

    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger scheduledGen = new java.util.concurrent.atomic.AtomicInteger(-1);
    private volatile String lastError = "aucune";
    private volatile boolean running;
    private volatile boolean connected;
    private volatile WebSocket ws;
    private volatile Integer seq;
    private volatile String roleId;
    private volatile String newRoleId;
    private volatile String appId;
    private volatile String botName = "?";
    private ScheduledFuture<?> heartbeat;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public DiscordBot(DiscordConfig cfg) { this.cfg = cfg; }

    public boolean isConnected() { return connected; }
    public String roleId() { return roleId; }
    public String newRoleId() { return newRoleId; }
    public String botName() { return botName; }
    public String lastError() { return lastError; }

    private void error(String msg) {
        lastError = msg;
        CitizenDiscord.LOGGER.warn("[CitizenDiscord] {}", msg);
    }

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
        interactions.shutdownNow();
    }

    private void connect() {
        if (!running) return;
        int gen = generation.incrementAndGet();
        WebSocket old = ws;
        ws = null;
        if (old != null) old.abort(); // une seule connexion à la fois
        if (heartbeat != null) heartbeat.cancel(false);
        seq = null;
        try {
            if (roleId == null) resolveRole();
            // vérifie que Discord autorise encore des connexions aujourd'hui (limite de 1000 / 24 h)
            JsonElement gw = rest("GET", "/gateway/bot", null);
            if (gw != null && gw.isJsonObject() && gw.getAsJsonObject().has("session_start_limit")) {
                JsonObject lim = gw.getAsJsonObject().getAsJsonObject("session_start_limit");
                if (lim.get("remaining").getAsInt() <= 0) {
                    long waitMs = lim.get("reset_after").getAsLong();
                    error("Limite de connexions Discord atteinte : nouvel essai dans " + (waitMs / 60000 + 1) + " min");
                    scheduleReconnect(gen, (int) Math.min(Integer.MAX_VALUE, waitMs / 1000 + 5));
                    return;
                }
            }
            synchronized (this) { sendChain = CompletableFuture.completedFuture(null); }
            ws = http.newWebSocketBuilder().buildAsync(URI.create(GATEWAY), new Conn(gen)).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            error("Connexion à Discord impossible (" + c + "), nouvel essai dans 30 s");
            scheduleReconnect(gen, 30);
        }
    }

    /** Programme UNE seule reconnexion par connexion perdue (évite les connexions en double). */
    private void scheduleReconnect(int gen, int seconds) {
        if (gen != generation.get()) return;            // vieille connexion : on ignore
        if (scheduledGen.getAndSet(gen) == gen) return; // déjà programmée
        connected = false;
        if (heartbeat != null) heartbeat.cancel(false);
        if (running && !exec.isShutdown()) exec.schedule(this::connect, seconds, TimeUnit.SECONDS);
    }

    private void resolveRole() throws Exception {
        JsonArray roles = null;
        if (cfg.idRole != null && !cfg.idRole.isBlank()) {
            roleId = cfg.idRole.trim();
        } else {
            roles = fetchRoles();
            roleId = findRole(roles, cfg.nomRole);
            if (roleId == null) throw new IllegalStateException("rôle « " + cfg.nomRole + " » introuvable sur le serveur Discord");
            CitizenDiscord.LOGGER.info("[CitizenDiscord] Rôle « {} » trouvé (id {})", cfg.nomRole, roleId);
        }
        if (cfg.roleNouveauxActif) {
            if (cfg.idRoleNouveaux != null && !cfg.idRoleNouveaux.isBlank()) {
                newRoleId = cfg.idRoleNouveaux.trim();
            } else {
                if (roles == null) roles = fetchRoles();
                newRoleId = findRole(roles, cfg.nomRoleNouveaux);
                if (newRoleId == null) CitizenDiscord.LOGGER.warn("[CitizenDiscord] Rôle des nouveaux « {} » introuvable : vérifie nom_role_nouveaux", cfg.nomRoleNouveaux);
                else CitizenDiscord.LOGGER.info("[CitizenDiscord] Rôle des nouveaux « {} » trouvé (id {})", cfg.nomRoleNouveaux, newRoleId);
            }
        }
    }

    private JsonArray fetchRoles() throws Exception {
        JsonElement e = rest("GET", "/guilds/" + cfg.idServeurDiscord + "/roles", null);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String findRole(JsonArray roles, String name) {
        if (name == null || name.isBlank()) return null;
        for (JsonElement r : roles) {
            JsonObject o = r.getAsJsonObject();
            if (o.get("name").getAsString().equalsIgnoreCase(name.trim())) return o.get("id").getAsString();
        }
        return null;
    }

    /** Donne le rôle des nouveaux à un membre qui vient d'arriver sur le Discord. */
    private void giveNewcomerRole(JsonObject member) {
        JsonObject user = member.getAsJsonObject("user");
        if (user == null || (user.has("bot") && user.get("bot").getAsBoolean())) return;
        if (!cfg.roleNouveauxActif || newRoleId == null) return;
        String id = user.get("id").getAsString();
        try {
            rest("PUT", "/guilds/" + cfg.idServeurDiscord + "/members/" + id + "/roles/" + newRoleId, null);
            log("👋 <@" + id + "> est arrivé sur le Discord : rôle **" + cfg.nomRoleNouveaux + "** donné.");
        } catch (Exception e) {
            String err = e.getMessage() == null ? "" : e.getMessage();
            if (err.contains("HTTP 403")) {
                log("⚠️ Impossible de donner le rôle **" + cfg.nomRoleNouveaux + "** à <@" + id + "> : donne au bot la permission "
                        + "« Gérer les rôles » et place son rôle AU-DESSUS de « " + cfg.nomRoleNouveaux + " » (Paramètres du serveur → Rôles).");
            } else {
                CitizenDiscord.LOGGER.warn("[CitizenDiscord] Rôle des nouveaux non donné : {}", err);
            }
        }
    }

    // ------------------------------------------------------------------ WebSocket

    /** Écouteur propre à UNE connexion : les messages d'une ancienne connexion sont ignorés. */
    private final class Conn implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder buffer = new StringBuilder();

        Conn(int gen) { this.gen = gen; }

        private boolean current() { return gen == generation.get() && running; }

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
                if (current()) {
                    try { handle(gen, JsonParser.parseString(msg).getAsJsonObject()); }
                    catch (Exception e) { CitizenDiscord.LOGGER.warn("[CitizenDiscord] Message Discord illisible", e); }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
            if (!current()) return null;
            connected = false;
            String fatal = switch (code) {
                case 4004 -> "Token du bot refusé par Discord : vérifie token_bot puis /cdiscord reload";
                case 4013, 4014 -> "Intent refusé : active « SERVER MEMBERS INTENT » (portail développeur → Bot → Save) puis /cdiscord reload";
                case 4010, 4011, 4012 -> "Connexion refusée par Discord (code " + code + ")";
                default -> null;
            };
            if (fatal != null) {
                error(fatal);
                running = false;
                return null;
            }
            error("Connexion Discord fermée (code " + code + (reason == null || reason.isBlank() ? "" : " : " + reason) + "), reconnexion…");
            scheduleReconnect(gen, 5);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable err) {
            if (!current()) return;
            error("Erreur de connexion Discord : " + err);
            scheduleReconnect(gen, 10);
        }
    }

    private synchronized void send(JsonObject payload) {
        WebSocket w = ws;
        if (w == null) return;
        String text = payload.toString();
        sendChain = sendChain.handle((a, b) -> null).thenCompose(v -> w.sendText(text, true));
    }

    private void handle(int gen, JsonObject msg) {
        int op = msg.get("op").getAsInt();
        if (msg.has("s") && !msg.get("s").isJsonNull()) seq = msg.get("s").getAsInt();
        switch (op) {
            case 10 -> { // HELLO
                long interval = msg.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                if (heartbeat != null) heartbeat.cancel(false);
                heartbeat = exec.scheduleAtFixedRate(() -> { if (gen == generation.get()) sendHeartbeat(); }, (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                identify();
            }
            case 1 -> sendHeartbeat();
            case 7, 9 -> { // RECONNECT / INVALID SESSION
                if (op == 9) error("Session Discord invalidée, reconnexion…");
                scheduleReconnect(gen, op == 9 ? 5 : 1);
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
                lastError = "aucune";
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
            case "GUILD_MEMBER_ADD" -> {
                JsonObject o = d.getAsJsonObject();
                if (cfg.idServeurDiscord.equals(str(o, "guild_id"))) exec.execute(() -> giveNewcomerRole(o));
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
                interactions.execute(() -> handleInteraction(o));
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
            WhitelistStore.unmarkWarned(id); // s'il récupère le rôle plus tard, il sera de nouveau prévenu
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
            // pseudo inconnu : on retire un éventuel pseudo deviné avant, et on prévient le joueur en privé (une seule fois)
            List<List<String>> res = WhitelistStore.syncDiscordMember(id, List.of());
            for (String r : res.get(1)) log("➖ **" + r + "** retiré de la whitelist (<@" + id + "> doit indiquer son pseudo avec /pseudo)");
            if (WhitelistStore.markWarned(id)) {
                boolean dm = sendPrivate(id, cfg.messagePriveRole);
                log("📜 <@" + id + "> a le rôle **" + cfg.nomRole + "** : en attente de son pseudo (/pseudo)"
                        + (dm ? ", message privé envoyé." : " — ses messages privés sont fermés, préviens-le sur le Discord."));
            }
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
            defer(i); // répond tout de suite à Discord (« Citizen réfléchit… »), le vrai message arrive juste après
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
            try { reply(i, "❌ Une erreur est survenue, réessaie dans un instant."); } catch (Exception ignored) {}
        }
    }

    /** Envoie un message privé au membre. @return false si ses MP sont fermés */
    private boolean sendPrivate(String userId, String content) {
        try {
            JsonObject open = new JsonObject();
            open.addProperty("recipient_id", userId);
            JsonElement ch = rest("POST", "/users/@me/channels", open);
            JsonObject msg = new JsonObject();
            msg.addProperty("content", content);
            rest("POST", "/channels/" + ch.getAsJsonObject().get("id").getAsString() + "/messages", msg);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void defer(JsonObject interaction) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("flags", 64);
        JsonObject body = new JsonObject();
        body.addProperty("type", 5);
        body.add("data", data);
        rest("POST", "/interactions/" + str(interaction, "id") + "/" + str(interaction, "token") + "/callback", body);
    }

    /** Remplace le message « réfléchit… » par la réponse finale. */
    private void reply(JsonObject interaction, String content) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        String app = appId != null ? appId : str(interaction, "application_id");
        rest("PATCH", "/webhooks/" + app + "/" + str(interaction, "token") + "/messages/@original", body);
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
