package fr.cobblepulse.battletower.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Titres et sons. */
public final class Fx {
    private Fx() {}

    public static void title(ServerPlayer p, String title, String subtitle) {
        if (p == null || p.connection == null) return;
        try {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(4, 30, 8));
            p.connection.send(new ClientboundSetSubtitleTextPacket(TextUtil.legacy(subtitle == null ? "" : subtitle)));
            p.connection.send(new ClientboundSetTitleTextPacket(TextUtil.legacy(title == null ? "" : title)));
        } catch (Throwable ignored) {
        }
    }

    public static void sound(ServerPlayer p, SoundEvent sound, float volume, float pitch) {
        if (p == null || sound == null) return;
        p.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    public static void sound(ServerPlayer p, String id, float volume, float pitch) {
        sound(p, soundById(id, SoundEvents.PLAYER_LEVELUP), volume, pitch);
    }

    public static SoundEvent soundById(String id, SoundEvent fallback) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id.trim());
        if (rl == null || !BuiltInRegistries.SOUND_EVENT.containsKey(rl)) return fallback;
        return BuiltInRegistries.SOUND_EVENT.get(rl);
    }

    public static void click(ServerPlayer p) {
        sound(p, SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.2f);
    }
}
