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
import java.util.ArrayList;
import java.util.List;

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

    @SerializedName("lien_discord")
    public String lienDiscord = "https://discord.gg/TON_INVITATION";

    @SerializedName("id_salon_logs")
    public String idSalonLogs = "";

    /** false : seul /pseudo compte (le surnom Discord n'est souvent pas le pseudo Minecraft). */
    @SerializedName("deviner_pseudo_depuis_surnom")
    public boolean pseudoDepuisSurnom = false;

    @SerializedName("message_prive_role")
    public String messagePriveRole = "⚜️ Bienvenue chez **Citizen** ! Tu as reçu le rôle **Aventurier**.\n"
            + "Pour être ajouté à la whitelist, tape sur le serveur Discord :\n"
            + "• `/pseudo pseudo:TonPseudoMinecraft` si tu joues sur **PC**\n"
            + "• `/pseudo pseudo:TonGamertag plateforme:Console` si tu joues sur **Xbox / PlayStation / Switch / téléphone**\n"
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
                    if (c != null) instance = c;
                }
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible de lire config.json", e);
        }
        save(); // réécrit le fichier pour ajouter les nouvelles options éventuelles
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(dir().resolve("config.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            CitizenDiscord.LOGGER.error("[CitizenDiscord] Impossible d'écrire config.json", e);
        }
    }

    public boolean tokenOk() {
        return tokenBot != null && tokenBot.length() > 30 && !tokenBot.startsWith("COLLE_ICI");
    }
}
