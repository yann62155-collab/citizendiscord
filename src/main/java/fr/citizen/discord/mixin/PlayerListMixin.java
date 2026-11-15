package fr.citizen.discord.mixin;

import com.mojang.authlib.GameProfile;
import fr.citizen.discord.LoginGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void citizendiscord$whitelist(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        Component result = LoginGuard.check((PlayerList) (Object) this, address, profile, original);
        if (result != original) cir.setReturnValue(result);
    }
}
