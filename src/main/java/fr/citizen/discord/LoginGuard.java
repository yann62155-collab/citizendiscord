package fr.citizen.discord;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserWhiteListEntry;

import java.net.SocketAddress;

/**
 * Appelé pendant la phase de connexion (avant l'entrée dans le monde) :
 * aucun message « X a rejoint la partie » pour un joueur refusé.
 * Fonctionne pour les joueurs PC et console (Geyser/ViaProxy arrivent ici comme un joueur Java).
 */
public final class LoginGuard {

    private LoginGuard() {}

    public static Component check(PlayerList list, SocketAddress addr, GameProfile profile, Component vanillaResult) {
        DiscordConfig c = DiscordConfig.get();
        if (!c.whitelistActive || list.getServer() == null || !list.getServer().isDedicatedServer()) return vanillaResult;

        boolean vanillaRefusedWhitelist = vanillaResult != null
                && vanillaResult.getContents() instanceof TranslatableContents tc
                && "multiplayer.disconnect.not_whitelisted".equals(tc.getKey());

        if (vanillaResult != null && !vanillaRefusedWhitelist) return vanillaResult; // ban, serveur plein… : on ne touche pas

        boolean allowed = (c.opsAutorises && list.isOp(profile)) || WhitelistStore.contains(profile.getName());
        if (!allowed) {
            CitizenDiscord.LOGGER.info("[CitizenDiscord] Connexion refusée (pas whitelist) : {}", profile.getName());
            return CitizenDiscord.kickMessage(profile.getName());
        }
        if (vanillaRefusedWhitelist) {
            // whitelist vanilla activée dans server.properties : on y ajoute le joueur puis on refait les vérifications
            list.getWhiteList().add(new UserWhiteListEntry(profile));
            return list.canPlayerLogin(addr, profile);
        }
        return null;
    }
}
