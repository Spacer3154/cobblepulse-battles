package fr.cobblepulse.battletower.net;

import fr.cobblepulse.battletower.Battletower;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Paquets reseau de l'editeur de PNJ (le mod est requis cote client pour l'editeur et la galerie de skins). */
public final class Payloads {
    private static final StreamCodec<ByteBuf, String> BIG_STRING = ByteBufCodecs.stringUtf8(1 << 20);

    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> makeType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Battletower.MOD_ID, path));
    }

    /** Serveur -> client : ouvre l'editeur (kind = floor/boss, id = etage ou identifiant du boss). */
    public record OpenEditor(String kind, String id, String trainerJson, String metaJson) implements CustomPacketPayload {
        public static final Type<OpenEditor> TYPE = makeType("open_editor");
        public static final StreamCodec<ByteBuf, OpenEditor> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenEditor::kind, ByteBufCodecs.STRING_UTF8, OpenEditor::id,
                BIG_STRING, OpenEditor::trainerJson, BIG_STRING, OpenEditor::metaJson, OpenEditor::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : enregistre la fiche du dresseur. */
    public record SaveTrainer(String kind, String id, String trainerJson, boolean force) implements CustomPacketPayload {
        public static final Type<SaveTrainer> TYPE = makeType("save_trainer");
        public static final StreamCodec<ByteBuf, SaveTrainer> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveTrainer::kind, ByteBufCodecs.STRING_UTF8, SaveTrainer::id,
                BIG_STRING, SaveTrainer::trainerJson, ByteBufCodecs.BOOL, SaveTrainer::force, SaveTrainer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : resultat de l'enregistrement (erreurs de validation eventuelles). */
    public record EditorResult(boolean ok, String message, String errorsJson) implements CustomPacketPayload {
        public static final Type<EditorResult> TYPE = makeType("editor_result");
        public static final StreamCodec<ByteBuf, EditorResult> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, EditorResult::ok, ByteBufCodecs.STRING_UTF8, EditorResult::message,
                BIG_STRING, EditorResult::errorsJson, EditorResult::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : demande les octets d'un skin (dossier serveur ou compte Mojang) pour l'apercu 3D. */
    public record SkinRequest(String ref) implements CustomPacketPayload {
        public static final Type<SkinRequest> TYPE = makeType("skin_request");
        public static final StreamCodec<ByteBuf, SkinRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SkinRequest::ref, SkinRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : skin PNG (vide si introuvable). */
    public record SkinData(String ref, boolean slim, byte[] png) implements CustomPacketPayload {
        public static final Type<SkinData> TYPE = makeType("skin_data");
        public static final StreamCodec<ByteBuf, SkinData> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SkinData::ref, ByteBufCodecs.BOOL, SkinData::slim,
                ByteBufCodecs.BYTE_ARRAY, SkinData::png, SkinData::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
