package fr.cobblepulse.battletower.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

/** Appels directs a l'API LuckPerms. Ne jamais referencer cette classe si LuckPerms est absent. */
final class LuckPermsHook {
    private LuckPermsHook() {}

    static Boolean check(ServerPlayer p, String node) {
        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(p.getUUID());
        if (user == null) return null;
        Tristate t = user.getCachedData().getPermissionData().checkPermission(node);
        return t == Tristate.UNDEFINED ? null : t.asBoolean();
    }
}
