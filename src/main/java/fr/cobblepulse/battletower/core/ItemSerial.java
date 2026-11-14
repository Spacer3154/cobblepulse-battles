package fr.cobblepulse.battletower.core;

import fr.cobblepulse.battletower.Battletower;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Serialisation d'inventaires (NBT compresse en Base64). A appeler sur le thread principal. */
public final class ItemSerial {
    private ItemSerial() {}

    private static HolderLookup.Provider registries() {
        return Battletower.server().registryAccess();
    }

    public static String encode(List<ItemStack> items) {
        try {
            NonNullList<ItemStack> list = NonNullList.withSize(items.size(), ItemStack.EMPTY);
            for (int i = 0; i < items.size(); i++) list.set(i, items.get(i) == null ? ItemStack.EMPTY : items.get(i));
            CompoundTag tag = new CompoundTag();
            tag.putInt("Size", items.size());
            ContainerHelper.saveAllItems(tag, list, registries());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Serialisation d'items impossible", e);
            return "";
        }
    }

    public static List<ItemStack> decode(String data) {
        List<ItemStack> out = new ArrayList<>();
        if (data == null || data.isEmpty()) return out;
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(data)), NbtAccounter.unlimitedHeap());
            int size = tag.getInt("Size");
            NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, list, registries());
            out.addAll(list);
        } catch (Exception e) {
            Battletower.LOG.error("[Battletower] Deserialisation d'items impossible", e);
        }
        return out;
    }

    public static String encode(SimpleContainer c) {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) l.add(c.getItem(i));
        return encode(l);
    }

    public static void decodeInto(String data, SimpleContainer c) {
        List<ItemStack> l = decode(data);
        for (int i = 0; i < c.getContainerSize(); i++) {
            c.setItem(i, i < l.size() ? l.get(i) : ItemStack.EMPTY);
        }
    }

    public static List<ItemStack> nonEmpty(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) if (s != null && !s.isEmpty()) out.add(s);
        return out;
    }
}
