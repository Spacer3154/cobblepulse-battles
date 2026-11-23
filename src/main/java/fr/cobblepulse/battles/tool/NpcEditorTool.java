package fr.cobblepulse.battles.tool;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import fr.cobblepulse.battles.trainers.TrainerCommands;
import fr.cobblepulse.battles.trainers.TrainerMenus;
import fr.cobblepulse.battles.trainers.WorldTrainers;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.core.Loc;
import fr.cobblepulse.battletower.core.Msg;
import fr.cobblepulse.battletower.core.Perms;
import fr.cobblepulse.battletower.gui.ChatInput;
import fr.cobblepulse.battletower.menu.AdminMenus;
import fr.cobblepulse.battletower.net.EditorService;
import fr.cobblepulse.battletower.npc.TrainerNpcs;
import fr.cobblepulse.battletower.tower.TowerManager;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Objet d'administration « Editeur de PNJ » : clic droit sur un PNJ de CobblePulse-Battles (etage de la Tour, Boss du
 * Jour, boutique, dresseur du monde) pour ouvrir son editeur ; sur un bloc pour poser un nouveau dresseur ; dans le vide
 * pour la liste des dresseurs. Reserve au staff.
 */
public final class NpcEditorTool {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Battletower.MOD_ID, "editeur_pnj");
    public static Item ITEM;

    private NpcEditorTool() {}

    public static void register() {
        ITEM = Registry.register(BuiltInRegistries.ITEM, ID, new EditorItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.OP_BLOCKS).register(e -> e.accept(ITEM));
    }

    public static boolean is(ItemStack st) {
        return ITEM != null && st != null && st.is(ITEM);
    }

    public static boolean allowed(ServerPlayer p) {
        return Perms.admin(p, TrainerCommands.PERM_ADMIN) || Perms.isAdmin(p);
    }

    public static ItemStack stack() {
        return new ItemStack(ITEM);
    }

    public static void give(ServerPlayer p) {
        ItemStack st = stack();
        if (!p.getInventory().add(st)) p.drop(st, false);
        Msg.send(p, "tool.given");
    }

    /** Clic droit sur un PNJ avec l'outil (le PNJ n'est pas « utilise » : pas de combat ni de boutique). */
    public static void onNpc(ServerPlayer p, NPCEntity npc) {
        if (!allowed(p)) {
            Msg.send(p, "tool.no_permission");
            return;
        }
        if (!TrainerNpcs.isOurs(npc)) {
            Msg.send(p, "tool.not_ours");
            return;
        }
        if (WorldTrainers.isWorld(npc)) {
            WorldTrainers.WorldTrainer w = WorldTrainers.of(npc);
            if (w == null) Msg.send(p, "tool.not_ours");
            else EditorService.openWorld(p, w.id);
            return;
        }
        if (npc.getTags().contains(TrainerNpcs.TAG_BOSS)) {
            String boss = TowerManager.state().currentBossId;
            if (boss == null || boss.isBlank()) AdminMenus.bosses(p);
            else EditorService.openBoss(p, boss);
            return;
        }
        if (npc.getTags().contains(TrainerNpcs.TAG_SHOP)) {
            AdminMenus.panel(p);
            return;
        }
        int floor = TrainerNpcs.floorOf(npc);
        if (floor > 0) EditorService.openFloor(p, floor);
        else Msg.send(p, "tool.not_ours");
    }

    public static void onAir(ServerPlayer p) {
        if (!allowed(p)) {
            Msg.send(p, "tool.no_permission");
            return;
        }
        TrainerMenus.list(p);
    }

    /** Clic droit sur un bloc : nouveau dresseur pose sur ce bloc, tourne vers le joueur. */
    public static void onBlock(ServerPlayer p, BlockPos clicked, Direction face) {
        if (!allowed(p)) {
            Msg.send(p, "tool.no_permission");
            return;
        }
        BlockPos at = face == Direction.UP || face == null ? clicked.above() : clicked.relative(face);
        Loc loc = new Loc(p.serverLevel().dimension().location().toString(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                net.minecraft.util.Mth.wrapDegrees(p.getYRot() + 180f), 0f);
        ChatInput.ask(p, "trainer.ask_id", id -> {
            WorldTrainers.WorldTrainer w = WorldTrainers.create(p, id, loc);
            if (w == null) Msg.send(p, "trainer.create_failed", "id", id);
            else {
                Msg.send(p, "trainer.created", "id", w.id);
                EditorService.openWorld(p, w.id);
            }
        }, () -> {
        });
    }

    static final class EditorItem extends Item {
        EditorItem(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack st = player.getItemInHand(hand);
            if (!level.isClientSide() && player instanceof ServerPlayer sp) onAir(sp);
            return InteractionResultHolder.sidedSuccess(st, level.isClientSide());
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            if (!ctx.getLevel().isClientSide() && ctx.getPlayer() instanceof ServerPlayer sp) onBlock(sp, ctx.getClickedPos(), ctx.getClickedFace());
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide());
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.cobblepulse_battles.editeur_pnj.npc").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.cobblepulse_battles.editeur_pnj.block").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.cobblepulse_battles.editeur_pnj.air").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.cobblepulse_battles.editeur_pnj.staff").withStyle(ChatFormatting.DARK_RED));
        }
    }
}
