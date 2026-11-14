package fr.cobblepulse.battletower.core;

import fr.cobblepulse.battletower.integration.LuckPermsBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Permissions LuckPerms du cahier des charges. Sans LuckPerms (ou noeud non defini) :
 * noeuds joueurs autorises, VIP refuses, staff = operateurs niveau 2, admin = operateurs niveau 3.
 */
public final class Perms {
    public static final String USE = "cobblepulse.battletower.use";
    public static final String SHOP = "cobblepulse.battletower.shop";
    public static final String VIP_BOOST = "cobblepulse.battletower.vip.bp_boost";
    public static final String STAFF_DISQUALIFY = "cobblepulse.battletower.staff.disqualify";
    public static final String STAFF_RESET_COOLDOWN = "cobblepulse.battletower.staff.resetcooldown";
    public static final String ADMIN = "cobblepulse.battletower.admin";
    public static final String ADMIN_TOGGLE = "cobblepulse.battletower.admin.toggle";
    public static final String ADMIN_NPC = "cobblepulse.battletower.admin.npc";
    public static final String ADMIN_SHOP = "cobblepulse.battletower.admin.shop";
    public static final String ADMIN_BOSS = "cobblepulse.battletower.admin.boss";
    public static final String ADMIN_RELOAD = "cobblepulse.battletower.admin.reload";
    public static final String ADMIN_BP = "cobblepulse.battletower.admin.bp";

    private Perms() {}

    public static boolean player(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v == null || v;
    }

    public static boolean vip(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v != null && v;
    }

    public static boolean staff(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        if (v != null) return v;
        Boolean admin = LuckPermsBridge.check(p, ADMIN + ".*");
        return admin != null ? admin : p.hasPermissions(2);
    }

    /** Noeud admin : le noeud precis, sinon le joker admin.*, sinon operateur niveau 3. */
    public static boolean admin(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        if (v != null) return v;
        Boolean all = LuckPermsBridge.check(p, ADMIN + ".*");
        return all != null ? all : p.hasPermissions(3);
    }

    public static boolean isAdmin(ServerPlayer p) {
        return admin(p, ADMIN_NPC) || admin(p, ADMIN_TOGGLE);
    }

    public static Predicate<CommandSourceStack> playerReq(String node) {
        return src -> !src.isPlayer() || player(src.getPlayer(), node);
    }

    public static Predicate<CommandSourceStack> staffReq(String node) {
        return src -> !src.isPlayer() || staff(src.getPlayer(), node);
    }

    public static Predicate<CommandSourceStack> adminReq(String node) {
        return src -> !src.isPlayer() || admin(src.getPlayer(), node);
    }
}
