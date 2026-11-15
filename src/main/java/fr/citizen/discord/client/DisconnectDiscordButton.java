package fr.citizen.discord.client;

import fr.citizen.discord.CitizenDiscord;
import net.minecraft.Util;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Côté PC (si le joueur a le mod, ex. via Citizen Updater) : l'écran « Déconnecté » de Minecraft
 * ne rend pas les liens cliquables, donc on ajoute un vrai bouton « Rejoindre le Discord ».
 * Sans le mod (ou sur console), le lien reste affiché en clair dans le message.
 */
@EventBusSubscriber(modid = CitizenDiscord.MODID, value = Dist.CLIENT)
public final class DisconnectDiscordButton {

    private static final Pattern URL = Pattern.compile("https?://(?:www\\.)?(?:discord\\.gg|discord\\.com/invite)/[A-Za-z0-9_-]+");

    private DisconnectDiscordButton() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof DisconnectedScreen screen)) return;
        String url = null;
        int bottom = 0;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof AbstractWidget w) {
                bottom = Math.max(bottom, w.getY() + w.getHeight());
                Matcher m = URL.matcher(w.getMessage().getString());
                if (url == null && m.find()) url = m.group();
            }
        }
        if (url == null) return;
        final String link = url;
        int y = Math.min(bottom + 6, screen.height - 24);
        event.addListener(Button.builder(Component.literal("§bRejoindre le Discord"), b -> Util.getPlatform().openUri(link))
                .bounds(screen.width / 2 - 100, y, 200, 20)
                .build());
    }
}
