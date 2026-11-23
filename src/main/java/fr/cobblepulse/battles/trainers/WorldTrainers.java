package fr.cobblepulse.battles.trainers;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.google.gson.reflect.TypeToken;
import fr.cobblepulse.battles.ranked.MatchManager;
import fr.cobblepulse.battles.ranked.RankedQueue;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.TowerBattle;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Fx;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Rewards;
import fr.cobblepulse.battletower.core.TextUtil;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.economy.BattlePoints;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.storage.JsonStorage;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dresseurs poses n'importe ou dans le monde (hors Tour de Combat) : meme editeur, memes skins et meme IA que la Tour,
 * avec des recompenses a la victoire et une revanche apres un delai.
 */
public final class WorldTrainers {
    public static final String TAG_WORLD = "cpbt_world";

    public static final class WorldTrainer {
        public String id;
        public Loc location;
        public String npcUuid;
        public TrainerSpec trainer = new TrainerSpec();
    }

    private static final Map<String, WorldTrainer> TRAINERS = new LinkedHashMap<>();
    /** Auto-tests : dernier resultat par joueur. */
    public static final Map<String, String> RESULTS = new java.util.concurrent.ConcurrentHashMap<>();

    private WorldTrainers() {}

    static Path file() {
        return Cfg.root().resolve("dresseurs.json");
    }

    public static void load() {
        TRAINERS.clear();
        try {
            if (Files.exists(file())) {
                Map<String, WorldTrainer> m = Json.GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, WorldTrainer>>() {}.getType());
                if (m != null) {
                    for (Map.Entry<String, WorldTrainer> e : m.entrySet()) {
                        WorldTrainer w = e.getValue();
                        if (w == null) continue;
                        w.id = e.getKey();
                        if (w.trainer == null) w.trainer = new TrainerSpec();
                        if (w.trainer.world == null) w.trainer.world = new TrainerSpec.WorldOptions();
                        w.trainer.normalize();
                        TRAINERS.put(w.id, w);
                    }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            Battletower.LOG.error("[Battles] dresseurs.json illisible : {}", e.toString());
        }
    }

    public static void save() {
        try {
            JsonStorage.writeAtomic(file(), Json.GSON.toJson(TRAINERS));
        } catch (Exception e) {
            Battletower.LOG.error("[Battles] Ecriture de dresseurs.json impossible : {}", e.toString());
        }
    }

    public static List<WorldTrainer> all() {
        return new ArrayList<>(TRAINERS.values());
    }

    public static WorldTrainer get(String id) {
        return id == null ? null : TRAINERS.get(id.toLowerCase(Locale.ROOT));
    }

    public static String tagOf(String id) {
        return TAG_WORLD + "_" + id;
    }

    public static boolean isWorld(NPCEntity npc) {
        return npc.getTags().contains(TAG_WORLD);
    }

    public static WorldTrainer of(NPCEntity npc) {
        for (WorldTrainer w : TRAINERS.values()) {
            if (npc.getUUID().toString().equals(w.npcUuid) || npc.getTags().contains(tagOf(w.id))) return w;
        }
        return null;
    }

    public static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /** Nouveau dresseur a la position du joueur. */
    public static WorldTrainer create(ServerPlayer p, String rawId) {
        return create(p, rawId, Loc.of(p));
    }

    /** Nouveau dresseur a une position donnee (Editeur de PNJ : bloc clique). */
    public static WorldTrainer create(ServerPlayer p, String rawId, Loc at) {
        String id = normalizeId(rawId);
        if (id.isEmpty() || TRAINERS.containsKey(id)) return null;
        WorldTrainer w = new WorldTrainer();
        w.id = id;
        w.location = at;
        w.trainer.name = rawId.trim();
        w.trainer.world = new TrainerSpec.WorldOptions();
        w.trainer.normalize();
        TRAINERS.put(id, w);
        spawn(p.server, w);
        save();
        return w;
    }

    public static NPCEntity spawn(MinecraftServer server, WorldTrainer w) {
        if (w.npcUuid != null) TrainerNpcs.remove(server, w.npcUuid, w.location);
        NPCEntity npc = TrainerNpcs.spawn(server, w.location, TrainerNpcs.displayName(w.trainer), w.trainer.skin, TAG_WORLD, tagOf(w.id));
        w.npcUuid = npc == null ? null : npc.getUUID().toString();
        return npc;
    }

    public static NPCEntity npc(MinecraftServer server, WorldTrainer w, boolean respawn) {
        NPCEntity npc = TrainerNpcs.find(server, w.npcUuid, w.location);
        if (npc == null && respawn && w.location != null) {
            npc = spawn(server, w);
            save();
        }
        return npc;
    }

    public static void move(ServerPlayer p, WorldTrainer w) {
        w.location = Loc.of(p);
        spawn(p.server, w);
        save();
    }

    public static void remove(MinecraftServer server, WorldTrainer w) {
        if (w.npcUuid != null) TrainerNpcs.remove(server, w.npcUuid, w.location);
        TRAINERS.remove(w.id);
        save();
    }

    /** Apres l'editeur : nom et skin du PNJ. */
    public static void refresh(MinecraftServer server, WorldTrainer w) {
        NPCEntity npc = npc(server, w, true);
        if (npc != null) TrainerNpcs.configure(npc, TrainerNpcs.displayName(w.trainer), w.trainer.skin);
    }

    public static void refresh(NPCEntity npc) {
        WorldTrainer w = of(npc);
        if (w != null) TrainerNpcs.configure(npc, TrainerNpcs.displayName(w.trainer), w.trainer.skin);
    }

    public static void update(WorldTrainer w, TrainerSpec spec) {
        if (spec.world == null) spec.world = w.trainer.world == null ? new TrainerSpec.WorldOptions() : w.trainer.world;
        spec.normalize();
        w.trainer = spec;
        save();
    }

    // ------------------------------------------------------------------ etat d'un joueur face a un dresseur

    public enum Status { AVAILABLE, REMATCH_REWARD, REMATCH, WAIT_WIN, WAIT_LOSS, DONE }

    public record Availability(Status status, long waitMs) {
        public boolean canFight() {
            return status == Status.AVAILABLE || status == Status.REMATCH || status == Status.REMATCH_REWARD;
        }

        public boolean rewards() {
            return status == Status.AVAILABLE || status == Status.REMATCH_REWARD;
        }
    }

    public static Availability availability(PlayerData d, WorldTrainer w) {
        TrainerSpec.WorldOptions o = w.trainer.world == null ? new TrainerSpec.WorldOptions() : w.trainer.world;
        PlayerData.TrainerRecord r = d.trainers.get(w.id);
        long now = System.currentTimeMillis();
        if (r == null || r.wins == 0) {
            if (r != null && o.lossCooldownMinutes > 0 && r.lastLossAt > 0) {
                long wait = r.lastLossAt + o.lossCooldownMinutes * 60_000L - now;
                if (wait > 0) return new Availability(Status.WAIT_LOSS, wait);
            }
            return new Availability(Status.AVAILABLE, 0);
        }
        if (o.lossCooldownMinutes > 0 && r.lastLossAt > r.lastWinAt) {
            long wait = r.lastLossAt + o.lossCooldownMinutes * 60_000L - now;
            if (wait > 0) return new Availability(Status.WAIT_LOSS, wait);
        }
        if (o.rematchHours < 0) return new Availability(Status.DONE, 0);
        long wait = r.lastWinAt + o.rematchHours * 3_600_000L - now;
        if (wait > 0) return new Availability(Status.WAIT_WIN, wait);
        return new Availability(o.rematchRewards ? Status.REMATCH_REWARD : Status.REMATCH, 0);
    }

    // ------------------------------------------------------------------ combat

    public static void challenge(ServerPlayer p, WorldTrainer w) {
        if (!fr.cobblepulse.battletower.core.Perms.player(p, TrainerCommands.PERM_USE)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        if (TowerManager.inRun(p.getUUID()) || RankedQueue.queued(p.getUUID()) || MatchManager.inMatch(p.getUUID())) {
            Msg.send(p, "trainer.busy_player");
            return;
        }
        PlayerData d = PlayerStore.get(p);
        Availability a = availability(d, w);
        if (!a.canFight()) {
            Msg.send(p, a.status() == Status.DONE ? "trainer.already_beaten" : "trainer.wait", "time", TimeFmt.duration(a.waitMs()));
            return;
        }
        NPCEntity npc = npc(p.server, w, true);
        if (npc == null) {
            Msg.send(p, "trainer.npc_missing");
            return;
        }
        boolean exhibition = w.trainer.world != null && "exhibition".equals(w.trainer.world.mode);
        boolean rewards = a.rewards();
        String err = TowerBattle.start(p, npc, w.trainer, out -> onResult(out, w, rewards), exhibition, exhibition);
        if (err != null) {
            Msg.send(p, "trainer.battle_error", "error", err);
            return;
        }
        if (!w.trainer.introMessage.isBlank()) p.sendSystemMessage(TextUtil.legacy("&e" + w.trainer.display() + " &7: &f" + w.trainer.introMessage));
    }

    private static void onResult(TowerBattle.Outcome out, WorldTrainer w, boolean rewards) {
        ServerPlayer p = fr.cobblepulse.battletower.core.Players.get(out.player());
        PlayerData d = PlayerStore.getOrCreate(out.player(), p == null ? "?" : p.getGameProfile().getName());
        if (out.aborted()) {
            RESULTS.put(out.player().toString(), w.id + ":interrompu");
            return;
        }
        PlayerData.TrainerRecord r = d.trainers.computeIfAbsent(w.id, k -> new PlayerData.TrainerRecord());
        long now = System.currentTimeMillis();
        r.lastBattleAt = now;
        TrainerSpec.WorldOptions o = w.trainer.world == null ? new TrainerSpec.WorldOptions() : w.trainer.world;
        if (out.won()) {
            if (r.wins == 0) r.firstWinAt = now;
            r.wins++;
            r.lastWinAt = now;
            if (p != null) {
                if (!w.trainer.defeatMessage.isBlank()) p.sendSystemMessage(TextUtil.legacy("&e" + w.trainer.display() + " &7: &f" + w.trainer.defeatMessage));
                Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                if (rewards) give(p, d, w, o);
                else Msg.send(p, "trainer.win_no_reward", "trainer", w.trainer.display());
                if (o.rematchHours >= 0) Msg.send(p, "trainer.rematch_in", "time", TimeFmt.duration(o.rematchHours * 3_600_000L));
            }
            RESULTS.put(out.player().toString(), w.id + ":victoire:" + (rewards ? "recompense" : "sans"));
        } else {
            r.losses++;
            r.lastLossAt = now;
            if (p != null) {
                if (!o.winMessage.isBlank()) p.sendSystemMessage(TextUtil.legacy("&e" + w.trainer.display() + " &7: &f" + o.winMessage));
                Msg.send(p, "trainer.lost", "trainer", w.trainer.display());
            }
            RESULTS.put(out.player().toString(), w.id + ":defaite");
        }
        PlayerStore.save(d);
    }

    private static void give(ServerPlayer p, PlayerData d, WorldTrainer w, TrainerSpec.WorldOptions o) {
        List<String> parts = new ArrayList<>();
        if (o.bp > 0) {
            BattlePoints.give(d, o.bp);
            parts.add(o.bp + " BP");
        }
        if (!o.items.isEmpty()) {
            Rewards.giveItems(p, o.items);
            parts.addAll(o.items);
        }
        if (!o.commands.isEmpty()) Rewards.runCommands(p, p.getGameProfile().getName(), o.commands);
        Msg.send(p, "trainer.win_reward", "trainer", w.trainer.display(), "rewards", parts.isEmpty() ? "-" : String.join(", ", parts));
    }

    public static boolean resetPlayer(PlayerData d, String id) {
        boolean changed = id == null ? !d.trainers.isEmpty() : d.trainers.remove(id) != null;
        if (id == null) d.trainers.clear();
        if (changed) PlayerStore.save(d);
        return changed;
    }

    public static void refreshAll(MinecraftServer server) {
        for (WorldTrainer w : TRAINERS.values()) {
            NPCEntity npc = TrainerNpcs.find(server, w.npcUuid, w.location);
            if (npc != null) TrainerNpcs.configure(npc, TrainerNpcs.displayName(w.trainer), w.trainer.skin);
        }
    }

    @SuppressWarnings("unused")
    private static boolean inBattle(ServerPlayer p) {
        return CobblemonBridge.isInBattle(p);
    }
}
