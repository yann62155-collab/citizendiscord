package fr.citizen.discord;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.util.List;

@Mod(CitizenDiscord.MODID)
public class CitizenDiscord {

    public static final String MODID = "citizendiscord";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static MinecraftServer server;
    private static DiscordBot bot;

    public CitizenDiscord(IEventBus modBus) {
        DiscordConfig.load();
        WhitelistStore.load();
        StreamerStore.load();
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    public static MinecraftServer server() { return server; }
    public static DiscordBot bot() { return bot; }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        if (!server.isDedicatedServer()) return; // pas de bot en solo
        startBot();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        stopBot();
        server = null;
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CDiscordCommand.register(event.getDispatcher());
    }

    public static synchronized void startBot() {
        stopBot();
        DiscordConfig c = DiscordConfig.get();
        if (!c.tokenOk() || c.idServeurDiscord.isBlank()) {
            LOGGER.warn("[CitizenDiscord] Bot Discord non démarré : remplis token_bot et id_serveur_discord dans config/citizendiscord/config.json");
            return;
        }
        bot = new DiscordBot(c);
        bot.start();
    }

    public static synchronized void stopBot() {
        if (bot != null) {
            bot.stop();
            bot = null;
        }
    }

    /** Message affiché à un joueur non whitelisté (PC : lien cliquable / bouton ; console : lien en clair). */
    public static Component kickMessage(String pseudo) {
        DiscordConfig c = DiscordConfig.get();
        MutableComponent msg = Component.empty();
        List<String> lines = c.messageKick;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replace("%pseudo%", pseudo);
            if (line.contains("%lien%")) {
                String[] parts = line.split("%lien%", -1);
                msg.append(Component.literal(parts[0]));
                msg.append(Component.literal(c.lienDiscord).withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, c.lienDiscord))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Ouvrir le Discord")))));
                if (parts.length > 1) msg.append(Component.literal(parts[1]));
            } else {
                msg.append(Component.literal(line));
            }
            if (i < lines.size() - 1) msg.append(Component.literal("\n"));
        }
        return msg;
    }

    /** Exclut (sur le thread serveur) les joueurs connectés qui portent ces pseudos. */
    public static void kickOnline(List<String> pseudos) {
        MinecraftServer s = server;
        if (s == null || pseudos.isEmpty() || !DiscordConfig.get().exclureSiRetire) return;
        s.execute(() -> {
            for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) {
                String n = WhitelistStore.normalize(p.getGameProfile().getName());
                for (String ps : pseudos) {
                    if (WhitelistStore.normalize(ps).equals(n)
                            && !(DiscordConfig.get().opsAutorises && s.getPlayerList().isOp(p.getGameProfile()))) {
                        p.connection.disconnect(Component.literal(DiscordConfig.get().messageRetire));
                    }
                }
            }
        });
    }
}
