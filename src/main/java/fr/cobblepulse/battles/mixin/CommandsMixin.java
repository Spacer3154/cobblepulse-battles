package fr.cobblepulse.battles.mixin;

import fr.cobblepulse.battles.ranked.Restrictions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Commandes interdites pendant la file et les matchs de la Ranked (restrictions.toml). */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void cpbattles$restrict(CommandSourceStack source, String command, CallbackInfo ci) {
        if (source != null && source.getEntity() instanceof ServerPlayer p && Restrictions.blockCommand(p, command)) ci.cancel();
    }
}
