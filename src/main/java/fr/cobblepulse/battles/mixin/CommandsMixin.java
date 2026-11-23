package fr.cobblepulse.battles.mixin;

import com.mojang.brigadier.ParseResults;
import fr.cobblepulse.battles.ranked.Restrictions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Commandes interdites pendant la file et les matchs de la Ranked (restrictions.toml). Les commandes tapees par les
 * joueurs (signees ou non) et celles lancees par performPrefixedCommand passent toutes par performCommand.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void cpbattles$restrict(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        CommandSourceStack source = parse == null ? null : parse.getContext().getSource();
        if (source != null && source.getEntity() instanceof ServerPlayer p && Restrictions.blockCommand(p, command)) ci.cancel();
    }
}
