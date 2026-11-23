package fr.cobblepulse.battles.ranked;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.battles.hub.Hub;
import fr.cobblepulse.battletower.battle.CobblemonBridge;
import fr.cobblepulse.battletower.core.ItemBuilder;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.TimeFmt;
import fr.cobblepulse.battletower.gui.ChatInput;
import fr.cobblepulse.battletower.gui.Gui;
import fr.cobblepulse.battletower.gui.Guis;
import fr.cobblepulse.battletower.gui.Screens;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ecrans de la Ranked (affiches en ecrans CobblePulse, ou en menus coffre sans le mod cote client). */
public final class RankedMenus {
    private static final Map<UUID, Gui> SELECT = new ConcurrentHashMap<>();

    private RankedMenus() {}

    static String f(String key, Object... kv) {
        return Msg.formatBare(key, kv);
    }

    static ItemStack rankIcon(RankedConfig.Rank r) {
        return ItemBuilder.of(r.icon()).build();
    }

    // ------------------------------------------------------------------ menu principal

    public static void main(ServerPlayer p) {
        if (!RankedConfig.enabled()) {
            Msg.send(p, "ranked.disabled");
            return;
        }
        Gui g = new Gui(5, f("ranked.menu_title"));
        renderMain(g, p);
        g.every(20, gg -> renderMain(gg, p));
        g.open(p);
    }

    private static void renderMain(Gui g, ServerPlayer p) {
        RankedProfile prof = RankedStore.get(p);
        List<String> lore = new ArrayList<>();
        for (String fmt : RankedConfig.FORMATS) {
            RankedProfile.FormatStats st = prof.stats(fmt);
            FormatRules r = RankedConfig.rules(fmt);
            lore.add(f("ranked.profile_line", "format", r == null ? fmt : r.name, "elo", st.elo, "rank", RankedConfig.rankFor(st.elo).name(),
                    "wins", st.wins, "losses", st.losses));
        }
        lore.add(f("ranked.profile_season", "season", Seasons.describe()));
        lore.add(f("ranked.click_profile"));
        g.button(4, ItemBuilder.of(Items.PLAYER_HEAD).head(p.getGameProfile()).name("&e" + p.getGameProfile().getName()).lore(lore).build(),
                pl -> profile(pl, pl.getUUID()));
        int[] slots = {10, 12, 14, 16};
        int i = 0;
        for (boolean ranked : new boolean[]{true, false}) {
            for (String fmt : RankedConfig.FORMATS) {
                int slot = slots[i++];
                FormatRules r = RankedConfig.rules(fmt);
                if (r == null) continue;
                RankedQueue.Entry e = RankedQueue.entry(p.getUUID());
                boolean here = e != null && e.format.equals(fmt) && e.ranked == ranked;
                List<String> l = new ArrayList<>();
                l.add(f("ranked.queue_rules", "n", r.teamSize, "of", r.teamVisible, "level", r.level > 0 ? String.valueOf(r.level) : f("ranked.level_real")));
                if (r.turnTimer > 0 || r.matchMinutes > 0) l.add(f("ranked.queue_timers", "turn", r.turnTimer, "minutes", r.matchMinutes));
                l.add(f("ranked.queue_players", "n", RankedQueue.count(fmt, ranked)));
                boolean closed = !r.enabled || (ranked && !Seasons.active());
                if (closed) l.add(f("ranked.queue_closed"));
                else if (here) {
                    l.add(f("ranked.queue_waiting", "time", TimeFmt.clock(System.currentTimeMillis() - e.joinedAt)));
                    l.add(f("ranked.click_leave"));
                } else if (MatchManager.inMatch(p.getUUID())) l.add(f("ranked.queue_in_match"));
                else l.add(f("ranked.click_join"));
                String name = f(ranked ? "ranked.queue_ranked" : "ranked.queue_casual", "format", r.name);
                ItemStack icon = ranked ? rankIcon(RankedConfig.rankFor(prof.stats(fmt).elo)) : new ItemStack(fmt.equals("simple") ? Items.IRON_SWORD : Items.SHIELD);
                g.button(slot, ItemBuilder.of(icon).name(name).lore(l).glow(here).hideExtra().build(), pl -> {
                    if (RankedQueue.entry(pl.getUUID()) != null && here) {
                        RankedQueue.leave(pl.getUUID());
                        Msg.send(pl, "ranked.left_queue");
                    } else if (RankedQueue.entry(pl.getUUID()) == null) {
                        RankedQueue.join(pl, fmt, ranked);
                    } else {
                        Msg.send(pl, "ranked.already_queued");
                    }
                    renderMain(g, pl);
                });
            }
        }
        g.button(19, ItemBuilder.of(Items.GOLDEN_HELMET).name(f("ranked.menu_top")).lore(f("ranked.menu_top_lore")).hideExtra().build(),
                pl -> leaderboard(pl, "simple"));
        RankedProfile.MissionState next = null;
        Missions.refresh(prof);
        int done = 0;
        int total = prof.daily.size() + prof.weekly.size();
        for (RankedProfile.MissionState s : prof.daily) if (s.done) done++;
        for (RankedProfile.MissionState s : prof.weekly) if (s.done) done++;
        if (Missions.enabled()) {
            g.button(21, ItemBuilder.of(Items.WRITABLE_BOOK).name(f("ranked.menu_missions")).lore(f("ranked.menu_missions_lore", "done", done, "total", total)).build(),
                    RankedMenus::missions);
        }
        g.button(23, ItemBuilder.of(Items.CLOCK).name(f("ranked.menu_history")).lore(f("ranked.menu_history_lore")).build(),
                pl -> history(pl, RankedStore.get(pl)));
        g.button(25, ItemBuilder.of(Items.NETHER_STAR).name(f("ranked.menu_ranks")).lore(f("ranked.menu_ranks_lore")).build(), RankedMenus::ranks);
        List<String> season = new ArrayList<>();
        if (Seasons.active()) {
            season.add(f("ranked.season_preset", "preset", RankedConfig.presetName(Seasons.presetId())));
            season.add(f("ranked.season_end_line", "end", Seasons.endText(), "time", TimeFmt.duration(Seasons.remainingMs())));
        } else season.add(f("ranked.season_none"));
        g.set(31, ItemBuilder.of(Items.SUNFLOWER).name(f("ranked.season_card", "season", Seasons.current() == null ? f("ranked.off_season_name") : Seasons.current().name()))
                .lore(season).build());
        g.button(36, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), Hub::open);
        if (RankedQueue.queued(p.getUUID())) {
            g.button(40, ItemBuilder.of(Items.BARRIER).name(f("ranked.leave_queue")).build(), pl -> {
                RankedQueue.leave(pl.getUUID());
                Msg.send(pl, "ranked.left_queue");
                renderMain(g, pl);
            });
        } else g.set(40, ItemStack.EMPTY);
        if (Perms.admin(p, Ranked.PERM_ADMIN)) g.button(44, ItemBuilder.of(Items.COMMAND_BLOCK).name(f("ranked.menu_admin")).build(), RankedMenus::admin);
    }

    // ------------------------------------------------------------------ match trouve

    static void ready(ServerPlayer p, MatchManager.Match m) {
        Gui g = new Gui(2, f("ranked.ready_title"));
        renderReady(g, p, m);
        g.every(10, gg -> {
            if (m.state != MatchManager.State.READY) return;
            renderReady(gg, p, m);
        });
        g.open(p);
    }

    private static void renderReady(Gui g, ServerPlayer p, MatchManager.Match m) {
        MatchManager.Side me = m.side(p.getUUID().toString());
        MatchManager.Side foe = m.other(me);
        List<String> l = new ArrayList<>();
        l.add(f(m.ranked ? "ranked.queue_ranked" : "ranked.queue_casual", "format", m.rules.name));
        if (m.ranked) l.add(f("ranked.ready_elo", "elo", foe.elo, "rank", RankedConfig.rankFor(foe.elo).name()));
        l.add(f("ranked.queue_rules", "n", m.rules.teamSize, "of", m.rules.teamVisible, "level", m.rules.level > 0 ? String.valueOf(m.rules.level) : f("ranked.level_real")));
        g.set(4, ItemBuilder.of(Items.PLAYER_HEAD).head(new com.mojang.authlib.GameProfile(UUID.fromString(foe.uuid), foe.name))
                .name(f("ranked.ready_vs", "opponent", foe.name)).lore(l).build());
        g.set(0, ItemBuilder.of(Items.CLOCK).name(f("ranked.time_left", "seconds", m.remainingMs() / 1000)).lore(
                me.accepted ? f("ranked.ready_waiting_foe") : f("ranked.ready_hint")).build());
        g.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name(f("ranked.accept")).glow(me.accepted).build(), MatchManager::accept);
        g.button(15, ItemBuilder.of(Items.RED_CONCRETE).name(f("ranked.decline")).lore(f("ranked.decline_lore")).build(), MatchManager::decline);
    }

    // ------------------------------------------------------------------ selection de l'equipe

    static void select(ServerPlayer p, MatchManager.Match m, MatchManager.Side s) {
        Gui g = new Gui(5, f("ranked.select_title", "format", m.rules.name, "n", m.rules.teamSize));
        SELECT.put(p.getUUID(), g);
        renderSelect(g, p, m, s);
        g.every(20, gg -> {
            if (m.state != MatchManager.State.SELECT) return;
            renderSelect(gg, p, m, s);
        });
        g.onClose(pl -> SELECT.remove(pl.getUUID(), g));
        g.open(p);
    }

    static void refreshSelect(ServerPlayer p, MatchManager.Match m, MatchManager.Side s) {
        Gui g = SELECT.get(p.getUUID());
        if (g != null) renderSelect(g, p, m, s);
        else select(p, m, s);
    }

    private static final String[] NUMBERS = {"①", "②", "③", "④", "⑤", "⑥"};

    private static void renderSelect(Gui g, ServerPlayer p, MatchManager.Match m, MatchManager.Side s) {
        MatchManager.Side foe = m.other(s);
        List<String> info = new ArrayList<>();
        info.add(f("ranked.select_hint", "n", m.rules.teamSize));
        info.add(f("ranked.ready_vs", "opponent", foe.name));
        info.add(s.confirmed ? f("ranked.select_waiting") : f("ranked.select_count", "n", s.picks.size(), "max", m.rules.teamSize));
        g.set(0, ItemBuilder.of(Items.CLOCK).name(f("ranked.time_left", "seconds", m.remainingMs() / 1000)).lore(info).build());
        for (int i = 0; i < 6; i++) {
            int slot = 9 + i;
            if (i >= s.party.size()) {
                g.set(slot, ItemStack.EMPTY);
                continue;
            }
            Pokemon pk = s.party.get(i);
            int order = s.picks.indexOf(i);
            String why = TeamRules.checkPokemon(pk, m.rules);
            String status;
            if (why != null) status = f("ranked.mon_refused", "reason", why);
            else if (order >= 0) status = f(order == 0 ? "ranked.mon_lead" : "ranked.mon_picked", "n", order + 1);
            else status = s.confirmed ? "" : f("ranked.click_pick");
            String prefix = order >= 0 ? "&a" + NUMBERS[Math.min(order, 5)] + " " : why != null ? "&8" : "&f";
            int idx = i;
            g.button(slot, monCard(pk, prefix, m.rules.level, true, status).glow(order >= 0).build(), pl -> MatchManager.toggle(pl, idx));
        }
        g.set(18, ItemBuilder.of(Items.SPYGLASS).name(f("ranked.select_foe", "opponent", foe.name)).lore(f("ranked.select_foe_lore", "n", m.rules.teamVisible)).build());
        for (int i = 0; i < 6; i++) {
            int slot = 27 + i;
            if (!foe.partyLoaded || i >= Math.min(foe.party.size(), m.rules.teamVisible)) {
                g.set(slot, ItemStack.EMPTY);
                continue;
            }
            Pokemon pk = foe.party.get(i);
            g.set(slot, monCard(pk, "&c", m.rules.level, false, "").build());
        }
        if (!s.confirmed) {
            g.button(36, ItemBuilder.of(Items.HOPPER).name(f("ranked.select_auto_btn")).lore(f("ranked.select_auto_lore")).build(), MatchManager::autoSelect);
            g.button(40, ItemBuilder.of(Items.LIME_CONCRETE).name(f("ranked.select_confirm")).lore(f("ranked.select_count", "n", s.picks.size(), "max", m.rules.teamSize)).build(),
                    MatchManager::confirm);
        } else {
            g.set(36, ItemStack.EMPTY);
            g.set(40, ItemBuilder.of(Items.LIME_STAINED_GLASS).name(f("ranked.select_waiting")).build());
        }
        g.button(44, ItemBuilder.of(Items.RED_CONCRETE).name(f("ranked.decline")).lore(f("ranked.decline_lore")).build(), MatchManager::decline);
    }

    /**
     * Carte d'un Pokemon : noms d'espece, de talent, d'objet et de capacites traduits par le client (composants
     * traduisibles), niveau impose.
     */
    static ItemBuilder monCard(Pokemon pk, String legacyPrefix, int levelCap, boolean details, String statusLegacy) {
        net.minecraft.network.chat.MutableComponent name = fr.cobblepulse.battletower.core.TextUtil.legacy(legacyPrefix)
                .append(pk.getSpecies().getTranslatedName());
        ItemBuilder b = ItemBuilder.of(CobblemonBridge.icon(pk)).name(name.withStyle(st -> st.withItalic(false)));
        int lvl = levelCap > 0 ? levelCap : pk.getLevel();
        b.loreComponent(fr.cobblepulse.battletower.core.TextUtil.legacy(f("ranked.mon_level", "level", pk.getLevel(), "cap", lvl)));
        if (details) {
            b.loreComponent(fr.cobblepulse.battletower.core.TextUtil.legacy(f("ranked.mon_ability_label"))
                    .append(net.minecraft.network.chat.Component.translatable(pk.getAbility().getDisplayName())));
            b.loreComponent(fr.cobblepulse.battletower.core.TextUtil.legacy(f("ranked.mon_item_label"))
                    .append(pk.heldItem().isEmpty() ? net.minecraft.network.chat.Component.literal("-") : pk.heldItem().getHoverName()));
            net.minecraft.network.chat.MutableComponent moves = fr.cobblepulse.battletower.core.TextUtil.legacy(f("ranked.mon_moves_label"));
            boolean first = true;
            for (var mv : pk.getMoveSet().getMoves()) {
                if (!first) moves.append(net.minecraft.network.chat.Component.literal(", "));
                moves.append(mv.getDisplayName());
                first = false;
            }
            b.loreComponent(moves);
        }
        if (statusLegacy != null && !statusLegacy.isEmpty()) b.loreComponent(fr.cobblepulse.battletower.core.TextUtil.legacy(statusLegacy));
        return b;
    }

    // ------------------------------------------------------------------ classement

    public static void leaderboard(ServerPlayer p, String format) {
        String fmt = RankedConfig.normalizeFormat(format) == null ? "simple" : RankedConfig.normalizeFormat(format);
        FormatRules r = RankedConfig.rules(fmt);
        Gui g = new Gui(6, f("ranked.top_title", "format", r == null ? fmt : r.name)).list(true);
        List<RankedProfile> top = RankedStore.top(fmt, 100);
        int max = Screens.enabled(p) ? top.size() : Math.min(45, top.size());
        if (max > 45) g.grow(54 + max - 45);
        for (int i = 0; i < max; i++) {
            RankedProfile rp = top.get(i);
            RankedProfile.FormatStats st = rp.formats.get(fmt);
            String color = i == 0 ? "&6" : i == 1 ? "&f" : i == 2 ? "&c" : "&7";
            ItemStack head = ItemBuilder.of(Items.PLAYER_HEAD).head(new com.mojang.authlib.GameProfile(UUID.fromString(rp.uuid), rp.name))
                    .name(color + "#" + (i + 1) + " &e" + rp.name)
                    .lore(f("ranked.top_elo", "elo", st.elo, "rank", RankedConfig.rankFor(st.elo).name()),
                            f("ranked.top_record", "wins", st.wins, "losses", st.losses, "rate", st.winrate()),
                            f("ranked.top_streak", "streak", st.streak, "best", st.bestStreak)).build();
            String uuid = rp.uuid;
            g.button(i < 45 ? i : 54 + i - 45, head, pl -> profile(pl, UUID.fromString(uuid)));
        }
        if (top.isEmpty()) g.set(22, ItemBuilder.of(Items.PAPER).name(f("ranked.top_empty")).build());
        g.button(45, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::main);
        for (String other : RankedConfig.FORMATS) {
            FormatRules or = RankedConfig.rules(other);
            int slot = other.equals("simple") ? 47 : 51;
            g.button(slot, ItemBuilder.of(other.equals("simple") ? Items.IRON_SWORD : Items.SHIELD).name(f("ranked.top_switch", "format", or == null ? other : or.name))
                    .glow(other.equals(fmt)).hideExtra().build(), pl -> leaderboard(pl, other));
        }
        RankedProfile me = RankedStore.get(p);
        int place = RankedStore.place(fmt, me.uuid);
        RankedProfile.FormatStats ms = me.stats(fmt);
        g.set(49, ItemBuilder.of(Items.PLAYER_HEAD).head(p.getGameProfile()).name(f("ranked.top_you", "place", place == 0 ? "-" : String.valueOf(place)))
                .lore(f("ranked.top_elo", "elo", ms.elo, "rank", RankedConfig.rankFor(ms.elo).name())).build());
        g.open(p);
    }

    // ------------------------------------------------------------------ profil, historique

    public static void profile(ServerPlayer viewer, UUID target) {
        RankedProfile rp = target.equals(viewer.getUUID()) ? RankedStore.get(viewer) : RankedStore.get(target);
        if (rp == null) {
            Msg.send(viewer, "general.player_not_found", "player", target);
            return;
        }
        Penalties.decay(rp);
        Gui g = new Gui(4, f("ranked.profile_title", "player", rp.name));
        g.set(4, ItemBuilder.of(Items.PLAYER_HEAD).head(new com.mojang.authlib.GameProfile(UUID.fromString(rp.uuid), rp.name))
                .name("&e" + rp.name).lore(f("ranked.profile_season", "season", Seasons.describe())).build());
        int slot = 11;
        for (String fmt : RankedConfig.FORMATS) {
            RankedProfile.FormatStats st = rp.stats(fmt);
            FormatRules r = RankedConfig.rules(fmt);
            int place = RankedStore.place(fmt, rp.uuid);
            g.set(slot, ItemBuilder.of(rankIcon(RankedConfig.rankFor(st.elo))).name("&6" + (r == null ? fmt : r.name)).lore(
                    f("ranked.top_elo", "elo", st.elo, "rank", RankedConfig.rankFor(st.elo).name()),
                    f("ranked.stat_peak", "peak", st.peak),
                    f("ranked.top_record", "wins", st.wins, "losses", st.losses, "rate", st.winrate()),
                    f("ranked.top_streak", "streak", st.streak, "best", st.bestStreak),
                    f("ranked.stat_casual", "wins", st.casualWins, "losses", st.casualLosses),
                    f("ranked.stat_place", "place", place == 0 ? "-" : String.valueOf(place))).build());
            slot += 4;
        }
        List<String> fair = new ArrayList<>();
        fair.add(f("ranked.stat_flees", "flees", rp.flees));
        long ban = rp.banUntil - System.currentTimeMillis();
        if (ban > 0) fair.add(f("ranked.stat_banned", "time", TimeFmt.duration(ban)));
        g.set(22, ItemBuilder.of(Items.SHIELD).name(f("ranked.stat_fairplay")).lore(fair).hideExtra().build());
        g.button(27, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::main);
        g.button(31, ItemBuilder.of(Items.CLOCK).name(f("ranked.menu_history")).build(), pl -> history(pl, rp));
        g.open(viewer);
    }

    public static void history(ServerPlayer viewer, RankedProfile rp) {
        Gui g = new Gui(4, f("ranked.history_title", "player", rp.name));
        int i = 0;
        for (RankedProfile.MatchSummary h : rp.history) {
            if (i >= 27) break;
            ItemStack icon = new ItemStack("victoire".equals(h.result) ? Items.EMERALD : "defaite".equals(h.result) ? Items.REDSTONE : Items.IRON_NUGGET);
            int delta = h.eloAfter - h.eloBefore;
            FormatRules r = RankedConfig.rules(h.format);
            g.set(i++, ItemBuilder.of(icon).name(f("ranked.history_entry", "result", f("ranked.result_word_" + h.result), "opponent", h.opponentName))
                    .lore(f(h.ranked ? "ranked.queue_ranked" : "ranked.queue_casual", "format", r == null ? h.format : r.name),
                            h.ranked ? f("ranked.history_elo", "before", h.eloBefore, "after", h.eloAfter, "delta", (delta >= 0 ? "+" : "") + delta) : "",
                            f("ranked.history_meta", "turns", h.turns, "reason", h.reason, "date", TimeFmt.date(h.at))).build());
        }
        if (rp.history.isEmpty()) g.set(13, ItemBuilder.of(Items.PAPER).name(f("ranked.history_empty")).build());
        g.button(27, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::main);
        g.open(viewer);
    }

    // ------------------------------------------------------------------ missions, rangs

    public static void missions(ServerPlayer p) {
        RankedProfile prof = RankedStore.get(p);
        Missions.refresh(prof);
        Gui g = new Gui(4, f("ranked.missions_title"));
        g.set(4, ItemBuilder.of(Items.CLOCK).name(f("ranked.missions_reset", "time", TimeFmt.duration(Missions.nextDailyResetMs()))).build());
        missionRow(g, prof.daily, false, 9);
        missionRow(g, prof.weekly, true, 18);
        g.button(27, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::main);
        g.open(p);
    }

    private static void missionRow(Gui g, List<RankedProfile.MissionState> list, boolean weekly, int start) {
        int slot = start;
        for (RankedProfile.MissionState s : list) {
            Missions.Def d = Missions.def(s.id, weekly);
            if (d == null) continue;
            List<String> l = new ArrayList<>();
            l.add("&7" + d.description());
            l.add(f(weekly ? "ranked.mission_weekly" : "ranked.mission_daily"));
            l.add(f("ranked.mission_progress", "n", s.progress, "max", d.required()));
            l.add(s.claimed ? f("ranked.mission_claimed_lore") : s.done ? f("ranked.mission_click_claim") : "");
            String id = s.id;
            g.button(slot++, ItemBuilder.of(d.icon()).name(d.title()).lore(l).glow(s.done && !s.claimed).build(), pl -> {
                if (Missions.claim(pl, id, weekly)) missions(pl);
            });
            if (slot >= start + 9) break;
        }
    }

    public static void ranks(ServerPlayer p) {
        RankedProfile prof = RankedStore.get(p);
        Gui g = new Gui(3, f("ranked.ranks_title"));
        int slot = 0;
        for (RankedConfig.Rank r : RankedConfig.RANKS) {
            if (slot >= 18) break;
            List<String> l = new ArrayList<>();
            l.add(f("ranked.rank_min", "elo", r.minElo()));
            for (String fmt : RankedConfig.FORMATS) {
                RankedProfile.FormatStats st = prof.stats(fmt);
                boolean reached = st.elo >= r.minElo() || st.rankRewards.contains(r.id());
                FormatRules fr = RankedConfig.rules(fmt);
                l.add(f(reached ? "ranked.rank_reached" : "ranked.rank_locked", "format", fr == null ? fmt : fr.name));
            }
            int rewards = RankedConfig.rewards.list("rang." + r.id() + ".commandes").size();
            if (rewards > 0) l.add(f("ranked.rank_has_reward"));
            g.set(slot++, ItemBuilder.of(rankIcon(r)).name(r.name()).lore(l).build());
        }
        g.button(18, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::main);
        g.open(p);
    }

    // ------------------------------------------------------------------ administration

    public static void admin(ServerPlayer p) {
        if (!Perms.admin(p, Ranked.PERM_ADMIN)) return;
        Gui g = new Gui(5, f("ranked.admin_title"));
        int slot = 0;
        for (Arenas.Arena a : Arenas.all()) {
            if (slot >= 18) break;
            String name = a.name;
            g.button(slot++, ItemBuilder.of(a.ready() ? Items.GRASS_BLOCK : Items.COARSE_DIRT).name("&e" + a.name).lore(
                    f("ranked.arena_status", "status", f(a.ready() ? (Arenas.busy(a) ? "ranked.arena_busy" : "ranked.arena_free") : "ranked.arena_incomplete")),
                    f("ranked.arena_pos", "n", 1, "pos", a.pos1 == null ? "-" : a.pos1.pretty()),
                    f("ranked.arena_pos", "n", 2, "pos", a.pos2 == null ? "-" : a.pos2.pretty()),
                    f("ranked.arena_exit", "pos", a.exit == null ? "-" : a.exit.pretty()),
                    f("ranked.click_edit")).build(), pl -> arena(pl, name));
        }
        g.button(18, ItemBuilder.of(Items.LIME_DYE).name(f("ranked.arena_create")).lore(f("ranked.arena_create_lore")).build(), pl ->
                ChatInput.ask(pl, "ranked.ask_arena_name", name -> {
                    Arenas.create(name.trim());
                    arena(pl, name.trim());
                }, () -> admin(pl)));
        List<String> season = new ArrayList<>();
        season.add(f("ranked.profile_season", "season", Seasons.describe()));
        season.add(f("ranked.season_end_line", "end", Seasons.endText(), "time", TimeFmt.duration(Seasons.remainingMs())));
        season.add(f("ranked.admin_rotate_lore"));
        g.button(20, ItemBuilder.of(Items.SUNFLOWER).name(f("ranked.admin_season")).lore(season).build(), pl ->
                Guis.confirm(pl, f("ranked.admin_season"), f("ranked.admin_rotate_confirm"), () -> {
                    Seasons.forceRotate();
                    admin(pl);
                }, () -> admin(pl)));
        List<String> matches = new ArrayList<>();
        for (MatchManager.Match m : MatchManager.all()) {
            matches.add("&7" + m.a.name + " &8vs &7" + m.b.name + " &8— " + MatchManager.stateName(m.state));
        }
        if (matches.isEmpty()) matches.add(f("ranked.admin_no_match"));
        matches.add(f("ranked.click_open"));
        g.button(22, ItemBuilder.of(Items.DIAMOND_SWORD).name(f("ranked.admin_matches", "n", MatchManager.all().size())).lore(matches).hideExtra().build(),
                RankedMenus::adminMatches);
        g.set(24, ItemBuilder.of(Items.HOPPER).name(f("ranked.admin_queue")).lore(
                f("ranked.admin_queue_line", "format", "Simple", "ranked", RankedQueue.count("simple", true), "casual", RankedQueue.count("simple", false)),
                f("ranked.admin_queue_line", "format", "Double", "ranked", RankedQueue.count("double", true), "casual", RankedQueue.count("double", false)),
                f("ranked.admin_network", "status", CrossServer.enabled() ? f("ranked.admin_network_on", "server", CrossServer.serverId(), "battle", CrossServer.battleServer())
                        : f("ranked.admin_network_off"))).build());
        g.button(26, ItemBuilder.of(Items.REPEATER).name(f("ranked.admin_reload")).build(), pl -> {
            Ranked.reload();
            Msg.send(pl, "ranked.reloaded");
            admin(pl);
        });
        g.button(36, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::main);
        g.open(p);
    }

    static void adminMatches(ServerPlayer p) {
        Gui g = new Gui(4, f("ranked.admin_matches", "n", MatchManager.all().size()));
        int slot = 0;
        for (MatchManager.Match m : MatchManager.all()) {
            if (slot >= 27) break;
            String uuid = m.a.uuid;
            g.button(slot++, ItemBuilder.of(Items.IRON_SWORD).name("&e" + m.a.name + " &7vs &e" + m.b.name).lore(
                    f(m.ranked ? "ranked.queue_ranked" : "ranked.queue_casual", "format", m.rules.name),
                    "&7" + MatchManager.stateName(m.state), f("ranked.admin_force_end")).hideExtra().build(), pl ->
                    Guis.confirm(pl, f("ranked.admin_force_end"), f("ranked.admin_force_end_confirm"), () -> {
                        MatchManager.forceEnd(UUID.fromString(uuid));
                        adminMatches(pl);
                    }, () -> adminMatches(pl)));
        }
        g.button(27, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::admin);
        g.open(p);
    }

    static void arena(ServerPlayer p, String name) {
        Arenas.Arena a = Arenas.get(name);
        if (a == null) {
            admin(p);
            return;
        }
        Gui g = new Gui(3, f("ranked.arena_title", "name", a.name));
        g.button(10, ItemBuilder.of(Items.RED_BANNER).name(f("ranked.arena_set_pos", "n", 1)).lore(a.pos1 == null ? "-" : "&7" + a.pos1.pretty()).build(), pl -> {
            a.pos1 = fr.cobblepulse.battletower.core.Loc.of(pl);
            Arenas.save();
            arena(pl, name);
        });
        g.button(12, ItemBuilder.of(Items.BLUE_BANNER).name(f("ranked.arena_set_pos", "n", 2)).lore(a.pos2 == null ? "-" : "&7" + a.pos2.pretty()).build(), pl -> {
            a.pos2 = fr.cobblepulse.battletower.core.Loc.of(pl);
            Arenas.save();
            arena(pl, name);
        });
        g.button(14, ItemBuilder.of(Items.OAK_DOOR).name(f("ranked.arena_set_exit")).lore(a.exit == null ? f("ranked.arena_exit_none") : "&7" + a.exit.pretty()).build(), (pl, c) -> {
            a.exit = c.right() ? null : fr.cobblepulse.battletower.core.Loc.of(pl);
            Arenas.save();
            arena(pl, name);
        });
        g.button(16, ItemBuilder.of(a.enabled ? Items.LIME_DYE : Items.GRAY_DYE).name(f(a.enabled ? "ranked.arena_enabled" : "ranked.arena_disabled")).build(), pl -> {
            a.enabled = !a.enabled;
            Arenas.save();
            arena(pl, name);
        });
        g.button(18, ItemBuilder.of(Items.ARROW).name(f("hub.back")).build(), RankedMenus::admin);
        if (a.pos1 != null) g.button(22, ItemBuilder.of(Items.ENDER_PEARL).name(f("ranked.arena_tp")).build(), pl -> a.pos1.teleport(pl));
        g.button(26, ItemBuilder.of(Items.LAVA_BUCKET).name(f("ranked.arena_delete")).build(), pl ->
                Guis.confirm(pl, f("ranked.arena_delete"), a.name, () -> {
                    Arenas.remove(name);
                    admin(pl);
                }, () -> arena(pl, name)));
        g.open(p);
    }
}
