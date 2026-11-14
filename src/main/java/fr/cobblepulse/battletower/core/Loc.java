package fr.cobblepulse.battletower.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Locale;

/** Position serialisable (dimension + coordonnees + orientation). */
public final class Loc {
    public String world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;

    public Loc() {}

    public Loc(String world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static Loc of(ServerPlayer p) {
        return new Loc(p.serverLevel().dimension().location().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    public ServerLevel level(MinecraftServer server) {
        ResourceLocation id = ResourceLocation.tryParse(world == null ? "minecraft:overworld" : world);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    public boolean teleport(ServerPlayer p) {
        ServerLevel level = level(p.server);
        if (level == null) return false;
        p.stopRiding();
        p.teleportTo(level, x, y, z, yaw, pitch);
        return true;
    }

    public String pretty() {
        return String.format(Locale.ROOT, "%s %.1f %.1f %.1f", world, x, y, z);
    }

    public Loc copy() {
        return new Loc(world, x, y, z, yaw, pitch);
    }
}
