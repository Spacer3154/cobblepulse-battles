package fr.cobblepulse.battletower;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import fr.cobblepulse.battletower.battle.TowerBattle;
import fr.cobblepulse.battletower.command.Commands;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Players;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.economy.Shop;
import fr.cobblepulse.battletower.integration.PlaceholderBridge;
import fr.cobblepulse.battletower.net.EditorService;
import fr.cobblepulse.battletower.npc.Skins;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.storage.DataService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CobblePulse Battletower : Tour de Combat 50 etages, Boss du Jour, Battle Points, boutique et succes. */
public final class Battletower implements ModInitializer {
    public static final String MOD_ID = "cobblepulse_battles";
    /** Espace de noms des ressources (textures, langues) conserve depuis Battletower. */
    public static final String ASSET_NS = "cobblepulse_battletower";
    public static final Logger LOG = LoggerFactory.getLogger("Battletower");
    private static MinecraftServer server;
    private static boolean ready;
    private static final java.util.Map<java.util.UUID, Long> LAST_INTERACT = new java.util.concurrent.ConcurrentHashMap<>();

    public static MinecraftServer server() {
        return server;
    }

    public static boolean ready() {
        return ready;
    }

    @Override
    public void onInitialize() {
        Cfg.loadAll();
        fr.cobblepulse.battles.tool.NpcEditorTool.register();
        Skins.load();
        EditorService.registerPayloads();
        EditorService.registerReceivers();
        fr.cobblepulse.battletower.net.Net.registerTypes();
        fr.cobblepulse.battletower.net.Net.registerServer();
        // Saisie dans le chat (joueurs sans le mod cote client) : la reponse n'est pas diffusee
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                !fr.cobblepulse.battletower.gui.ChatInput.intercept(sender, message.signedContent()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            Commands.register(dispatcher);
            fr.cobblepulse.battles.ranked.RankedCommands.register(dispatcher);
            fr.cobblepulse.battles.trainers.TrainerCommands.register(dispatcher);
        });
        fr.cobblepulse.battles.ranked.Ranked.init();
        ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STARTED.register(Battletower::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(Battletower::onStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        ServerTickEvents.END_SERVER_TICK.register(Scheduler::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (ready) {
                TowerManager.onJoin(handler.getPlayer());
                fr.cobblepulse.battles.ranked.Ranked.onJoin(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            fr.cobblepulse.battletower.gui.Screens.forget(handler.getPlayer().getUUID());
            fr.cobblepulse.battletower.gui.ChatInput.clear(handler.getPlayer().getUUID());
            if (ready) {
                fr.cobblepulse.battles.ranked.Ranked.onQuit(handler.getPlayer());
                TowerManager.onQuit(handler.getPlayer());
            }
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            // Editeur de PNJ en main : edition au lieu de l'interaction normale (combat, boutique, dialogue de Cobblemon)
            if (entity instanceof NPCEntity && fr.cobblepulse.battles.tool.NpcEditorTool.is(player.getMainHandItem())) {
                if (level.isClientSide() || !(player instanceof ServerPlayer tp)) return InteractionResult.SUCCESS;
                if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
                long t = tp.serverLevel().getGameTime();
                Long prev = LAST_INTERACT.put(tp.getUUID(), t);
                if (prev != null && t - prev < 5) return InteractionResult.SUCCESS;
                fr.cobblepulse.battles.tool.NpcEditorTool.onNpc(tp, (NPCEntity) entity);
                return InteractionResult.SUCCESS;
            }
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !TrainerNpcs.isOurs(entity)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
            // Un clic droit envoie deux paquets (interact + interactAt) : un seul traitement par clic
            long now = sp.serverLevel().getGameTime();
            Long last = LAST_INTERACT.put(sp.getUUID(), now);
            if (last != null && now - last < 5) return InteractionResult.SUCCESS;
            onNpcInteract(sp, (NPCEntity) entity);
            return InteractionResult.SUCCESS;
        });
        // Le skin d'un PNJ arrive avec le paquet d'apparition, avant que le client de Cobblemon ne soit pret a
        // l'enregistrer (le PNJ s'affiche alors en Steve). On le renvoie a chaque joueur peu apres l'apparition.
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (TrainerNpcs.isOurs(entity)) Scheduler.later(10, () -> Skins.resend((NPCEntity) entity, player));
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (ready && TrainerNpcs.isOurs(entity)) Scheduler.later(2, () -> TrainerNpcs.refresh((NPCEntity) entity));
        });
        TowerBattle.register();
        PlaceholderBridge.init();
        LOG.info("[Battles] CobblePulse-Battles initialise");
    }

    private static void onNpcInteract(ServerPlayer p, NPCEntity npc) {
        if (fr.cobblepulse.battles.trainers.WorldTrainers.isWorld(npc)) {
            var w = fr.cobblepulse.battles.trainers.WorldTrainers.of(npc);
            if (w == null) return;
            if (p.isShiftKeyDown() && fr.cobblepulse.battletower.core.Perms.admin(p, fr.cobblepulse.battles.trainers.TrainerCommands.PERM_ADMIN)) {
                fr.cobblepulse.battletower.net.EditorService.openWorld(p, w.id);
            } else {
                fr.cobblepulse.battles.trainers.TrainerMenus.challenge(p, w);
            }
            return;
        }
        if (npc.getTags().contains(TrainerNpcs.TAG_SHOP)) {
            Shop.open(p);
            return;
        }
        if (npc.getTags().contains(TrainerNpcs.TAG_BOSS)) {
            if (TowerManager.inRun(p.getUUID())) TowerManager.engage(p);
            else DailyBoss.start(p);
            return;
        }
        int floor = TrainerNpcs.floorOf(npc);
        if (floor > 0) {
            var run = TowerManager.run(p.getUUID());
            if (run != null && !run.daily && run.floor == floor) TowerManager.engage(p);
            else fr.cobblepulse.battletower.core.Msg.send(p, "tower.npc_not_yours", "floor", floor);
        }
    }

    private static void onStarted(MinecraftServer s) {
        server = s;
        long t0 = System.currentTimeMillis();
        DataService.start();
        PlayerStore.load();
        TowerConfig.load();
        TowerManager.load();
        DailyBoss.init();
        fr.cobblepulse.battles.trainers.WorldTrainers.load();
        fr.cobblepulse.battles.ranked.Ranked.onStarted(s);
        ready = true;
        LOG.info("[Battletower] Pret en {} ms ({} etages, {} tiers depuis {})", System.currentTimeMillis() - t0, Cfg.floors(), Cfg.TIERS.size(), Cfg.tiersSource);
    }

    private static void onStopping(MinecraftServer s) {
        try {
            TowerManager.onServerStopping();
            fr.cobblepulse.battles.ranked.Ranked.onStopping();
            DataService.shutdown();
        } finally {
            ready = false;
            Scheduler.clear();
            TowerBattle.clear();
            TowerManager.clearRuntime();
            PlayerStore.clear();
            fr.cobblepulse.battles.ranked.Ranked.afterStop();
        }
    }

    /** /battletower admin reload : configurations TOML, floors.json, bosses.json, skins. */
    public static void reload() {
        Cfg.loadAll();
        Skins.load();
        TowerConfig.load();
        DailyBoss.rotateIfNeeded(true);
        fr.cobblepulse.battles.trainers.WorldTrainers.load();
        if (server != null) fr.cobblepulse.battles.trainers.WorldTrainers.refreshAll(server);
        fr.cobblepulse.battles.ranked.Ranked.reload();
        if (server != null) TrainerNpcs.refreshAll(server);
    }

    public static boolean isTestPlayer(ServerPlayer p) {
        return Players.isTest(p.getUUID());
    }
}
