package fr.citizen.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fichier config/citizendiscord/config.json (modifiable par FTP, puis /cdiscord reload). */
public class DiscordConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static DiscordConfig instance = new DiscordConfig();

    @SerializedName("whitelist_active")
    public boolean whitelistActive = true;

    @SerializedName("token_bot")
    public String tokenBot = "COLLE_ICI_LE_TOKEN_DU_BOT";

    @SerializedName("id_serveur_discord")
    public String idServeurDiscord = "";

    @SerializedName("nom_role")
    public String nomRole = "Aventurier";

    @SerializedName("id_role")
    public String idRole = "";

    /** Rôle donné automatiquement à chaque nouvel arrivant sur le Discord. */
    @SerializedName("role_nouveaux_actif")
    public boolean roleNouveauxActif = true;

    @SerializedName("nom_role_nouveaux")
    public String nomRoleNouveaux = "Nouveau";

    @SerializedName("id_role_nouveaux")
    public String idRoleNouveaux = "";

    @SerializedName("lien_discord")
    public String lienDiscord = "https://discord.gg/TON_INVITATION";

    @SerializedName("id_salon_logs")
    public String idSalonLogs = "";

    /** Intégration Twitch : rôle autorisé à s'inscrire et salon des annonces. */
    @SerializedName("id_role_streameur")
    public String idRoleStreameur = "1574470919462592644";

    @SerializedName("id_salon_annonces_live")
    public String idSalonAnnoncesLive = "1443225866220863601";

    /** Identifiants d'application Twitch (jamais affichés dans les logs ni envoyés à Discord). */
    @SerializedName("twitch_client_id")
    public String twitchClientId = "";

    @SerializedName("twitch_client_secret")
    public String twitchClientSecret = "";

    @SerializedName("twitch_intervalle_verification_secondes")
    public int twitchIntervalleVerificationSecondes = 60;

    /** false : seul /pseudo compte (le surnom Discord n'est souvent pas le pseudo Minecraft). */
    @SerializedName("deviner_pseudo_depuis_surnom")
    public boolean pseudoDepuisSurnom = false;

    /**
     * Rôles Discord (noms ou identifiants) considérés comme staff, en plus des membres
     * qui ont la permission « Gérer les rôles » ou « Administrateur ».
     * Le staff peut utiliser /pseudo pour un autre membre, même après validation.
     */
    @SerializedName("roles_staff")
    public List<String> rolesStaff = new ArrayList<>();

    /**
     * Roles Discord de metier, donnes automatiquement d'apres les entreprises du joueur dans Citizen Economy.
     * DESACTIVE par defaut. Le bot ne cree JAMAIS de role et ne touche qu'aux roles listes ci-dessous (nom ou identifiant) :
     * il faut donc les avoir crees a la main, et placer le role du bot AU-DESSUS d'eux.
     */
    @SerializedName("roles_metiers_actif")
    public boolean rolesMetiersActif = false;

    @SerializedName("roles_metiers")
    public Map<String, String> rolesMetiers = defaultJobRoles();

    static Map<String, String> defaultJobRoles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("forgeron", "Forgeron");
        m.put("menuisier", "Menuisier");
        m.put("restaurant", "Restaurateur");
        m.put("taverne", "Tavernier");
        m.put("potionniste", "Potionniste");
        m.put("poissonnier", "Poissonnier");
        return m;
    }

    @SerializedName("message_prive_lien")
    public String messagePriveLien = "⚜️ Le staff de **Citizen** a lié ton compte Minecraft **%pseudo%** (%plateforme%) à ton Discord.";

    @SerializedName("message_prive_role")
    public String messagePriveRole = "⚜️ Bienvenue chez **Citizen** ! Tu as reçu le rôle **Aventurier**.\n"
            + "Pour être ajouté à la whitelist, tape sur le serveur Discord :\n"
            + "• `/pseudo pseudo:TonPseudoMinecraft` si tu joues sur **PC**\n"
            + "• `/pseudo pseudo:TonGamertag plateforme:Console` si tu joues sur **Xbox / PlayStation / Switch / téléphone**\n"
            + "Sur Bedrock avec Geyser, ajoute le préfixe `.` devant ton gamertag (ex. `.Yann`).\n"
            + "Ensuite, connecte-toi au serveur !";

    @SerializedName("retirer_si_role_retire")
    public boolean retirerSiRoleRetire = true;

    @SerializedName("retirer_si_quitte_le_discord")
    public boolean retirerSiQuitte = true;

    @SerializedName("exclure_si_retire")
    public boolean exclureSiRetire = true;

    @SerializedName("ops_toujours_autorises")
    public boolean opsAutorises = true;

    /** Préfixes ajoutés aux pseudos console par Geyser / Floodgate / ViaProxy. */
    @SerializedName("prefixes_console")
    public List<String> prefixesConsole = new ArrayList<>(List.of(".", "*", "BE_"));

    @SerializedName("message_kick")
    public List<String> messageKick = new ArrayList<>(List.of(
            "§6§l⚜ CITIZEN ⚜",
            "",
            "§cTu n'es pas encore dans la whitelist !",
            "",
            "§fPour jouer, passe la whitelist sur notre Discord :",
            "%lien%",
            "",
            "§7Ton pseudo : §e%pseudo%",
            "§7Sur Discord, tape §e/pseudo§7 avec ce pseudo, puis attends le rôle §6Aventurier§7.",
            "§7Ensuite, reconnecte-toi !"
    ));

    @SerializedName("message_retire")
    public String messageRetire = "§cTu as perdu le rôle Aventurier sur Discord : tu n'es plus dans la whitelist.";

    public static DiscordConfig get() { return instance; }

    public static Path dir() { return FMLPaths.CONFIGDIR.get().resolve("citizendiscord"); }

    public static synchronized void load() {
        Path file = dir().resolve("config.json");
        try {
            Files.createDirectories(dir());
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    DiscordConfig c = GSON.fromJson(r, DiscordConfig.class);
                    if (c != null) {
                        if (c.rolesStaff == null) c.rolesStaff = new ArrayList<>();
                        if (c.rolesMetiers == null) c.rolesMetiers = defaultJobRoles();
                        instance = c;
                    }
                }
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible de lire config.json", e);
        }
        save(); // réécrit le fichier pour ajouter les nouvelles options éventuelles
    }

    /** Restreint les fichiers de configuration aux droits du propriétaire sur les systèmes POSIX. */
    public static void restrictFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows applique ses ACL NTFS ; les droits doivent être limités au compte du serveur.
        } catch (Exception e) {
            CitizenDiscord.LOGGER.warn("[CitizenDiscord] Impossible de restreindre les permissions de {}", file.getFileName());
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(dir().resolve("config.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
            restrictFile(dir().resolve("config.json"));
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible d'écrire config.json", e);
        }
    }

    public boolean tokenOk() {
        return tokenBot != null && tokenBot.length() > 30 && !tokenBot.startsWith("COLLE_ICI");
    }
}
