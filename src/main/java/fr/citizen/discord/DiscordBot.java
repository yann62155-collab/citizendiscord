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

    /** Vérifications Twitch isolées du thread Gateway afin de ne jamais bloquer les heartbeats Discord. */
    private final ScheduledExecutorService twitchExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CitizenDiscord-Twitch");
        t.setDaemon(true);
        return t;
    });
    private final TwitchService twitch;

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
    /** Discord a répondu au dernier battement de cœur ? Sinon la connexion est morte : on se reconnecte. */
    private volatile boolean acked = true;
    private volatile long lastAttempt;
    private volatile long nextAttemptAt;
    private volatile boolean slashOk;
    private volatile Set<String> staffRoleIds = Set.of();
    private static final long PERM_ADMIN = 1L << 3;
    private static final long PERM_MANAGE_ROLES = 1L << 28;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public DiscordBot(DiscordConfig cfg) {
        this.cfg = cfg;
        this.twitch = new TwitchService(cfg.twitchClientId, cfg.twitchClientSecret);
    }

    public boolean isConnected() { return connected; }
    public String roleId() { return roleId; }
    public String newRoleId() { return newRoleId; }
    public String botName() { return botName; }
    public String lastError() { return lastError; }
    public boolean slashOk() { return slashOk; }

    private void error(String msg) {
        lastError = msg;
        CitizenDiscord.LOGGER.warn("[CitizenDiscord] {}", msg);
    }

    // ------------------------------------------------------------------ cycle de vie

    public void start() {
        running = true;
        exec.execute(this::connect);
        // chien de garde : si le bot n'est pas connecté depuis trop longtemps et qu'aucun essai n'est prévu, on relance
        exec.scheduleWithFixedDelay(this::watchdog, 60, 60, TimeUnit.SECONDS);
        // resynchronisation régulière (rattrape les changements de rôles manqués pendant une coupure)
        exec.scheduleWithFixedDelay(() -> { if (connected) fullSync(); }, 30, 30, TimeUnit.MINUTES);
        int interval = Math.max(30, Math.min(300, cfg.twitchIntervalleVerificationSecondes));
        twitchExec.scheduleWithFixedDelay(this::pollTwitchSafely, 20, interval, TimeUnit.SECONDS);
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
        twitchExec.shutdownNow();
    }

    private void watchdog() {
        if (!running || connected) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt > 120_000 && now > nextAttemptAt + 60_000) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Bot toujours déconnecté : nouvelle tentative de connexion");
            connect();
        }
    }

    private void connect() {
        if (!running) return;
        lastAttempt = System.currentTimeMillis();
        nextAttemptAt = 0;
        int gen = generation.incrementAndGet();
        WebSocket old = ws;
        ws = null;
        if (old != null) old.abort(); // une seule connexion à la fois
        if (heartbeat != null) heartbeat.cancel(false);
        seq = null;
        try {
            if (roleId == null) {
                try { resolveRole(); }
                catch (Exception e) { error("Rôle non résolu (" + e.getMessage() + "), nouvel essai à la connexion"); }
            }
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
            WebSocket w = http.newWebSocketBuilder().buildAsync(URI.create(GATEWAY), new Conn(gen)).get(20, TimeUnit.SECONDS);
            if (gen == generation.get()) ws = w; else w.abort();
            // sécurité : si Discord n'a pas validé la connexion (READY) en 45 s, on recommence proprement
            exec.schedule(() -> {
                if (running && gen == generation.get() && !connected) {
                    error("Discord n'a pas validé la connexion du bot, nouvel essai…");
                    WebSocket cur = ws;
                    if (cur != null) cur.abort();
                    scheduleReconnect(gen, 5);
                }
            }, 45, TimeUnit.SECONDS);
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
        nextAttemptAt = System.currentTimeMillis() + seconds * 1000L;
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

    // ------------------------------------------------------------------ roles de metier (Citizen Economy)

    private final Set<String> warnedRoles = ConcurrentHashMap.newKeySet();

    /**
     * Met les roles de metier des membres Discord en accord avec ce que Citizen Economy envoie.
     * Jamais de creation de role, jamais de role hors de la liste roles_metiers. @return le nombre de roles ajoutes ou retires
     */
    public int syncJobRoles(Map<String, Set<String>> jobsByDiscordId) {
        if (!running || !cfg.rolesMetiersActif || cfg.rolesMetiers == null || cfg.rolesMetiers.isEmpty()) return 0;
        Map<String, String> roleOfJob = new LinkedHashMap<>(); // metier -> id du role
        try {
            JsonArray roles = null;
            for (Map.Entry<String, String> e : cfg.rolesMetiers.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.matches("\\d{15,25}")) { roleOfJob.put(e.getKey(), v); continue; }
                if (roles == null) roles = fetchRoles();
                String id = findRole(roles, v);
                if (id == null) {
                    if (warnedRoles.add(v)) CitizenDiscord.LOGGER.warn("[CitizenDiscord] Rôle de métier « {} » introuvable sur le Discord : crée-le à la main (le bot n'en crée jamais)", v);
                } else roleOfJob.put(e.getKey(), id);
            }
        } catch (Exception e) {
            error("Rôles de métier : lecture des rôles impossible (" + e.getMessage() + ")");
            return 0;
        }
        if (roleOfJob.isEmpty()) return 0;
        int changes = 0;
        for (Map.Entry<String, Set<String>> m : jobsByDiscordId.entrySet()) {
            if (!running) break;
            String discordId = m.getKey();
            if (discordId == null || !discordId.matches("\\d{15,25}")) continue;
            JsonObject member = fetchMember(discordId);
            if (member == null || !member.has("roles")) continue; // plus sur le Discord
            Set<String> has = new HashSet<>();
            for (JsonElement r : member.getAsJsonArray("roles")) has.add(r.getAsString());
            for (Map.Entry<String, String> job : roleOfJob.entrySet()) {
                boolean should = m.getValue().contains(job.getKey());
                boolean is = has.contains(job.getValue());
                if (should == is) continue;
                try {
                    rest(should ? "PUT" : "DELETE", "/guilds/" + cfg.idServeurDiscord + "/members/" + discordId + "/roles/" + job.getValue(), null);
                    changes++;
                    Thread.sleep(150); // reste loin des limites de Discord
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return changes;
                } catch (Exception e) {
                    String err = e.getMessage() == null ? "" : e.getMessage();
                    if (err.contains("HTTP 403")) error("Rôle de métier non modifié : donne au bot « Gérer les rôles » et place son rôle AU-DESSUS des rôles de métier");
                    else CitizenDiscord.LOGGER.warn("[CitizenDiscord] Rôle de métier non modifié pour {} : {}", discordId, err);
                }
            }
        }
        if (changes > 0) log("🛠️ Rôles de métier mis à jour d'après les entreprises : **" + changes + "** changement(s).");
        return changes;
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
            // on enregistre la connexion AVANT de lire le premier message (sinon l'identification part dans le vide)
            if (current()) ws = webSocket;
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
                error(fatal + " (nouvel essai automatique dans 5 min)");
                scheduleReconnect(gen, 300);
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
                acked = true;
                heartbeat = exec.scheduleAtFixedRate(() -> heartbeatTick(gen), (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                identify();
            }
            case 1 -> sendHeartbeat();
            case 11 -> acked = true; // HEARTBEAT ACK
            case 7, 9 -> { // RECONNECT / INVALID SESSION
                if (op == 9) error("Session Discord invalidée, reconnexion…");
                else CitizenDiscord.LOGGER.info("[CitizenDiscord] Discord demande une reconnexion (normal), reconnexion…");
                scheduleReconnect(gen, op == 9 ? 5 : 1);
            }
            case 0 -> dispatch(msg.get("t").getAsString(), msg.get("d"));
            default -> { }
        }
    }

    private void heartbeatTick(int gen) {
        if (gen != generation.get()) return;
        if (!acked) { // pas de réponse de Discord : connexion fantôme
            error("Discord ne répond plus, reconnexion…");
            WebSocket w = ws;
            if (w != null) w.abort();
            scheduleReconnect(gen, 1);
            return;
        }
        acked = false;
        sendHeartbeat();
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
                    if (roleId == null) {
                        try { resolveRole(); } catch (Exception e) { error("Rôle introuvable : " + e.getMessage()); }
                    }
                    resolveStaffRoles();
                    registerSlashCommandsWithRetry(generation.get(), 0);
                    fullSync();
                });
            }
            case "GUILD_MEMBER_UPDATE" -> {
                JsonObject o = d.getAsJsonObject();
                if (cfg.idServeurDiscord.equals(str(o, "guild_id"))) exec.execute(() -> applyMember(o, true));
            }
            case "GUILD_MEMBER_ADD" -> {
                JsonObject o = d.getAsJsonObject();
                if (cfg.idServeurDiscord.equals(str(o, "guild_id"))) exec.execute(() -> {
                    giveNewcomerRole(o);
                    handleStreamerMember(o);
                });
            }
            case "GUILD_MEMBER_REMOVE" -> {
                JsonObject o = d.getAsJsonObject();
                if (cfg.idServeurDiscord.equals(str(o, "guild_id")) && cfg.retirerSiQuitte) {
                    String id = o.getAsJsonObject("user").get("id").getAsString();
                    exec.execute(() -> {
                        removeMember(id, "a quitté le Discord");
                        StreamerStore.remove(id);
                        StreamerStore.unmarkWelcomeSent(id);
                    });
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
        if (roleId == null) { error("Synchronisation annulée : rôle « " + cfg.nomRole + " » introuvable"); return; }
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
        handleStreamerMember(member);
        if (roleId == null) return;

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

    /** Crée les commandes Discord ; en cas d'échec, réessaie toutes les minutes tant que le bot est connecté. */
    private void registerSlashCommandsWithRetry(int gen, int attempt) {
        if (!running || gen != generation.get()) return;
        slashOk = registerSlashCommands();
        if (slashOk) {
            CitizenDiscord.LOGGER.info("[CitizenDiscord] Commandes Discord /pseudo et /streamer-inscription à jour");
            return;
        }
        if (attempt == 2) log("⚠️ Impossible de créer les commandes Discord (/pseudo…). Réinvite le bot avec la permission **applications.commands**. Nouvel essai chaque minute.");
        exec.schedule(() -> registerSlashCommandsWithRetry(gen, attempt + 1), 60, TimeUnit.SECONDS);
    }

    private boolean registerSlashCommands() {
        if (appId == null) return false;
        try {
            JsonArray all = new JsonArray();

            // /pseudo : joueurs (tant que leur compte n'est pas validé) + staff (pour n'importe quel membre)
            JsonArray pseudoOpts = new JsonArray();
            pseudoOpts.add(option(3, "pseudo", "Ton pseudo Minecraft PC, ou ton gamertag Xbox / PlayStation / Switch / téléphone", true, 3, 20));
            pseudoOpts.add(platformOption());
            pseudoOpts.add(option(6, "membre", "Staff uniquement : le membre Discord à lier", false, 0, 0));
            all.add(command("pseudo", "Lier ton pseudo Minecraft à la whitelist du serveur Citizen", pseudoOpts, false));

            // /pseudo-voir : staff
            JsonArray voirOpts = new JsonArray();
            voirOpts.add(option(6, "membre", "Le membre Discord", true, 0, 0));
            all.add(command("pseudo-voir", "Staff : voir les pseudos Minecraft liés à un membre", voirOpts, true));

            // /pseudo-retirer : staff
            JsonArray retOpts = new JsonArray();
            retOpts.add(option(6, "membre", "Le membre Discord", true, 0, 0));
            JsonObject quoi = platformOption();
            quoi.addProperty("description", "Quel pseudo retirer (les deux par défaut)");
            retOpts.add(quoi);
            all.add(command("pseudo-retirer", "Staff : délier le pseudo Minecraft d'un membre (le retire de la whitelist)", retOpts, true));

            JsonArray streamerOpts = new JsonArray();
            streamerOpts.add(option(3, "twitch", "Ton identifiant Twitch, sans URL ni @", true, 1, 25));
            all.add(command("streamer-inscription", "Associer ta chaîne Twitch aux annonces de Citizen", streamerOpts, false));
            all.add(command("streamer-retirer", "Retirer ta chaîne Twitch des annonces de Citizen", new JsonArray(), false));

            rest("PUT", "/applications/" + appId + "/guilds/" + cfg.idServeurDiscord + "/commands", all);
            return true;
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Impossible de créer les commandes Discord (le bot a-t-il été invité avec applications.commands ?) : {}", e.getMessage());
            return false;
        }
    }

    private static JsonObject option(int type, String name, String desc, boolean required, int min, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.addProperty("required", required);
        if (min > 0) o.addProperty("min_length", min);
        if (max > 0) o.addProperty("max_length", max);
        return o;
    }

    private static JsonObject platformOption() {
        JsonObject plateforme = option(3, "plateforme", "Sur quoi tu joues (PC par défaut)", false, 0, 0);
        JsonArray choices = new JsonArray();
        choices.add(choice("PC (Java)", "java"));
        choices.add(choice("Console / téléphone (Bedrock)", "console"));
        plateforme.add("choices", choices);
        return plateforme;
    }

    private static JsonObject command(String name, String desc, JsonArray options, boolean staffOnly) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("name", name);
        cmd.addProperty("type", 1);
        cmd.addProperty("description", desc);
        cmd.add("options", options);
        // visible seulement pour « Gérer les rôles » (modifiable dans Paramètres du serveur → Intégrations)
        if (staffOnly) cmd.addProperty("default_member_permissions", String.valueOf(PERM_MANAGE_ROLES));
        return cmd;
    }

    private static JsonObject choice(String name, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", value);
        return o;
    }

    /** Transforme les noms / identifiants de roles_staff en identifiants. */
    private void resolveStaffRoles() {
        if (cfg.rolesStaff == null || cfg.rolesStaff.isEmpty()) { staffRoleIds = Set.of(); return; }
        try {
            JsonArray roles = fetchRoles();
            Set<String> ids = new HashSet<>();
            for (String r : cfg.rolesStaff) {
                if (r == null || r.isBlank()) continue;
                if (r.trim().matches("\\d{15,25}")) { ids.add(r.trim()); continue; }
                String id = findRole(roles, r);
                if (id != null) ids.add(id);
                else CitizenDiscord.LOGGER.warn("[CitizenDiscord] Rôle staff « {} » introuvable (roles_staff)", r);
            }
            staffRoleIds = ids;
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Rôles staff non lus : {}", e.getMessage());
        }
    }

    /** Staff = Administrateur, « Gérer les rôles », ou un des rôles de roles_staff. */
    private boolean isStaff(JsonObject member) {
        try {
            String p = str(member, "permissions");
            if (p != null) {
                long perms = Long.parseLong(p);
                if ((perms & PERM_ADMIN) != 0 || (perms & PERM_MANAGE_ROLES) != 0) return true;
            }
        } catch (NumberFormatException ignored) {}
        if (member.has("roles")) {
            for (JsonElement r : member.getAsJsonArray("roles")) if (staffRoleIds.contains(r.getAsString())) return true;
        }
        return false;
    }

    /** Le compte est validé dès qu'au moins un de ses pseudos est dans la whitelist. */
    private static boolean isValidated(String discordId) {
        return !WhitelistStore.pseudosOf(discordId).isEmpty();
    }

    private JsonObject fetchMember(String userId) {
        try {
            JsonElement e = rest("GET", "/guilds/" + cfg.idServeurDiscord + "/members/" + userId, null);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null; // pas (ou plus) sur le Discord
        }
    }

    private static Map<String, String> options(JsonObject data) {
        Map<String, String> out = new HashMap<>();
        if (data.has("options")) {
            for (JsonElement e : data.getAsJsonArray("options")) {
                JsonObject opt = e.getAsJsonObject();
                out.put(str(opt, "name"), opt.get("value").getAsString().trim());
            }
        }
        return out;
    }

    private void handleInteraction(JsonObject i) {
        try {
            if (i.get("type").getAsInt() != 2) return;
            JsonObject data = i.getAsJsonObject("data");
            String name = str(data, "name");
            if (!"pseudo".equals(name) && !"pseudo-voir".equals(name) && !"pseudo-retirer".equals(name)
                    && !"streamer-inscription".equals(name) && !"streamer-retirer".equals(name)) return;
            defer(i); // répond tout de suite à Discord (« Citizen réfléchit… »), le vrai message arrive juste après
            JsonObject member = i.getAsJsonObject("member");
            if (member == null) { reply(i, "Utilise cette commande sur le serveur Discord Citizen."); return; }
            String authorId = member.getAsJsonObject("user").get("id").getAsString();
            boolean staff = isStaff(member);
            Map<String, String> opts = options(data);

            if (name.startsWith("streamer-")) {
                if (!hasStreamerRole(member)) {
                    reply(i, "❌ Cette commande est réservée aux membres ayant le rôle Streameur.");
                    return;
                }
                if ("streamer-inscription".equals(name)) cmdStreamerRegister(i, authorId, opts.get("twitch"));
                else cmdStreamerRemove(i, authorId);
                return;
            }

            switch (name) {
                case "pseudo" -> cmdPseudo(i, member, authorId, staff, opts);
                case "pseudo-voir" -> {
                    if (!staff) { reply(i, "❌ Commande réservée au staff."); return; }
                    cmdVoir(i, opts.get("membre"));
                }
                case "pseudo-retirer" -> {
                    if (!staff) { reply(i, "❌ Commande réservée au staff."); return; }
                    cmdRetirer(i, authorId, opts.get("membre"), opts.get("plateforme"));
                }
                default -> { }
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Erreur commande Discord", e);
            try { reply(i, "❌ Une erreur est survenue, réessaie dans un instant."); } catch (Exception ignored) {}
        }
    }

    private boolean hasStreamerRole(JsonObject member) {
        String wanted = cfg.idRoleStreameur == null ? "" : cfg.idRoleStreameur.trim();
        if (wanted.isBlank() || member == null || !member.has("roles")) return false;
        for (JsonElement r : member.getAsJsonArray("roles")) if (wanted.equals(r.getAsString())) return true;
        return false;
    }

    /** Envoie une seule fois les instructions Twitch lorsqu'un membre reçoit le rôle Streameur. */
    private void handleStreamerMember(JsonObject member) {
        JsonObject user = member.getAsJsonObject("user");
        if (user == null || (user.has("bot") && user.get("bot").getAsBoolean())) return;
        String id = str(user, "id");
        if (id == null) return;
        if (!hasStreamerRole(member)) {
            StreamerStore.unmarkWelcomeSent(id);
            StreamerStore.remove(id);
            return;
        }
        if (StreamerStore.markWelcomeSent(id)) {
            String message = "🎥 Tu as reçu le rôle Streameur de Citizen !\n"
                    + "Pour relier ta chaîne Twitch aux annonces automatiques, utilise :\n"
                    + "/streamer-inscription twitch:TonPseudoTwitch\n"
                    + "Le bot annoncera le début et la fin de tes lives dans le salon dédié. "
                    + "Tu peux te désinscrire à tout moment avec /streamer-retirer.";
            if (!sendPrivate(id, message)) StreamerStore.unmarkWelcomeSent(id);
        }
    }

    private void cmdStreamerRegister(JsonObject interaction, String discordId, String login) throws Exception {
        if (login == null || !login.matches("[A-Za-z0-9_]{1,25}")) {
            reply(interaction, "Identifiant Twitch invalide. Indique uniquement ton pseudo Twitch, sans URL ni @.");
            return;
        }
        if (!twitch.isConfigured()) {
            reply(interaction, "L'intégration Twitch n'est pas encore configurée par l'administrateur du serveur.");
            return;
        }
        if (!twitch.validLogin(login)) {
            reply(interaction, "Cette chaîne Twitch est introuvable. Vérifie ton identifiant.");
            return;
        }
        StreamerStore.register(discordId, login);
        reply(interaction, "Ta chaîne twitch.tv/" + login + " est enregistrée ! Citizen vérifiera ton live automatiquement et annoncera son début et sa fin.");
    }

    private void cmdStreamerRemove(JsonObject interaction, String discordId) throws Exception {
        if (StreamerStore.remove(discordId)) reply(interaction, "Ta chaîne Twitch a été retirée des annonces Citizen.");
        else reply(interaction, "Aucune chaîne Twitch n'était enregistrée pour ton compte.");
    }

    private void pollTwitchSafely() {
        if (!running || !connected || !twitch.isConfigured()) return;
        for (StreamerStore.Streamer streamer : StreamerStore.all()) {
            if (!running) return;
            try {
                JsonObject stream = twitch.liveStream(streamer.login);
                boolean live = stream != null;
                Boolean previous = StreamerStore.updateLive(streamer.discordId, live);
                // Le premier relevé sert de référence : pas de fausse annonce au démarrage du serveur.
                if (previous != null && previous != live) announceTwitchState(streamer, live, stream);
            } catch (Exception e) {
                CitizenDiscord.LOGGER.warn("[CitizenDiscord] Vérification Twitch impossible pour {} (identifiants ou API à vérifier)", streamer.login);
            }
        }
    }

    private void announceTwitchState(StreamerStore.Streamer streamer, boolean live, JsonObject stream) {
        if (cfg.idSalonAnnoncesLive == null || !cfg.idSalonAnnoncesLive.matches("\\d{15,25}")) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] id_salon_annonces_live est invalide : annonces Twitch ignorées.");
            return;
        }
        String url = "https://www.twitch.tv/" + streamer.login;
        String message;
        if (live) {
            String title = str(stream, "title");
            String game = str(stream, "game_name");
            String viewers = stream.has("viewer_count") ? stream.get("viewer_count").getAsString() : "?";
            message = "🔴 " + streamer.login + " est en LIVE sur Twitch !\n"
                    + (title == null || title.isBlank() ? "" : "🎮 " + title + "\n")
                    + (game == null || game.isBlank() ? "" : "🕹️ " + game + "\n")
                    + "👥 " + viewers + " spectateur(s)\n"
                    + "👉 " + url;
        } else {
            message = streamer.login + " a terminé son live Twitch.\n"
                    + "Merci à ceux qui étaient présents !\n👉 " + url;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("content", message);
            JsonObject mentions = new JsonObject();
            mentions.add("parse", new JsonArray());
            body.add("allowed_mentions", mentions);
            rest("POST", "/channels/" + cfg.idSalonAnnoncesLive + "/messages", body);
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Publication Twitch impossible dans le salon configuré (vérifie les permissions du bot).");
        }
    }

    private void cmdPseudo(JsonObject i, JsonObject author, String authorId, boolean staff, Map<String, String> opts) throws Exception {
        String pseudo = opts.get("pseudo");
        boolean console = "console".equals(opts.get("plateforme"));
        String targetId = opts.getOrDefault("membre", authorId);
        boolean forOther = !targetId.equals(authorId);

        if (forOther && !staff) { reply(i, "❌ Seul le staff peut lier le pseudo d'un autre membre."); return; }
        if (!staff && isValidated(authorId)) {
            reply(i, "🔒 Ton compte est déjà validé (" + String.join(", ", WhitelistStore.pseudosOf(authorId))
                    + "). Pour changer ou ajouter un pseudo, contacte le **staff**.");
            return;
        }
        if (pseudo == null || !ANY_NAME.matcher(pseudo).matches() || (!console && !MC_NAME.matcher(pseudo).matches())) {
            reply(i, console
                    ? "❌ Gamertag invalide (3 à 20 caractères : lettres, chiffres, espaces, _)."
                    : "❌ Pseudo invalide (3 à 16 caractères : lettres, chiffres, _). Console ? Ajoute `plateforme: Console`.");
            return;
        }
        JsonObject target = forOther ? fetchMember(targetId) : author;
        if (target == null) { reply(i, "❌ Ce membre n'est pas sur le serveur Discord."); return; }
        JsonObject tu = target.getAsJsonObject("user");
        if (tu != null && tu.has("bot") && tu.get("bot").getAsBoolean()) { reply(i, "❌ Impossible de lier un bot."); return; }

        String owner = WhitelistStore.ownerOf(pseudo);
        if (owner != null && !owner.equals(targetId)) {
            reply(i, staff
                    ? "❌ **" + pseudo + "** est déjà lié à <@" + owner + ">. Fais d'abord `/pseudo-retirer` sur ce membre."
                    : "❌ Le pseudo **" + pseudo + "** est déjà lié à un autre membre. Contacte le staff si c'est bien le tien.");
            return;
        }

        WhitelistStore.Link old = WhitelistStore.link(targetId);
        String previous = old == null ? null : (console ? old.console : old.java);
        WhitelistStore.setLink(targetId, pseudo, console);
        String plat = console ? "console" : "PC";
        boolean whitelisted = hasRole(target);
        if (whitelisted) applyMember(target, true);

        String who = forOther ? "<@" + targetId + ">" : "Tu";
        String replaced = previous != null && !previous.equalsIgnoreCase(pseudo) ? " (remplace **" + previous + "**)" : "";
        if (whitelisted) {
            reply(i, "✅ **" + pseudo + "** (" + plat + ")" + replaced + " est dans la whitelist"
                    + (forOther ? " pour <@" + targetId + ">." : " ! Tu peux te connecter au serveur Citizen.")
                    + (forOther ? "" : "\n🔒 Ton compte est maintenant validé : pour le modifier, il faudra passer par le staff."));
        } else {
            reply(i, "📜 Pseudo **" + pseudo + "** (" + plat + ")" + replaced + " enregistré. "
                    + (forOther ? who + " sera" : "Tu seras") + " whitelisté dès réception du rôle **" + cfg.nomRole + "**.");
        }
        if (staff && forOther) {
            log("🔗 <@" + authorId + "> a lié **" + pseudo + "** (" + plat + ")" + replaced + " à <@" + targetId + ">");
            if (cfg.messagePriveLien != null && !cfg.messagePriveLien.isBlank())
                sendPrivate(targetId, cfg.messagePriveLien.replace("%pseudo%", pseudo).replace("%plateforme%", plat));
        }
    }

    private void cmdVoir(JsonObject i, String targetId) throws Exception {
        if (targetId == null) { reply(i, "❌ Indique un membre."); return; }
        WhitelistStore.Link l = WhitelistStore.link(targetId);
        List<String> wl = WhitelistStore.pseudosOf(targetId);
        reply(i, "📜 <@" + targetId + ">\n"
                + "• PC : " + (l != null && l.java != null ? "**" + l.java + "**" : "—") + "\n"
                + "• Console : " + (l != null && l.console != null ? "**" + l.console + "**" : "—") + "\n"
                + "• Whitelist : " + (wl.isEmpty() ? "non validé" : "✅ " + String.join(", ", wl)));
    }

    private void cmdRetirer(JsonObject i, String authorId, String targetId, String plateforme) throws Exception {
        if (targetId == null) { reply(i, "❌ Indique un membre."); return; }
        boolean java = plateforme == null || "java".equals(plateforme);
        boolean console = plateforme == null || "console".equals(plateforme);
        List<String> unlinked = WhitelistStore.clearLink(targetId, java, console);

        // la whitelist suit les pseudos restants (si le membre a toujours le rôle)
        JsonObject target = fetchMember(targetId);
        List<String> keep = target != null && hasRole(target) ? WhitelistStore.linkedPseudos(targetId) : List.of();
        List<List<String>> res = WhitelistStore.syncDiscordMember(targetId, keep);
        CitizenDiscord.kickOnline(res.get(1));

        if (unlinked.isEmpty() && res.get(1).isEmpty()) { reply(i, "ℹ️ Aucun pseudo lié à <@" + targetId + ">."); return; }
        List<String> all = new ArrayList<>(unlinked);
        for (String r : res.get(1)) if (all.stream().noneMatch(r::equalsIgnoreCase)) all.add(r);
        reply(i, "❌ " + String.join(", ", all) + " délié(s) de <@" + targetId + "> et retiré(s) de la whitelist."
                + " Le membre peut de nouveau utiliser /pseudo.");
        log("➖ <@" + authorId + "> a délié **" + String.join(", ", all) + "** de <@" + targetId + ">");
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
