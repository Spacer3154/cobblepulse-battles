package fr.cobblepulse.battletower.npc;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.npc.NPCPlayerModelType;
import com.cobblemon.mod.common.entity.npc.NPCPlayerTexture;
import fr.cobblepulse.battletower.Battletower;
import fr.cobblepulse.battletower.config.Cfg;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Skins des PNJ :
 * <ul>
 *   <li>builtin:&lt;nom&gt; : textures embarquees dans le .jar (assets/cobblepulse_battletower/textures/entity/npc/*.png) ;</li>
 *   <li>server:&lt;fichier.png&gt; : dossier config/cobblepulse_battletower/skins/ (ajout via FileZilla, sans mise a jour du mod) ;</li>
 *   <li>player:&lt;pseudo&gt; : skin officiel Mojang du compte.</li>
 * </ul>
 * Les octets du skin sont transmis aux clients par Cobblemon (donnee synchronisee du PNJ) : rien a telecharger.
 */
public final class Skins {
    public static final String ASSET_DIR = "/assets/cobblepulse_battletower/textures/entity/npc/";

    public record SkinInfo(String ref, String label, boolean slim) {}

    private static final Map<String, SkinInfo> BUILTIN = new LinkedHashMap<>();
    private static final Map<String, byte[]> CACHE = new LinkedHashMap<>();

    private Skins() {}

    public static void load() {
        BUILTIN.clear();
        CACHE.clear();
        try (InputStream in = Skins.class.getResourceAsStream(ASSET_DIR + "index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\|");
                    String name = parts[0].trim();
                    boolean slim = parts.length > 1 && parts[1].trim().equalsIgnoreCase("slim");
                    String label = parts.length > 2 ? parts[2].trim() : name;
                    BUILTIN.put(name, new SkinInfo("builtin:" + name, label, slim));
                }
            }
        } catch (Exception e) {
            Battletower.LOG.warn("[Battletower] Index des skins embarques illisible : {}", e.toString());
        }
    }

    public static List<SkinInfo> builtin() {
        return new ArrayList<>(BUILTIN.values());
    }

    /** Skins du dossier serveur ; un nom finissant par _slim (ou .slim.png) utilise les bras fins. */
    public static List<SkinInfo> server() {
        List<SkinInfo> out = new ArrayList<>();
        Path dir = Cfg.skinsDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().forEach(p -> {
                String f = p.getFileName().toString();
                String low = f.toLowerCase(Locale.ROOT);
                boolean slim = low.endsWith("_slim.png") || low.endsWith(".slim.png");
                out.add(new SkinInfo("server:" + f, f.replaceAll("(?i)\\.png$", ""), slim));
            });
        } catch (Exception e) {
            Battletower.LOG.warn("[Battletower] Lecture du dossier skins impossible : {}", e.toString());
        }
        return out;
    }

    public static boolean isSlim(String ref) {
        if (ref == null) return false;
        if (ref.startsWith("builtin:")) {
            SkinInfo i = BUILTIN.get(ref.substring(8));
            return i != null && i.slim();
        }
        String low = ref.toLowerCase(Locale.ROOT);
        return low.endsWith("_slim.png") || low.endsWith(".slim.png");
    }

    /** Octets PNG d'un skin embarque ou serveur (null pour player:). */
    public static byte[] bytes(String ref) {
        if (ref == null) return null;
        byte[] cached = CACHE.get(ref);
        if (cached != null) return cached;
        byte[] data = null;
        try {
            if (ref.startsWith("builtin:")) {
                String name = ref.substring(8).replaceAll("[^A-Za-z0-9_\\-]", "");
                try (InputStream in = Skins.class.getResourceAsStream(ASSET_DIR + name + ".png")) {
                    if (in != null) data = in.readAllBytes();
                }
            } else if (ref.startsWith("server:")) {
                String file = ref.substring(7);
                // Chemins absolus normalises des deux cotes : le dossier de config peut etre "./config/..." (relatif),
                // et normalize() retire le "./" seulement du fichier, ce qui faisait echouer startsWith()
                Path dir = Cfg.skinsDir().toAbsolutePath().normalize();
                Path p = dir.resolve(file).toAbsolutePath().normalize();
                if (p.startsWith(dir) && Files.isRegularFile(p)) data = Files.readAllBytes(p);
                else Battletower.LOG.warn("[Battletower] Skin serveur introuvable : {}", p);
            }
        } catch (Exception e) {
            Battletower.LOG.warn("[Battletower] Skin illisible {} : {}", ref, e.toString());
        }
        if (data != null && !ref.startsWith("server:")) CACHE.put(ref, data);
        return data;
    }

    /** Applique un skin a un PNJ Cobblemon (synchronise automatiquement vers les clients). */
    public static boolean apply(NPCEntity npc, String ref) {
        if (npc == null || ref == null || ref.isBlank()) return false;
        try {
            if (ref.startsWith("player:")) {
                String name = ref.substring(7).trim();
                if (name.isEmpty()) return false;
                npc.loadTextureFromGameProfileName(name);
                return true;
            }
            byte[] png = bytes(ref);
            if (png == null) {
                Battletower.LOG.warn("[Battletower] Skin introuvable : {}", ref);
                return false;
            }
            npc.getEntityData().set(NPCEntity.Companion.getNPC_PLAYER_TEXTURE(),
                    new NPCPlayerTexture(png, isSlim(ref) ? NPCPlayerModelType.SLIM : NPCPlayerModelType.DEFAULT));
            return true;
        } catch (Throwable t) {
            Battletower.LOG.warn("[Battletower] Application du skin {} impossible : {}", ref, t.toString());
            return false;
        }
    }

    /** Renvoie au joueur la texture du PNJ (apres son apparition chez le client). */
    public static void resend(NPCEntity npc, net.minecraft.server.level.ServerPlayer player) {
        try {
            if (npc == null || player == null || !npc.isAlive() || player.hasDisconnected()) return;
            var acc = NPCEntity.Companion.getNPC_PLAYER_TEXTURE();
            NPCPlayerTexture tex = npc.getEntityData().get(acc);
            if (tex == null || tex.getModel() == NPCPlayerModelType.NONE) return;
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(npc.getId(),
                    java.util.List.of(net.minecraft.network.syncher.SynchedEntityData.DataValue.create(acc, tex))));
        } catch (Throwable t) {
            Battletower.LOG.debug("[Battletower] Renvoi du skin impossible : {}", t.toString());
        }
    }

    /** Texture actuellement portee par le PNJ (apres chargement d'un skin Mojang par exemple). */
    public static byte[] current(NPCEntity npc) {
        try {
            NPCPlayerTexture t = npc.getEntityData().get(NPCEntity.Companion.getNPC_PLAYER_TEXTURE());
            return t == null ? null : t.getTexture();
        } catch (Throwable t) {
            return null;
        }
    }
}
