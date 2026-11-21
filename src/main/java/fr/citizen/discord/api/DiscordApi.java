package fr.citizen.discord.api;

import fr.citizen.discord.CitizenDiscord;
import fr.citizen.discord.DiscordBot;
import fr.citizen.discord.DiscordConfig;
import fr.citizen.discord.WhitelistStore;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facade stable pour les autres mods Citizen (appelee par reflexion, sans dependance de compilation).
 * Citizen Economy s'en sert pour : connecter les joueurs au portail web avec leur compte Discord et envoyer leurs metiers au bot.
 */
public final class DiscordApi {
    private DiscordApi() { }

    /** Version du contrat (augmente si une signature change). */
    public static int version() { return 1; }

    /** Le bot est-il demarre et connecte ? */
    public static boolean botReady() {
        DiscordBot b = CitizenDiscord.bot();
        return b != null && b.isConnected();
    }

    /** Comptes Discord connus avec leurs pseudos Minecraft (PC et console) : identifiant Discord -> pseudos. */
    public static Map<String, List<String>> allLinks() { return WhitelistStore.allLinks(); }

    /** Meme normalisation que la whitelist : minuscules, sans prefixe console, espaces en « _ ». */
    public static String normalize(String pseudo) { return WhitelistStore.normalize(pseudo); }

    /** Les roles de metier sont-ils actives dans la configuration ? */
    public static boolean jobRolesEnabled() { return DiscordConfig.get().rolesMetiersActif; }

    /** Cles de metier qui ont un role Discord configure. */
    public static Set<String> jobKeys() { return Set.copyOf(DiscordConfig.get().rolesMetiers.keySet()); }

    /**
     * Envoie au bot les metiers de chaque membre : identifiant Discord -> cles de metier (vide = retirer tous les roles de metier).
     * Appel bloquant (quelques secondes) : a lancer hors du thread serveur. @return le nombre de roles ajoutes ou retires
     */
    public static int syncJobs(Map<String, Set<String>> jobsByDiscordId) {
        DiscordBot b = CitizenDiscord.bot();
        return b == null ? 0 : b.syncJobRoles(jobsByDiscordId);
    }
}
