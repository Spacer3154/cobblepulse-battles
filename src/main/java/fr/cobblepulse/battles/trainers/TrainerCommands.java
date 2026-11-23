package fr.cobblepulse.battles.trainers;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.data.PlayerData;
import fr.cobblepulse.battletower.net.EditorService;
import fr.cobblepulse.battletower.storage.PlayerStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** /dresseur : gestion des dresseurs hors Tour ; /dresseurs : liste et progression. */
public final class TrainerCommands {
    public static final String PERM_USE = "cobblepulse.battles.trainers.use";
    public static final String PERM_ADMIN = "cobblepulse.battles.trainers.admin";

    private TrainerCommands() {}

    static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ids() {
        return (ctx, b) -> SharedSuggestionProvider.suggest(WorldTrainers.all().stream().map(w -> w.id), b);
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("dresseurs").requires(Perms.playerReq(PERM_USE)).executes(c -> {
            TrainerMenus.list(c.getSource().getPlayerOrException());
            return 1;
        }));
        var root = Commands.literal("dresseur").requires(Perms.adminReq(PERM_ADMIN))
                .executes(c -> {
                    TrainerMenus.list(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("liste").executes(c -> {
                    TrainerMenus.list(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("creer").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    String id = StringArgumentType.getString(c, "id");
                    WorldTrainers.WorldTrainer w = WorldTrainers.create(p, id);
                    if (w == null) Msg.send(p, "trainer.create_failed", "id", id);
                    else {
                        Msg.send(p, "trainer.created", "id", w.id);
                        EditorService.openWorld(p, w.id);
                    }
                    return w == null ? 0 : 1;
                })))
                .then(Commands.literal("editer")
                        .executes(c -> edit(c, null))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(ids()).executes(c -> edit(c, StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("deplacer").then(Commands.argument("id", StringArgumentType.word()).suggests(ids()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    WorldTrainers.WorldTrainer w = WorldTrainers.get(StringArgumentType.getString(c, "id"));
                    if (w == null) return unknown(p, StringArgumentType.getString(c, "id"));
                    WorldTrainers.move(p, w);
                    Msg.send(p, "trainer.moved", "id", w.id);
                    return 1;
                })))
                .then(Commands.literal("supprimer").then(Commands.argument("id", StringArgumentType.word()).suggests(ids()).executes(c -> {
                    WorldTrainers.WorldTrainer w = WorldTrainers.get(StringArgumentType.getString(c, "id"));
                    if (w == null) {
                        Msg.send(c.getSource(), "trainer.unknown", "id", StringArgumentType.getString(c, "id"));
                        return 0;
                    }
                    WorldTrainers.remove(c.getSource().getServer(), w);
                    Msg.send(c.getSource(), "trainer.deleted", "id", w.id);
                    return 1;
                })))
                .then(Commands.literal("tp").then(Commands.argument("id", StringArgumentType.word()).suggests(ids()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    WorldTrainers.WorldTrainer w = WorldTrainers.get(StringArgumentType.getString(c, "id"));
                    if (w == null || w.location == null) return unknown(p, StringArgumentType.getString(c, "id"));
                    w.location.teleport(p);
                    return 1;
                })))
                .then(Commands.literal("reset").then(Commands.argument("joueur", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(PlayerStore.names(), b))
                        .executes(c -> reset(c, null))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(ids()).executes(c -> reset(c, StringArgumentType.getString(c, "id"))))));
        d.register(root);
    }

    private static int unknown(ServerPlayer p, String id) {
        Msg.send(p, "trainer.unknown", "id", id);
        return 0;
    }

    private static int edit(CommandContext<CommandSourceStack> c, String id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        if (id == null) {
            NPCEntity npc = lookedAt(p);
            WorldTrainers.WorldTrainer w = npc == null ? null : WorldTrainers.of(npc);
            if (w == null) {
                Msg.send(p, "trainer.look_at_npc");
                return 0;
            }
            id = w.id;
        }
        EditorService.openWorld(p, id);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> c, String id) {
        String name = StringArgumentType.getString(c, "joueur");
        PlayerData d = PlayerStore.byName(name);
        if (d == null) {
            Msg.send(c.getSource(), "general.player_not_found", "player", name);
            return 0;
        }
        boolean ok = WorldTrainers.resetPlayer(d, id);
        Msg.send(c.getSource(), ok ? "trainer.reset_done" : "trainer.reset_nothing", "player", d.name, "id", id == null ? "*" : id);
        return ok ? 1 : 0;
    }

    static NPCEntity lookedAt(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 end = eye.add(look.scale(8));
        AABB box = p.getBoundingBox().expandTowards(look.scale(8)).inflate(1);
        EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, end, box,
                e -> e instanceof NPCEntity, 64);
        Entity e = hit == null ? null : hit.getEntity();
        return e instanceof NPCEntity n ? n : null;
    }
}
