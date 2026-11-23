package fr.cobblepulse.battles.ranked;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.cobblepulse.battles.hub.Hub;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.core.TimeFmt;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** /ranked, /amical, /battles. */
public final class RankedCommands {
    private RankedCommands() {}

    static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> formats() {
        return (c, b) -> SharedSuggestionProvider.suggest(RankedConfig.FORMATS, b);
    }

    static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> arenas() {
        return (c, b) -> SharedSuggestionProvider.suggest(Arenas.all().stream().map(a -> a.name), b);
    }

    static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> players() {
        return (c, b) -> SharedSuggestionProvider.suggest(RankedStore.all().stream().map(p -> p.name), b);
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("battles").executes(c -> hub(c)));
        d.register(Commands.literal("combats").executes(c -> hub(c)));
        d.register(Commands.literal("amical").requires(Perms.playerReq(Ranked.PERM_CASUAL))
                .executes(c -> {
                    RankedMenus.main(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.argument("format", StringArgumentType.word()).suggests(formats()).executes(c ->
                        RankedQueue.join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "format"), false) ? 1 : 0)));
        var admin = Commands.literal("admin").requires(Perms.adminReq(Ranked.PERM_ADMIN))
                .executes(c -> {
                    RankedMenus.admin(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("arene")
                        .then(Commands.literal("liste").executes(c -> {
                            for (Arenas.Arena a : Arenas.all()) {
                                Msg.send(c.getSource(), "ranked.arena_line", "name", a.name, "status", Msg.formatBare(a.ready() ? (Arenas.busy(a) ? "ranked.arena_busy" : "ranked.arena_free") : "ranked.arena_incomplete"));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("creer").then(Commands.argument("nom", StringArgumentType.word()).executes(c -> {
                            Arenas.Arena a = Arenas.create(StringArgumentType.getString(c, "nom"));
                            a.pos1 = Loc.of(c.getSource().getPlayerOrException());
                            Arenas.save();
                            Msg.send(c.getSource(), "ranked.arena_created", "name", a.name);
                            return 1;
                        })))
                        .then(arenaPos("pos1", 1))
                        .then(arenaPos("pos2", 2))
                        .then(arenaPos("sortie", 3))
                        .then(Commands.literal("supprimer").then(Commands.argument("nom", StringArgumentType.word()).suggests(arenas()).executes(c -> {
                            boolean ok = Arenas.remove(StringArgumentType.getString(c, "nom"));
                            Msg.send(c.getSource(), ok ? "ranked.arena_deleted" : "ranked.arena_unknown", "name", StringArgumentType.getString(c, "nom"));
                            return ok ? 1 : 0;
                        })))
                        .then(Commands.literal("tp").then(Commands.argument("nom", StringArgumentType.word()).suggests(arenas()).executes(c -> {
                            Arenas.Arena a = Arenas.get(StringArgumentType.getString(c, "nom"));
                            if (a == null || a.pos1 == null) return 0;
                            a.pos1.teleport(c.getSource().getPlayerOrException());
                            return 1;
                        }))))
                .then(Commands.literal("saison")
                        .then(Commands.literal("info").executes(c -> {
                            Msg.send(c.getSource(), "ranked.season_info", "season", Seasons.describe(), "end", Seasons.endText(),
                                    "time", TimeFmt.duration(Seasons.remainingMs()));
                            return 1;
                        }))
                        .then(Commands.literal("changer").executes(c -> {
                            Seasons.forceRotate();
                            Msg.send(c.getSource(), "ranked.season_rotated", "season", Seasons.describe());
                            return 1;
                        })))
                .then(Commands.literal("elo")
                        .then(eloCmd("definir", false))
                        .then(eloCmd("ajouter", true)))
                .then(Commands.literal("bannir").then(Commands.argument("joueur", StringArgumentType.word()).suggests(players())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1)).executes(c -> {
                            RankedProfile p = RankedStore.byName(StringArgumentType.getString(c, "joueur"));
                            if (p == null) return notFound(c);
                            p.banUntil = System.currentTimeMillis() + IntegerArgumentType.getInteger(c, "minutes") * 60_000L;
                            p.banReason = "staff";
                            RankedStore.mark(p);
                            RankedQueue.leave(UUID.fromString(p.uuid));
                            Msg.send(c.getSource(), "ranked.banned_done", "player", p.name, "minutes", IntegerArgumentType.getInteger(c, "minutes"));
                            return 1;
                        }))))
                .then(Commands.literal("debannir").then(Commands.argument("joueur", StringArgumentType.word()).suggests(players()).executes(c -> {
                    RankedProfile p = RankedStore.byName(StringArgumentType.getString(c, "joueur"));
                    if (p == null) return notFound(c);
                    p.banUntil = 0;
                    p.flees = 0;
                    RankedStore.mark(p);
                    Msg.send(c.getSource(), "ranked.unbanned_done", "player", p.name);
                    return 1;
                })))
                .then(Commands.literal("fin").then(Commands.argument("joueur", StringArgumentType.word()).executes(c -> {
                    RankedProfile p = RankedStore.byName(StringArgumentType.getString(c, "joueur"));
                    boolean ok = p != null && MatchManager.forceEnd(UUID.fromString(p.uuid));
                    Msg.send(c.getSource(), ok ? "ranked.force_end_done" : "ranked.force_end_none", "player", StringArgumentType.getString(c, "joueur"));
                    return ok ? 1 : 0;
                })))
                .then(Commands.literal("reset").then(Commands.argument("joueur", StringArgumentType.word()).suggests(players()).executes(c -> {
                    RankedProfile p = RankedStore.byName(StringArgumentType.getString(c, "joueur"));
                    if (p == null) return notFound(c);
                    p.formats.clear();
                    p.history.clear();
                    p.flees = 0;
                    p.banUntil = 0;
                    RankedStore.mark(p);
                    Msg.send(c.getSource(), "ranked.reset_done", "player", p.name);
                    return 1;
                })))
                .then(Commands.literal("recharger").executes(c -> {
                    Ranked.reload();
                    Msg.send(c.getSource(), "ranked.reloaded");
                    return 1;
                }));
        d.register(Commands.literal("ranked").requires(Perms.playerReq(Ranked.PERM_RANKED))
                .executes(c -> {
                    RankedMenus.main(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("file").then(Commands.argument("format", StringArgumentType.word()).suggests(formats())
                        .executes(c -> RankedQueue.join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "format"), true) ? 1 : 0)
                        .then(Commands.literal("amical").executes(c -> RankedQueue.join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "format"), false) ? 1 : 0))
                        .then(Commands.literal("classe").executes(c -> RankedQueue.join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "format"), true) ? 1 : 0))))
                .then(Commands.literal("quitter").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Msg.send(p, RankedQueue.leave(p.getUUID()) ? "ranked.left_queue" : "ranked.not_queued");
                    return 1;
                }))
                .then(Commands.literal("accepter").executes(c -> {
                    MatchManager.accept(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("refuser").executes(c -> {
                    MatchManager.decline(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("top").executes(c -> {
                    RankedMenus.leaderboard(c.getSource().getPlayerOrException(), "simple");
                    return 1;
                }).then(Commands.argument("format", StringArgumentType.word()).suggests(formats()).executes(c -> {
                    RankedMenus.leaderboard(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "format"));
                    return 1;
                })))
                .then(Commands.literal("profil").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    RankedMenus.profile(p, p.getUUID());
                    return 1;
                }).then(Commands.argument("joueur", StringArgumentType.word()).suggests(players()).executes(c -> {
                    RankedProfile p = RankedStore.byName(StringArgumentType.getString(c, "joueur"));
                    if (p == null) return notFound(c);
                    RankedMenus.profile(c.getSource().getPlayerOrException(), UUID.fromString(p.uuid));
                    return 1;
                })))
                .then(Commands.literal("missions").executes(c -> {
                    RankedMenus.missions(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("historique").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    RankedMenus.history(p, RankedStore.get(p));
                    return 1;
                }))
                .then(Commands.literal("rangs").executes(c -> {
                    RankedMenus.ranks(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(admin));
    }

    private static int hub(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Hub.open(c.getSource().getPlayerOrException());
        return 1;
    }

    private static int notFound(CommandContext<CommandSourceStack> c) {
        Msg.send(c.getSource(), "general.player_not_found", "player", StringArgumentType.getString(c, "joueur"));
        return 0;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> arenaPos(String lit, int which) {
        return Commands.literal(lit).then(Commands.argument("nom", StringArgumentType.word()).suggests(arenas()).executes(c -> {
            Arenas.Arena a = Arenas.get(StringArgumentType.getString(c, "nom"));
            if (a == null) {
                Msg.send(c.getSource(), "ranked.arena_unknown", "name", StringArgumentType.getString(c, "nom"));
                return 0;
            }
            Loc l = Loc.of(c.getSource().getPlayerOrException());
            if (which == 1) a.pos1 = l;
            else if (which == 2) a.pos2 = l;
            else a.exit = l;
            Arenas.save();
            Msg.send(c.getSource(), "ranked.arena_pos_set", "name", a.name, "what", lit);
            return 1;
        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> eloCmd(String lit, boolean add) {
        return Commands.literal(lit).then(Commands.argument("joueur", StringArgumentType.word()).suggests(players())
                .then(Commands.argument("format", StringArgumentType.word()).suggests(formats())
                        .then(Commands.argument("valeur", IntegerArgumentType.integer(-5000, 10000)).executes(c -> {
                            RankedProfile p = RankedStore.byName(StringArgumentType.getString(c, "joueur"));
                            if (p == null) return notFound(c);
                            String f = RankedConfig.normalizeFormat(StringArgumentType.getString(c, "format"));
                            if (f == null) return 0;
                            RankedProfile.FormatStats st = p.stats(f);
                            int v = IntegerArgumentType.getInteger(c, "valeur");
                            st.elo = Math.max(RankedConfig.floorElo(), add ? st.elo + v : v);
                            st.peak = Math.max(st.peak, st.elo);
                            RankedStore.mark(p);
                            Msg.send(c.getSource(), "ranked.elo_set", "player", p.name, "format", f, "elo", st.elo);
                            return 1;
                        }))));
    }
}
