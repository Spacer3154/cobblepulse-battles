package fr.cobblepulse.battletower.npc;

import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.npc.configuration.NPCBattleConfiguration;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.core.TextUtil;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.TowerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * PNJ de la tour (dresseurs d'etage, Boss du Jour, marchands) : entites PNJ de Cobblemon, immobiles et invulnerables,
 * reperees par des tags persistants (cpbt, cpbt_floor_N, cpbt_boss, cpbt_shop).
 */
public final class TrainerNpcs {
    public static final String TAG = "cpbt";
    public static final String TAG_BOSS = "cpbt_boss";
    public static final String TAG_SHOP = "cpbt_shop";

    private TrainerNpcs() {}

    public static String floorTag(int floor) {
        return "cpbt_floor_" + floor;
    }

    public static int floorOf(Entity e) {
        for (String t : e.getTags()) {
            if (t.startsWith("cpbt_floor_")) {
                try {
                    return Integer.parseInt(t.substring(11));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    public static boolean isOurs(Entity e) {
        return e instanceof NPCEntity && e.getTags().contains(TAG);
    }

    private static NPCClass npcClass() {
        NPCClass c = NPCClasses.getByIdentifier(ResourceLocation.fromNamespaceAndPath("cobblemon", "standard"));
        if (c == null) c = NPCClasses.INSTANCE.getByName("standard");
        if (c == null) c = NPCClasses.random();
        return c;
    }

    /** Fait apparaitre un PNJ a la position donnee. */
    public static NPCEntity spawn(MinecraftServer server, Loc loc, String name, String skin, String... tags) {
        ServerLevel level = loc.level(server);
        if (level == null) return null;
        NPCEntity npc = new NPCEntity(level);
        NPCClass cls = npcClass();
        if (cls != null) npc.setNpc(cls);
        npc.moveTo(loc.x, loc.y, loc.z, loc.yaw, 0);
        npc.setYHeadRot(loc.yaw);
        npc.setYBodyRot(loc.yaw);
        try {
            npc.initialize(1);
        } catch (Throwable ignored) {
        }
        configure(npc, name, skin);
        npc.addTag(TAG);
        for (String t : tags) npc.addTag(t);
        if (!level.addFreshEntity(npc)) return null;
        Scheduler.later(5, () -> Skins.apply(npc, skin));
        return npc;
    }

    /** Nom, skin et comportement (immobile, invulnerable, non defiable via Cobblemon). */
    public static void configure(NPCEntity npc, String name, String skin) {
        npc.setMovable(Boolean.FALSE);
        npc.setInvulnerable(Boolean.TRUE);
        npc.setLeashable(Boolean.FALSE);
        npc.setAllowProjectileHits(Boolean.FALSE);
        npc.setPersistenceRequired();
        npc.setCustomName(TextUtil.legacy(name == null ? "" : name));
        npc.setCustomNameVisible(true);
        NPCBattleConfiguration bc = new NPCBattleConfiguration();
        bc.setCanChallenge(false);
        bc.setSimultaneousBattles(false);
        bc.setHealAfterwards(true);
        npc.setBattle(bc);
        Skins.apply(npc, skin);
    }

    /** Retrouve une entite par UUID dans le monde indique (en chargeant le chunk si besoin). */
    public static NPCEntity find(MinecraftServer server, String uuid, Loc loc) {
        if (uuid == null || uuid.isBlank()) return null;
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ServerLevel level = loc == null ? null : loc.level(server);
        if (level != null) {
            Entity e = level.getEntity(id);
            if (e == null) {
                level.getChunk(((int) Math.floor(loc.x)) >> 4, ((int) Math.floor(loc.z)) >> 4);
                e = level.getEntity(id);
            }
            if (e instanceof NPCEntity npc && npc.isAlive()) return npc;
        }
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof NPCEntity npc && npc.isAlive()) return npc;
        }
        return null;
    }

    public static void remove(MinecraftServer server, String uuid, Loc loc) {
        NPCEntity npc = find(server, uuid, loc);
        if (npc != null) npc.discard();
    }

    // ------------------------------------------------------------------ Etages

    public static NPCEntity floorNpc(MinecraftServer server, FloorEntry fe, boolean respawnIfMissing) {
        NPCEntity npc = find(server, fe.npcUuid, fe.npcLocation);
        if (npc == null && respawnIfMissing && fe.npcLocation != null) {
            npc = spawn(server, fe.npcLocation, displayName(fe.trainer), fe.trainer.skin, floorTag(fe.floor));
            if (npc != null) {
                fe.npcUuid = npc.getUUID().toString();
                TowerConfig.saveFloors();
                Battletower.LOG.info("[Battletower] PNJ de l'etage {} recree a {}", fe.floor, fe.npcLocation.pretty());
            }
        }
        return npc;
    }

    public static NPCEntity spawnFloor(MinecraftServer server, FloorEntry fe, Loc loc) {
        TrainerNpcs.remove(server, fe.npcUuid, fe.npcLocation);
        NPCEntity npc = spawn(server, loc, displayName(fe.trainer), fe.trainer.skin, floorTag(fe.floor));
        if (npc != null) {
            fe.npcUuid = npc.getUUID().toString();
            fe.npcLocation = loc.copy();
            TowerConfig.saveFloors();
        }
        return npc;
    }

    public static String displayName(TrainerSpec t) {
        return "&e" + t.display();
    }

    /** Recharge nom et skin depuis la configuration (apres modification de floors.json ou de l'editeur). */
    public static void refresh(NPCEntity npc) {
        if (!isOurs(npc)) return;
        int floor = floorOf(npc);
        if (floor > 0) {
            FloorEntry fe = TowerConfig.floor(floor);
            if (fe != null && npc.getUUID().toString().equals(fe.npcUuid)) configure(npc, displayName(fe.trainer), fe.trainer.skin);
        } else if (npc.getTags().contains(TAG_BOSS)) {
            TrainerSpec t = DailyBoss.current();
            if (t != null) configure(npc, "&c&l" + t.display(), t.skin);
        } else if (npc.getTags().contains(TAG_SHOP)) {
            configure(npc, Cfg.shop.str("menu.npc_name", "&6Marchand de la Tour"), Cfg.shop.str("menu.npc_skin", "builtin:shopkeeper"));
        }
    }

    public static void refreshAll(MinecraftServer server) {
        for (FloorEntry fe : TowerConfig.floors()) {
            NPCEntity npc = find(server, fe.npcUuid, fe.npcLocation);
            if (npc != null) configure(npc, displayName(fe.trainer), fe.trainer.skin);
        }
        TowerData d = TowerConfig.data();
        NPCEntity boss = find(server, d.bossNpcUuid, d.bossNpcLocation);
        if (boss != null) refresh(boss);
        for (TowerData.ShopNpc s : d.shops) {
            NPCEntity npc = find(server, s.uuid, s.location);
            if (npc != null) refresh(npc);
        }
    }
}
