package fr.citizen.discord;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/** /cdiscord … (réservé aux OP niveau 3) */
public final class CDiscordCommand {

    private CDiscordCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("cdiscord")
                .requires(s -> s.hasPermission(3))
                .then(Commands.literal("statut").executes(CDiscordCommand::status))
                .then(Commands.literal("reload").executes(ctx -> {
                    DiscordConfig.load();
                    WhitelistStore.load();
                    if (ctx.getSource().getServer().isDedicatedServer()) CitizenDiscord.startBot();
                    ok(ctx, "§aConfiguration rechargée, bot Discord relancé.");
                    return 1;
                }))
                .then(Commands.literal("sync").executes(ctx -> {
                    DiscordBot bot = CitizenDiscord.bot();
                    if (bot == null || !bot.isConnected()) { ok(ctx, "§cLe bot Discord n'est pas connecté."); return 0; }
                    bot.fullSync();
                    ok(ctx, "§aSynchronisation avec le rôle Discord lancée.");
                    return 1;
                }))
                .then(Commands.literal("whitelist")
                        .then(Commands.literal("on").executes(ctx -> toggle(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> toggle(ctx, false)))
                        .then(Commands.literal("liste").executes(CDiscordCommand::list))
                        .then(Commands.literal("ajouter").then(Commands.argument("pseudo", StringArgumentType.greedyString()).executes(ctx -> {
                            String p = StringArgumentType.getString(ctx, "pseudo");
                            boolean added = WhitelistStore.addManual(p);
                            ok(ctx, added ? "§a" + p + " ajouté à la whitelist." : "§e" + p + " est déjà dans la whitelist.");
                            if (added && CitizenDiscord.bot() != null) CitizenDiscord.bot().log("✅ **" + p + "** ajouté à la whitelist par " + ctx.getSource().getTextName());
                            return 1;
                        })))
                        .then(Commands.literal("retirer").then(Commands.argument("pseudo", StringArgumentType.greedyString()).executes(ctx -> {
                            String p = StringArgumentType.getString(ctx, "pseudo");
                            WhitelistStore.Entry e = WhitelistStore.remove(p);
                            if (e == null) { ok(ctx, "§c" + p + " n'est pas dans la whitelist."); return 0; }
                            ok(ctx, "§a" + e.pseudo + " retiré de la whitelist."
                                    + (WhitelistStore.SOURCE_DISCORD.equals(e.source) ? " §7(il reviendra s'il a toujours le rôle Discord : retire-lui le rôle)" : ""));
                            CitizenDiscord.kickOnline(List.of(e.pseudo));
                            return 1;
                        })))));
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx, boolean on) {
        DiscordConfig.get().whitelistActive = on;
        DiscordConfig.save();
        ok(ctx, on ? "§aWhitelist Citizen activée." : "§eWhitelist Citizen désactivée (tout le monde peut entrer).");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        DiscordConfig c = DiscordConfig.get();
        DiscordBot bot = CitizenDiscord.bot();
        ok(ctx, "§6⚜ Citizen Discord ⚜");
        ok(ctx, "§7Whitelist : " + (c.whitelistActive ? "§aactivée" : "§cdésactivée"));
        ok(ctx, "§7Bot : " + (bot != null && bot.isConnected() ? "§aconnecté (" + bot.botName() + ")" : "§cnon connecté"));
        ok(ctx, "§7Rôle : §f" + c.nomRole + (bot != null && bot.roleId() != null ? " §8(" + bot.roleId() + ")" : " §c(introuvable)"));
        ok(ctx, "§7Joueurs whitelistés : §f" + WhitelistStore.all().size());
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<WhitelistStore.Entry> all = WhitelistStore.all();
        ok(ctx, "§6Whitelist Citizen (" + all.size() + ") :");
        for (WhitelistStore.Entry e : all) {
            ok(ctx, " §f" + e.pseudo + " §8- " + (WhitelistStore.SOURCE_DISCORD.equals(e.source) ? "§9Discord" : "§7manuel") + " §8(" + e.date + ")");
        }
        return 1;
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
