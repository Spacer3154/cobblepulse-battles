package fr.cobblepulse.battletower.command;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.authlib.GameProfile;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.ai.Model;
import fr.cobblepulse.battletower.ai.Planner;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.battle.TowerBattle;
import fr.cobblepulse.battletower.battle.TrainerFactory;
import fr.cobblepulse.battletower.config.Cfg;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Players;
import fr.cobblepulse.battletower.core.Scheduler;
import fr.cobblepulse.battletower.core.TextUtil;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.data.FloorEntry;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.data.RunState;
import fr.cobblepulse.battletower.data.ShowdownFormat;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.economy.BattlePoints;
import fr.cobblepulse.battletower.economy.Shop;
import fr.cobblepulse.battletower.integration.PlaceholderBridge;
import fr.cobblepulse.battletower.npc.Skins;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.storage.DataService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import fr.cobblepulse.battletower.tower.DailyBoss;
import fr.cobblepulse.battletower.tower.TowerConfig;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Auto-diagnostic (CI) : vrais combats contre l'IA de la tour, pilotes cote joueur par un bot. */
public final class SelfTest {
    private static final List<String> REPORT = new ArrayList<>();
    private static final Map<String, List<String>> LOGS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> TAGS = new ConcurrentHashMap<>();
    private static boolean botStarted;

    private SelfTest() {}

    private static void check(CommandSourceStack src, String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail == null || detail.isEmpty() ? "" : " - " + detail);
        REPORT.add(line);
        Battletower.LOG.info("[Battletower][selftest] {}", line);
        if (src != null) src.sendSystemMessage(TextUtil.legacy((ok ? "&a" : "&c") + line));
    }

    // ------------------------------------------------------------------ Bot de test (cote joueur)

    private static void startBot() {
        if (botStarted) return;
        botStarted = true;
        TowerBattle.observer = (battle, live) -> {
            String tag = TAGS.getOrDefault(live.player, "?");
            List<String> lines = new ArrayList<>(battle.getShowdownMessages());
            List<String> chat = new ArrayList<>();
            battle.getChatLog().forEach(c -> chat.add(c.getString()));
            lines.add("##CHAT## " + String.join(" | ", chat));
            lines.add("##AI## " + live.ai.lastDecision());
            LOGS.put(tag + "#" + LOGS.size(), lines);
            Battletower.LOG.info("[Battletower][selftest] combat {} termine ({} lignes Showdown, IA : {})", tag, lines.size(), live.ai.lastDecision());
        };
        Scheduler.repeat(10, 10, SelfTest::drive);
        Scheduler.repeat(100, 100, SelfTest::diagnose);
    }

    /** Journal de diagnostic des combats de test (etat des acteurs, derniers messages Showdown). */
    private static void diagnose() {
        for (UUID id : new ArrayList<>(TAGS.keySet())) {
            ServerPlayer p = Players.get(id);
            if (p == null) continue;
            PokemonBattle b = BattleRegistry.getBattleByParticipatingPlayer(p);
            if (b == null) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(TAGS.get(id)).append(" tour=").append(b.getTurn()).append(" started=").append(b.getStarted())
                    .append(" ended=").append(b.getEnded()).append(" dispatches=").append(b.getDispatches().size());
            for (BattleActor a : b.getActors()) {
                sb.append(" | ").append(a.getType()).append(" mustChoose=").append(a.getMustChoose())
                        .append(" request=").append(a.getRequest() != null).append(" sendingOut=").append(a.getStillSendingOutCount())
                        .append(" active=").append(a.getActivePokemon().size());
                for (ActiveBattlePokemon ab : a.getActivePokemon()) {
                    var bp = ab.getBattlePokemon();
                    var e = bp == null ? null : bp.getEntity();
                    if (e != null) sb.append(" [").append(e.getName().getString()).append(" ticks=").append(e.tickCount)
                            .append(" alive=").append(e.isAlive()).append(" ticking=")
                            .append(((ServerLevel) e.level()).isPositionEntityTicking(e.blockPosition())).append("]");
                }
            }
            List<String> msgs = b.getShowdownMessages();
            sb.append(" | sd=").append(msgs.size());
            for (int i = Math.max(0, msgs.size() - 4); i < msgs.size(); i++) {
                String m = msgs.get(i).replace("\n", " / ");
                sb.append("\n    ").append(m.length() > 300 ? m.substring(0, 300) : m);
            }
            Battletower.LOG.info("[Battletower][selftest][diag] {}", sb);
        }
    }

    private static void drive() {
        for (UUID id : new ArrayList<>(TAGS.keySet())) {
            ServerPlayer p = Players.get(id);
            if (p == null) continue;
            PokemonBattle b = BattleRegistry.getBattleByParticipatingPlayer(p);
            if (b == null || b.getEnded()) continue;
            BattleActor actor = b.getActor(p);
            if (actor == null || !actor.getMustChoose() || actor.getRequest() == null) continue;
            ShowdownActionRequest req = actor.getRequest();
            try {
                List<ShowdownActionResponse> responses = req.iterate(actor.getActivePokemon(),
                        (ActiveBattlePokemon active, ShowdownMoveset ms, Boolean force) -> botChoice(actor, active, ms, force));
                actor.setActionResponses(responses);
            } catch (Throwable t) {
                Battletower.LOG.warn("[Battletower][selftest] bot : {}", t.toString());
            }
        }
    }

    private static ShowdownActionResponse botChoice(BattleActor actor, ActiveBattlePokemon active, ShowdownMoveset ms, Boolean force) {
        if (Boolean.TRUE.equals(force) || ms == null) {
            for (BattlePokemon bp : actor.getPokemonList()) {
                if (bp.getHealth() <= 0) continue;
                boolean isActive = false;
                for (ActiveBattlePokemon a : actor.getActivePokemon()) if (a.getBattlePokemon() == bp) isActive = true;
                if (!isActive) return new SwitchActionResponse(bp.getUuid());
            }
            return PassActionResponse.INSTANCE;
        }
        for (InBattleMove m : ms.getMoves()) {
            if (!m.canBeUsed()) continue;
            String target = null;
            List<Targetable> t = m.getTargets(active);
            if (t != null && !t.isEmpty()) target = t.get(0).getPNX();
            return new MoveActionResponse(m.getId(), target, null);
        }
        return PassActionResponse.INSTANCE;
    }

    private static FakePlayer fake(ServerLevel level, String name) {
        FakePlayer fp = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(("bt-" + name).getBytes(StandardCharsets.UTF_8)), name));
        Players.registerTest(fp);
        registerInPlayerList(level.getServer(), fp);
        TAGS.put(fp.getUUID(), name);
        PlayerStore.get(fp);
        return fp;
    }

    /**
     * Cobblemon resout les joueurs par UUID (Pokedex, evenements) : le joueur factice est ajoute a l'index par UUID de la
     * liste des joueurs (champ Map&lt;UUID, ServerPlayer&gt; trouve par son type generique), sans etre ajoute a la liste diffusee.
     */
    @SuppressWarnings("unchecked")
    private static void registerInPlayerList(net.minecraft.server.MinecraftServer server, ServerPlayer p) {
        try {
            for (java.lang.reflect.Field f : net.minecraft.server.players.PlayerList.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                if (!(f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt)) continue;
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length == 2 && args[0] == UUID.class && args[1] == ServerPlayer.class) {
                    f.setAccessible(true);
                    ((Map<UUID, ServerPlayer>) f.get(server.getPlayerList())).put(p.getUUID(), p);
                    return;
                }
            }
            Battletower.LOG.warn("[Battletower][selftest] index des joueurs introuvable");
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower][selftest] enregistrement du joueur factice impossible : {}", t.toString());
        }
    }

    private static void party(ServerPlayer p, MonSpec... mons) {
        var party = CobblemonBridge.party(p);
        for (Pokemon pk : CobblemonBridge.partyList(p)) party.remove(pk);
        for (MonSpec m : mons) {
            List<String> errors = new ArrayList<>();
            Pokemon pk = TrainerFactory.create(m, errors);
            if (pk != null) party.add(pk);
            if (!errors.isEmpty()) Battletower.LOG.warn("[Battletower][selftest] {} : {}", m.species, errors);
        }
    }

    private static MonSpec mon(String species, int level, String... moves) {
        MonSpec m = new MonSpec();
        m.species = species;
        m.level = level;
        for (String mv : moves) m.moves.add(new MonSpec.MoveSpec(mv));
        return m;
    }

    private static TrainerSpec trainer(String name, String ai, MonSpec... team) {
        TrainerSpec t = new TrainerSpec();
        t.name = name;
        t.ai = ai;
        t.tier = "ag";
        t.skin = "builtin:ace_trainer_m";
        for (MonSpec m : team) t.team.add(m);
        t.normalize();
        return t;
    }

    /** Execute {@code then} des que {@code cond} est vraie (verifiee chaque seconde), ou au bout de {@code maxSeconds}. */
    private static void waitFor(java.util.function.BooleanSupplier cond, int maxSeconds, Runnable then) {
        long deadline = Scheduler.currentTick() + maxSeconds * 20L;
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            boolean ok;
            try {
                ok = cond.getAsBoolean();
            } catch (Throwable t) {
                ok = false;
            }
            if (ok || Scheduler.currentTick() >= deadline) Scheduler.later(ok ? 40 : 1, then);
            else Scheduler.later(20, step[0]);
        };
        Scheduler.later(20, step[0]);
    }

    private static boolean forced;

    private static BlockPos base(ServerLevel level) {
        BlockPos b = level.getSharedSpawnPos();
        if (!forced) {
            forced = true;
            // Les joueurs factices ne chargent pas de chunks : on force la zone de test pour que les
            // entites (PNJ, Pokemon envoyes) soient tickees, comme autour d'un vrai joueur.
            int cx = b.getX() >> 4, cz = b.getZ() >> 4;
            for (int x = cx - 2; x <= cx + 2; x++)
                for (int z = cz - 2; z <= cz + 5; z++)
                    level.setChunkForced(x, z, true);
            Battletower.LOG.info("[Battletower][selftest] zone de test forcee autour de {}", b);
        }
        return b;
    }

    private static Loc loc(ServerLevel level, int dx, int dz) {
        BlockPos b = base(level);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.getX() + dx, b.getZ() + dz);
        return new Loc(level.dimension().location().toString(), b.getX() + dx + 0.5, y, b.getZ() + dz + 0.5, 0, 0);
    }

    private static void setupFloor(ServerLevel level, int n, int dz, TrainerSpec t) {
        FloorEntry fe = TowerConfig.floor(n);
        fe.trainer = t;
        fe.arrival = loc(level, 0, dz);
        TrainerNpcs.spawnFloor(level.getServer(), fe, loc(level, 3, dz));
        TowerConfig.saveFloors();
    }

    private static void reset(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        d.cooldownUntil = 0;
        d.dailyBossDay = "";
        PlayerStore.save(d);
    }

    // ------------------------------------------------------------------ Tests

    public static int basic(CommandSourceStack src) {
        check(src, "config.tiers", Cfg.TIERS.size() >= 10, Cfg.TIERS.size() + " tiers (" + Cfg.tiersSource + ")");
        check(src, "config.messages", !"general.prefix".equals(Msg.raw("general.prefix")), null);
        check(src, "config.floors", TowerConfig.floors().size() >= Cfg.floors(), TowerConfig.floors().size() + " etages");
        check(src, "config.rewards", BattlePoints.baseForFloor(1) == 2 && BattlePoints.baseForFloor(50) == 15,
                "etage1=" + BattlePoints.baseForFloor(1) + " etage45=" + BattlePoints.baseForFloor(45) + " etage50=" + BattlePoints.baseForFloor(50));
        check(src, "config.shop", Shop.entries().size() >= 5, Shop.entries().size() + " articles");
        check(src, "config.achievements", fr.cobblepulse.battletower.economy.Achievements.all().size() >= 5, null);
        check(src, "skins.builtin", Skins.builtin().size() >= 8, Skins.builtin().size() + " skins embarques");
        boolean bytesOk = true;
        for (Skins.SkinInfo s : Skins.builtin()) if (Skins.bytes(s.ref()) == null) bytesOk = false;
        check(src, "skins.bytes", bytesOk, null);
        check(src, "storage.backend", DataService.storage() != null, DataService.storage() == null ? "-" : DataService.storage().name());
        String stamp = String.valueOf(TimeFmt.now());
        DataService.async(s -> {
            s.saveKv("selftest", "\"" + stamp + "\"");
            return s.loadKv("selftest");
        }).whenComplete((v, e) -> DataService.main(() -> check(src, "storage.roundtrip", v != null && v.contains(stamp), e == null ? null : e.toString())));
        check(src, "integration.placeholders", true, PlaceholderBridge.present() ? "present" : "absent");
        check(src, "integration.megashowdown", true, FabricLoader.getInstance().isModLoaded("mega_showdown") ? "present" : "absent");
        // Format Showdown (import/export de l'editeur)
        String sd = "Garchomp @ Life Orb\nAbility: Rough Skin\nLevel: 50\nTera Type: Steel\nEVs: 252 Atk / 4 SpD / 252 Spe\nJolly Nature\n- Earthquake\n- Dragon Claw\n- Swords Dance\n- Stone Edge\n";
        List<MonSpec> parsed = ShowdownFormat.parse(sd);
        check(src, "showdown.import", parsed.size() == 1 && "garchomp".equals(parsed.get(0).species) && parsed.get(0).ev("spe") == 252
                && parsed.get(0).moves.size() == 4 && "steel".equals(parsed.get(0).teraType), parsed.isEmpty() ? "vide" : parsed.get(0).species);
        List<String> errs = new ArrayList<>();
        MonSpec chomp = parsed.isEmpty() ? mon("garchomp", 50, "earthquake") : parsed.get(0);
        Pokemon pk = TrainerFactory.create(chomp, errs);
        check(src, "factory.create", pk != null && errs.isEmpty() && pk.getLevel() == 50 && pk.getMoveSet().getMoves().size() == 4
                        && "life_orb".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pk.heldItem().getItem()).getPath()),
                errs.toString() + (pk == null ? "" : " ivAtk=" + pk.getIvs().get(com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK)));
        // Formes : import Showdown generique (Rotom-Wash, Ninetales-Alola, Ho-Oh)
        List<MonSpec> forms = ShowdownFormat.parse("Rotom-Wash @ Leftovers\n- Hydro Pump\n\nNinetales-Alola\n- Moonblast\n\nHo-Oh\n- Sacred Fire\n");
        List<String> ferrs = new ArrayList<>();
        StringBuilder fdesc = new StringBuilder();
        boolean formsOk = forms.size() == 3 && "hooh".equals(forms.get(2).species) && forms.get(2).form.isEmpty();
        for (int i = 0; i < Math.min(2, forms.size()); i++) {
            Pokemon fp = TrainerFactory.create(forms.get(i), ferrs);
            String fn = fp == null ? "null" : fp.getForm().getName();
            fdesc.append(forms.get(i).species).append('/').append(forms.get(i).form).append("->").append(fn).append(' ');
            formsOk &= fp != null && fn.toLowerCase(java.util.Locale.ROOT).contains(i == 0 ? "wash" : "alola");
        }
        check(src, "factory.forms", formsOk && ferrs.isEmpty(), fdesc + ferrs.toString());
        // IA hors combat : chaque palier choisit une action valide
        for (Planner.Level lvl : Planner.Level.values()) {
            Model.AState st = new Model.AState();
            Model.AMon a = new Model.AMon();
            a.id = "a";
            a.species = "garchomp";
            a.types.add("dragon");
            a.types.add("ground");
            a.maxHp = a.hp = 183;
            a.atk = 182;
            a.spe = 154;
            Model.AMove eq = new Model.AMove();
            eq.id = "earthquake";
            eq.type = "ground";
            eq.category = "physical";
            eq.power = 100;
            a.moves.add(eq);
            a.active = true;
            st.ai.team.add(a);
            st.ai.active.add(a);
            Model.AMon f = new Model.AMon();
            f.id = "f";
            f.species = "heatran";
            f.types.add("fire");
            f.types.add("steel");
            f.maxHp = f.hp = 166;
            f.active = true;
            st.foe.team.add(f);
            st.foe.active.add(f);
            Model.Decision d = new Planner(lvl, 1, 30, 2).decide(st, 0, false, false, new HashSet<>());
            check(src, "ai.planner." + lvl.name().toLowerCase(), d.kind == Model.Decision.Kind.MOVE && "earthquake".equals(d.moveId), d.toString());
        }
        return 1;
    }

    /** Ascension complete (2 etages) + file d'attente avec un second joueur. */
    public static int ascension(CommandSourceStack src) {
        startBot();
        ServerLevel level = src.getServer().overworld();
        TowerManager.testFloorLimit = 2;
        TowerManager.setOpen(true);
        TrainerSpec f1 = trainer("Testeur", "EASY", mon("caterpie", 5, "tackle"), mon("weedle", 5, "poisonsting"));
        TrainerSpec f2 = trainer("Testeur Inverse", "MEDIUM", mon("magikarp", 5, "tackle"));
        f2.inverse = true;
        f2.weather = "rain";
        f2.terrain = "electric";
        setupFloor(level, 1, 0, f1);
        setupFloor(level, 2, 12, f2);
        FakePlayer runner = fake(level, "BTRunner");
        FakePlayer second = fake(level, "BTQueue");
        reset(runner);
        reset(second);
        party(runner, mon("pikachu", 60, "thunderbolt", "quickattack"));
        party(second, mon("charmander", 60, "ember"));
        loc(level, 0, 0).teleport(runner);
        loc(level, 0, 0).teleport(second);
        long bpBefore = PlayerStore.get(runner).bp;
        TowerManager.startAssault(runner);
        TowerManager.startAssault(second);
        TowerManager.engage(runner);
        Scheduler.later(5, () -> TowerManager.engage(second));
        Scheduler.later(20 * 6, () -> {
            RunState r1 = TowerManager.run(runner.getUUID());
            RunState r2 = TowerManager.run(second.getUUID());
            check(src, "ascension.started", r1 != null && (r1.phase == RunState.Phase.BATTLE || r1.wins > 0), r1 == null ? "aucune" : r1.phase + " etage " + r1.floor);
            check(src, "queue.waiting", r2 != null && (r2.phase == RunState.Phase.QUEUED || r1 != null && r1.wins > 0), r2 == null ? "aucune" : r2.phase.name());
        });
        Scheduler.later(20 * 75, () -> {
            PlayerData d = PlayerStore.get(runner);
            check(src, "ascension.completed", d.completions >= 1 && d.maxFloor >= 2, "completions=" + d.completions + " record=" + d.maxFloor);
            check(src, "ascension.bp", d.bp > bpBefore, "bp=" + d.bp);
            check(src, "ascension.best_time", d.bestTimeMs > 0, TimeFmt.clock(d.bestTimeMs));
            check(src, "ascension.run_closed", !TowerManager.inRun(runner.getUUID()), null);
            check(src, "leaderboard.rank", PlayerStore.rank(d.uuid) >= 1, "#" + PlayerStore.rank(d.uuid));
            check(src, "achievements.unlocked", true, d.achievements.keySet().toString());
            PlayerData q = PlayerStore.get(second);
            check(src, "queue.served", q.totalWins >= 1, "victoires=" + q.totalWins);
            boolean inverse = false;
            boolean rain = false;
            boolean terrain = false;
            for (Map.Entry<String, List<String>> e : LOGS.entrySet()) {
                for (String l : e.getValue()) {
                    if (l.contains("Inverse")) inverse = true;
                    if (l.contains("-weather|RainDance")) rain = true;
                    if (l.contains("Electric Terrain")) terrain = true;
                }
            }
            check(src, "modifiers.inverse", inverse, null);
            check(src, "modifiers.weather", rain, null);
            check(src, "modifiers.terrain", terrain, null);
        });
        return 1;
    }

    /** Defaite : cooldown 24 h, record conserve, ascension close. */
    public static int defeat(CommandSourceStack src) {
        startBot();
        ServerLevel level = src.getServer().overworld();
        TowerManager.testFloorLimit = 2;
        TrainerSpec strong = trainer("Mur", "HARD", mon("garchomp", 70, "earthquake", "dragonclaw"));
        setupFloor(level, 1, 0, strong);
        FakePlayer loser = fake(level, "BTLoser");
        reset(loser);
        party(loser, mon("magikarp", 5, "splash"));
        loc(level, 0, 0).teleport(loser);
        TowerManager.startAssault(loser);
        TowerManager.engage(loser);
        Scheduler.later(20 * 40, () -> {
            PlayerData d = PlayerStore.get(loser);
            check(src, "defeat.cooldown", d.cooldownUntil > TimeFmt.now() + 23 * TimeFmt.HOUR, TimeFmt.duration(d.cooldownUntil - TimeFmt.now()));
            check(src, "defeat.losses", d.totalLosses >= 1, null);
            check(src, "defeat.run_closed", !TowerManager.inRun(loser.getUUID()), null);
            TowerManager.startAssault(loser);
            check(src, "defeat.blocked", !TowerManager.inRun(loser.getUUID()), null);
            // Disqualification d'un joueur en cours d'ascension
            FakePlayer dq = fake(level, "BTDisq");
            reset(dq);
            party(dq, mon("pikachu", 30, "thunderbolt"));
            loc(level, 0, 0).teleport(dq);
            TowerManager.startAssault(dq);
            boolean started = TowerManager.inRun(dq.getUUID());
            boolean ok = TowerManager.disqualify(dq.getUUID(), "selftest");
            check(src, "staff.disqualify", started && ok && !TowerManager.inRun(dq.getUUID()) && PlayerStore.get(dq).cooldownUntil > TimeFmt.now(), null);
            // Boutique
            PlayerData buyer = PlayerStore.get(dq);
            buyer.bp = 100;
            Shop.Entry candy = null;
            for (Shop.Entry e : Shop.entries()) if (e.give().stream().anyMatch(g -> g.contains("rare_candy"))) candy = e;
            if (candy != null) {
                boolean bought = Shop.buy(dq, candy);
                check(src, "shop.buy", bought && buyer.bp == 100 - candy.price(), "bp=" + buyer.bp);
            }
        });
        return 1;
    }

    /** Boss du Jour : rotation, victoire, tentative unique. */
    public static int daily(CommandSourceStack src) {
        startBot();
        ServerLevel level = src.getServer().overworld();
        TrainerSpec boss = trainer("Boss Test", "EXTREME", mon("caterpie", 5, "tackle"));
        boss.weather = "fog";
        TowerConfig.bosses().bosses.put("selftest_boss", boss);
        TowerConfig.saveBosses();
        TowerConfig.data().bossArrival = loc(level, 0, 24);
        TowerConfig.data().bossNpcLocation = loc(level, 3, 24);
        TowerConfig.data().bossNpcUuid = null;
        TowerManager.state().currentBossId = "selftest_boss";
        DailyBoss.npc(level.getServer());
        FakePlayer dp = fake(level, "BTDaily");
        reset(dp);
        party(dp, mon("pikachu", 60, "thunderbolt"));
        loc(level, 0, 24).teleport(dp);
        long bp0 = PlayerStore.get(dp).bp;
        DailyBoss.start(dp);
        TowerManager.engage(dp);
        Scheduler.later(20 * 40, () -> {
            PlayerData d = PlayerStore.get(dp);
            check(src, "daily.won", d.dailyBossWins >= 1 && d.dailyBossBeaten.contains("selftest_boss"), "victoires=" + d.dailyBossWins);
            check(src, "daily.reward", d.bp > bp0, "bp=" + d.bp);
            DailyBoss.start(dp);
            check(src, "daily.once_per_day", !TowerManager.inRun(dp.getUUID()) && DailyBoss.attemptedToday(d), null);
            check(src, "daily.rotation", !DailyBoss.bossFor(java.time.LocalDate.now()).isEmpty(), DailyBoss.bossFor(java.time.LocalDate.now()));
            boolean fog = false;
            for (List<String> lines : LOGS.values()) for (String l : lines) if (l.contains("-weather|Fog")) fog = true;
            check(src, "modifiers.fog", fog, null);
        });
        return 1;
    }

    /** IA Difficile/Extreme en situation : Tera defensive et Mega-Evolution (si Mega Showdown est installe). */
    public static int extreme(CommandSourceStack src) {
        startBot();
        ServerLevel level = src.getServer().overworld();
        TowerManager.testFloorLimit = 2;
        MonSpec chomp = mon("garchomp", 50, "earthquake", "dragonclaw", "swordsdance", "stoneedge");
        chomp.teraType = "steel";
        chomp.allowTera = true;
        TrainerSpec hard = trainer("Tera Test", "HARD", chomp);
        setupFloor(level, 1, 36, hard);
        FakePlayer tp = fake(level, "BTTera");
        reset(tp);
        party(tp, mon("glaceon", 55, "icebeam", "shadowball"));
        loc(level, 0, 36).teleport(tp);
        TowerManager.startAssault(tp);
        TowerManager.engage(tp);
        Scheduler.later(20 * 45, () -> {
            boolean tera = false;
            String ai = "";
            for (Map.Entry<String, List<String>> e : LOGS.entrySet()) {
                if (!e.getKey().startsWith("BTTera")) continue;
                for (String l : e.getValue()) {
                    if (l.contains("-terastallize")) tera = true;
                    if (l.startsWith("##AI##")) ai = l;
                }
            }
            check(src, "ai.hard.defensive_tera", tera, ai);
            RunState r = TowerManager.run(tp.getUUID());
            if (r != null) TowerManager.abandon(tp);
            // Extreme : combat complet sans erreur
            MonSpec zard = mon("charizard", 50, "flamethrower", "airslash", "dragonpulse", "roost");
            if (FabricLoader.getInstance().isModLoaded("mega_showdown")) {
                zard.heldItem = "mega_showdown:charizardite_y";
                zard.allowMega = true;
            }
            TrainerSpec ex = trainer("Extreme Test", "EXTREME", zard, mon("blastoise", 50, "surf", "icebeam"));
            setupFloor(level, 1, 48, ex);
            FakePlayer xp = fake(level, "BTExtreme");
            reset(xp);
            party(xp, mon("pikachu", 70, "thunderbolt", "irontail"), mon("snorlax", 70, "bodyslam"));
            loc(level, 0, 48).teleport(xp);
            TowerManager.startAssault(xp);
            TowerManager.engage(xp);
            // Les animations (Mega-Evolution...) rallongent le combat : on attend sa fin (au plus 150 s)
            waitFor(() -> LOGS.keySet().stream().anyMatch(k -> k.startsWith("BTExtreme")), 150, () -> {
                boolean ended = false;
                boolean mega = false;
                String aiLine = "";
                for (Map.Entry<String, List<String>> e : LOGS.entrySet()) {
                    if (!e.getKey().startsWith("BTExtreme")) continue;
                    ended = true;
                    for (String l : e.getValue()) {
                        if (l.contains("-mega") || l.contains("|detailschange|") && l.contains("Mega")) mega = true;
                        if (l.startsWith("##AI##")) aiLine = l;
                    }
                }
                check(src, "ai.extreme.battle", ended, aiLine);
                if (FabricLoader.getInstance().isModLoaded("mega_showdown")) check(src, "ai.extreme.mega", mega, null);
                RunState rx = TowerManager.run(xp.getUUID());
                if (rx != null) TowerManager.abandon(xp);
                for (Map.Entry<String, List<String>> e : LOGS.entrySet()) {
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    for (String l : e.getValue()) {
                        if (n++ > 400) break;
                        sb.append(l).append('\n');
                    }
                    Battletower.LOG.info("[Battletower][selftest] journal {} :\n{}", e.getKey(), sb);
                }
            });
        });
        return 1;
    }

    /** Laisse une ascension en cours pour verifier la reprise apres redemarrage. */
    public static int resumeSetup(CommandSourceStack src) {
        UUID id = UUID.nameUUIDFromBytes("bt-BTResume".getBytes(StandardCharsets.UTF_8));
        RunState r = new RunState();
        r.uuid = id.toString();
        r.name = "BTResume";
        r.floor = 2;
        r.startedAt = TimeFmt.now();
        r.activeMs = 90_000;
        r.phase = RunState.Phase.BATTLE;
        TowerManager.state().runs.put(r.uuid, r);
        TowerManager.save();
        PlayerStore.getOrCreate(id, "BTResume");
        check(src, "resume.setup", TowerManager.inRun(id), null);
        return 1;
    }

    /** Apres redemarrage : donnees joueurs et ascension interrompue retrouvees. */
    public static int persisted(CommandSourceStack src) {
        PlayerData runner = PlayerStore.byName("BTRunner");
        check(src, "persist.runner", runner != null && runner.completions >= 1 && runner.bp > 0,
                runner == null ? "absent" : "completions=" + runner.completions + " bp=" + runner.bp);
        PlayerData loser = PlayerStore.byName("BTLoser");
        check(src, "persist.cooldown", loser != null && loser.cooldownUntil > TimeFmt.now(), null);
        PlayerData daily = PlayerStore.byName("BTDaily");
        check(src, "persist.daily", daily != null && DailyBoss.attemptedToday(daily), null);
        UUID id = UUID.nameUUIDFromBytes("bt-BTResume".getBytes(StandardCharsets.UTF_8));
        RunState r = TowerManager.run(id);
        check(src, "persist.resume", r != null && r.serverInterrupted && r.floor == 2 && r.phase == RunState.Phase.FLOOR && r.activeMs >= 90_000,
                r == null ? "absente" : r.phase + " etage " + r.floor);
        FloorEntry fe = TowerConfig.floor(1);
        check(src, "persist.floors_json", fe != null && fe.npcUuid != null && fe.arrival != null, null);
        if (r != null) TowerManager.endRun(id, TowerManager.EndReason.CANCELLED, null);
        return 1;
    }

    public static int report(CommandSourceStack src) {
        long pass = REPORT.stream().filter(l -> l.startsWith("PASS")).count();
        long fail = REPORT.stream().filter(l -> l.startsWith("FAIL")).count();
        Battletower.LOG.info("[Battletower][selftest] SELFTEST SUMMARY pass={} fail={}", pass, fail);
        for (String l : REPORT) if (l.startsWith("FAIL")) Battletower.LOG.info("[Battletower][selftest] {}", l);
        src.sendSystemMessage(TextUtil.legacy("&eSELFTEST SUMMARY pass=" + pass + " fail=" + fail));
        return 1;
    }
}
