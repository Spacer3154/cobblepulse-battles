package fr.cobblepulse.battletower.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.battletower.core.Json;
import fr.cobblepulse.battletower.data.MonSpec;
import fr.cobblepulse.battletower.data.ShowdownFormat;
import fr.cobblepulse.battletower.data.TrainerSpec;
import fr.cobblepulse.battletower.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Editeur graphique complet d'un dresseur PNJ : parametres strategiques, equipe (1 a 6 Pokemon : espece, forme,
 * niveau, nature, talent, shiny, objet, IV/EV, capacites et PP, type Tera, Mega/Tera autorisees) et apparence
 * (galerie de skins avec apercu 3D, skin d'un compte Minecraft). Import/export au format Showdown.
 */
public final class TrainerEditorScreen extends Screen {
    public record SkinEntry(String ref, String label, boolean slim) {}

    private static final int BG = 0xE0101418;
    private static final int PANEL = 0xF01E242C;
    private static final int ACCENT = 0xFFE0B040;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int MUTED = 0xFF9AA4AE;
    private static final int ERROR = 0xFFFF6B6B;
    private static final int OK = 0xFF6BE07A;

    private static final List<String> FORMATS = List.of("singles", "doubles");
    private static final List<String> AIS = List.of("EASY", "MEDIUM", "HARD", "EXTREME");
    private static final List<Integer> LEVELS = List.of(0, 50, 100);
    private static final List<String> WEATHERS = List.of("none", "sun", "rain", "sand", "snow", "fog");
    private static final List<String> TERRAINS = List.of("none", "electric", "grassy", "psychic", "misty");
    private static final List<String> GENDERS = List.of("", "male", "female", "genderless");
    private static final List<String> DEFAULT_NATURES = List.of("hardy", "lonely", "brave", "adamant", "naughty", "bold", "docile", "relaxed",
            "impish", "lax", "timid", "hasty", "serious", "jolly", "naive", "modest", "mild", "quiet", "bashful", "rash", "calm", "gentle",
            "sassy", "careful", "quirky");
    private static final List<String> DEFAULT_TERA = List.of("normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison",
            "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy", "stellar");

    final String kind;
    final String id;
    TrainerSpec spec;
    private final String heading;
    private final List<SkinEntry> skins = new ArrayList<>();
    private final List<String[]> tiers = new ArrayList<>();
    private final List<String> natures = new ArrayList<>();
    private final List<String> teraTypes = new ArrayList<>();

    private int tab;
    private int sel;
    private int skinPage;
    private float yaw = 20;
    private float pitch = 5;
    private String status = "";
    private int statusColor = MUTED;
    private List<String> errors = new ArrayList<>();
    private boolean lastFailed;
    private String playerSkinInput = "";

    // Geometrie
    private int px;
    private int py;
    private int pw;
    private int ph;
    private EditBox speciesBox;
    private final List<EditBox> moveBoxes = new ArrayList<>();
    private int previewX;
    private int previewY;
    private int previewW;
    private int previewH;

    public TrainerEditorScreen(String kind, String id, TrainerSpec spec, String metaJson) {
        super(Component.literal("Éditeur de dresseur"));
        this.kind = kind;
        this.id = id;
        this.spec = spec == null ? new TrainerSpec() : spec;
        this.spec.normalize();
        String title = "Dresseur";
        try {
            JsonObject meta = JsonParser.parseString(metaJson).getAsJsonObject();
            if (meta.has("title")) title = meta.get("title").getAsString();
            for (JsonElement e : meta.getAsJsonArray("skins")) {
                JsonObject o = e.getAsJsonObject();
                skins.add(new SkinEntry(o.get("ref").getAsString(), o.get("label").getAsString(), o.get("slim").getAsBoolean()));
            }
            for (JsonElement e : meta.getAsJsonArray("tiers")) {
                JsonObject o = e.getAsJsonObject();
                tiers.add(new String[]{o.get("id").getAsString(), o.get("name").getAsString()});
            }
            if (meta.has("natures")) for (JsonElement e : meta.getAsJsonArray("natures")) natures.add(e.getAsString());
            if (meta.has("teraTypes")) for (JsonElement e : meta.getAsJsonArray("teraTypes")) teraTypes.add(e.getAsString().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
        }
        if (natures.isEmpty()) natures.addAll(DEFAULT_NATURES);
        if (teraTypes.isEmpty()) teraTypes.addAll(DEFAULT_TERA);
        if (tiers.isEmpty()) tiers.add(new String[]{"ag", "AG"});
        heading = title;
        if (this.spec.skin.startsWith("player:")) playerSkinInput = this.spec.skin.substring(7);
    }

    // ------------------------------------------------------------------ Construction

    /** Surface minimale (en pixels d'interface) dont l'editeur a besoin. */
    private static final int MIN_W = 470;
    private static final int MIN_H = 300;

    /**
     * Avec une grande echelle d'interface (ex. 1280x720 en echelle automatique = 426x240), l'editeur ne tiendrait pas :
     * on reduit l'echelle tant que l'ecran est ouvert, puis on la restaure a la fermeture.
     */
    private void fitGuiScale() {
        com.mojang.blaze3d.platform.Window win = minecraft.getWindow();
        int cur = (int) win.getGuiScale();
        int s = cur;
        while (s > 1 && (win.getWidth() / s < MIN_W || win.getHeight() / s < MIN_H)) s--;
        if (s != cur) {
            win.setGuiScale(s);
            this.width = win.getGuiScaledWidth();
            this.height = win.getGuiScaledHeight();
        }
    }

    @Override
    public void removed() {
        super.removed();
        com.mojang.blaze3d.platform.Window win = minecraft.getWindow();
        int s = win.calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode());
        if (s != (int) win.getGuiScale()) win.setGuiScale(s);
    }

    @Override
    protected void init() {
        fitGuiScale();
        pw = Math.min(width - 10, 460);
        ph = Math.min(height - 10, 290);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        moveBoxes.clear();
        speciesBox = null;
        boolean world = "world".equals(kind);
        if (world && spec.world == null) spec.world = new TrainerSpec.WorldOptions();
        String[] tabs = world ? new String[]{"Paramètres", "Équipe (" + spec.team.size() + "/6)", "Apparence", "Récompenses"}
                : new String[]{"Paramètres", "Équipe (" + spec.team.size() + "/6)", "Apparence"};
        int tx = px + 6;
        for (int i = 0; i < tabs.length; i++) {
            int idx = i;
            int w = font.width(tabs[i]) + 14;
            Button b = Button.builder(Component.literal((tab == i ? "> " : "") + tabs[i]), btn -> {
                tab = idx;
                rebuildWidgets();
            }).bounds(tx, py + 18, w + (tab == i ? 8 : 0), 16).build();
            addRenderableWidget(b);
            tx += w + (tab == i ? 8 : 0) + 3;
        }
        String importLabel = world ? "Importer" : "Importer Showdown";
        int iw = font.width(importLabel) + 12;
        addRenderableWidget(Button.builder(Component.literal(importLabel), b -> minecraft.setScreen(new ImportScreen(this)))
                .bounds(px + pw - 90 - iw, py + 18, iw, 16).tooltip(Tooltip.create(Component.literal("Coller une équipe exportée depuis le teambuilder de Pokémon Showdown"))).build());
        addRenderableWidget(Button.builder(Component.literal("Exporter"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(ShowdownFormat.export(spec.team));
            setStatus("Équipe copiée dans le presse-papiers (format Showdown).", OK);
        }).bounds(px + pw - 87, py + 18, 80, 16).build());
        switch (tab) {
            case 0 -> initGeneral();
            case 1 -> initTeam();
            case 3 -> initWorld();
            default -> initAppearance();
        }
        int by = py + ph - 22;
        addRenderableWidget(Button.builder(Component.literal("Enregistrer"), b -> save(false)).bounds(px + pw - 250, by, 80, 18).build());
        if (lastFailed) {
            addRenderableWidget(Button.builder(Component.literal("Forcer"), b -> save(true)).bounds(px + pw - 166, by, 70, 18)
                    .tooltip(Tooltip.create(Component.literal("Enregistrer malgré les erreurs (les Pokémon invalides seront ignorés en combat)"))).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> onClose()).bounds(px + pw - 92, by, 86, 18).build());
    }

    private EditBox box(int x, int y, int w, String value, int max, Consumer<String> responder) {
        EditBox e = new EditBox(font, x, y, w, 16, Component.empty());
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setResponder(responder);
        addRenderableWidget(e);
        return e;
    }

    private EditBox intBox(int x, int y, int w, int value, int min, int maxV, IntConsumer responder) {
        return box(x, y, w, String.valueOf(value), 3, s -> {
            try {
                int v = Integer.parseInt(s.trim());
                responder.accept(Math.max(min, Math.min(maxV, v)));
            } catch (NumberFormatException ignored) {
            }
        });
    }

    private <T> CycleButton<T> cycle(int x, int y, int w, String label, Collection<T> values, T initial, java.util.function.Function<T, String> name,
                                     Consumer<T> onChange) {
        List<T> list = new ArrayList<>(values);
        if (!list.contains(initial)) list.add(0, initial);
        CycleButton<T> b = CycleButton.<T>builder(v -> Component.literal(name.apply(v))).withValues(list).withInitialValue(initial)
                .create(x, y, w, 16, Component.literal(label), (btn, v) -> onChange.accept(v));
        addRenderableWidget(b);
        return b;
    }

    // ------------------------------------------------------------------ Onglet parametres

    private void initGeneral() {
        int c1 = px + 10;
        int c2 = px + pw / 2 + 4;
        int lw = 62;
        int fw = pw / 2 - lw - 18;
        int y = py + 44;
        box(c1 + lw, y, fw, spec.name, 48, s -> spec.name = s);
        box(c2 + lw, y, fw, spec.title, 32, s -> spec.title = s);
        y += 22;
        cycle(c1, y, pw / 2 - 18, "Format", FORMATS, spec.doubles() ? "doubles" : "singles",
                v -> "doubles".equals(v) ? "Duo (2v2)" : "Solo (1v1)", v -> spec.format = v);
        List<String> tierIds = new ArrayList<>();
        for (String[] t : tiers) tierIds.add(t[0]);
        cycle(c2, y, pw / 2 - 18, "Tier", tierIds, spec.tier, this::tierName, v -> spec.tier = v);
        y += 22;
        cycle(c1, y, pw / 2 - 18, "IA", AIS, spec.aiLevel().name(), this::aiName, v -> spec.ai = v);
        cycle(c2, y, pw / 2 - 18, "Niveau imposé", LEVELS, LEVELS.contains(spec.levelScale) ? spec.levelScale : spec.levelScale,
                v -> v == 0 ? "Aucun" : "Niveau " + v, v -> spec.levelScale = v);
        y += 22;
        cycle(c1, y, pw / 2 - 18, "Combat inverse", List.of(false, true), spec.inverse, v -> v ? "Oui" : "Non", v -> spec.inverse = v);
        y += 22;
        cycle(c1, y, pw / 2 - 18, "Météo", WEATHERS, spec.weather, TrainerEditorScreen::weatherName, v -> spec.weather = v);
        cycle(c2, y, pw / 2 - 18, "Terrain", TERRAINS, spec.terrain, TrainerEditorScreen::terrainName, v -> spec.terrain = v);
        y += 30;
        int mx = c1 + Math.max(font.width("Message d'intro"), font.width("Message de défaite")) + 8;
        box(mx, y, px + pw - 10 - mx, spec.introMessage, 200, s -> spec.introMessage = s);
        y += 22;
        box(mx, y, px + pw - 10 - mx, spec.defeatMessage, 200, s -> spec.defeatMessage = s);
    }

    // ------------------------------------------------------------------ Onglet recompenses (dresseurs du monde)

    private static final List<Integer> REMATCH = List.of(-1, 0, 1, 6, 12, 24, 48, 168);
    private static final List<Integer> LOSS_WAIT = List.of(0, 5, 15, 30, 60, 180, 1440);

    private static String hours(int h) {
        return switch (h) {
            case -1 -> "Jamais (une seule victoire)";
            case 0 -> "Immédiate";
            case 168 -> "7 jours";
            default -> h + " h";
        };
    }

    private static String minutes(int m) {
        if (m == 0) return "Aucun";
        if (m >= 60 && m % 60 == 0) return (m / 60) + " h";
        return m + " min";
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void initWorld() {
        TrainerSpec.WorldOptions o = spec.world;
        int c1 = px + 10;
        int c2 = px + pw / 2 + 4;
        int half = pw / 2 - 18;
        int y = py + 44;
        cycle(c1, y, half, "Mode", List.of("normal", "exhibition"), o.mode, v -> "exhibition".equals(v) ? "Exhibition" : "Normal", v -> o.mode = v);
        cycle(c2, y, half, "Revanche", REMATCH, o.rematchHours, TrainerEditorScreen::hours, v -> o.rematchHours = v);
        y += 22;
        cycle(c1, y, half, "Récomp. revanche", List.of(false, true), o.rematchRewards, v -> v ? "Oui" : "Non", v -> o.rematchRewards = v);
        cycle(c2, y, half, "Après défaite", LOSS_WAIT, o.lossCooldownMinutes, TrainerEditorScreen::minutes, v -> o.lossCooldownMinutes = v);
        y += 26;
        int lx = c1 + Math.max(font.width("Phrase de victoire"), font.width("Commandes")) + 8;
        box(lx, y, 60, String.valueOf(o.bp), 7, s -> {
            try {
                o.bp = Math.max(0, Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        });
        y += 22;
        box(lx, y, px + pw - 10 - lx, String.join("; ", o.items), 600, s -> o.items = splitList(s));
        y += 22;
        box(lx, y, px + pw - 10 - lx, String.join("; ", o.commands), 1500, s -> o.commands = splitList(s));
        y += 22;
        box(lx, y, px + pw - 10 - lx, o.winMessage, 200, s -> o.winMessage = s);
    }

    private void renderWorldLabels(GuiGraphics g) {
        int c1 = px + 10;
        int y = py + 44 + 22 + 26 + 4;
        g.drawString(font, "Battle Points", c1, y, TEXT, false);
        g.drawString(font, "Objets", c1, y + 22, TEXT, false);
        g.drawString(font, "Commandes", c1, y + 44, TEXT, false);
        g.drawString(font, "Phrase de victoire", c1, y + 66, TEXT, false);
        g.drawWordWrap(font, net.minecraft.network.chat.FormattedText.of(
                        "Objets et commandes séparés par « ; » (ex. cobblemon:rare_candy 3; minecraft:diamond 2). Commandes lancées par la console, %player% = joueur. "
                                + "Normal : l'équipe réelle du joueur combat (expérience, PV et objets). Exhibition : copie soignée, sans gain."),
                c1, y + 88, pw - 20, MUTED);
    }

    private String tierName(String id) {
        for (String[] t : tiers) if (t[0].equals(id)) return t[1];
        return id;
    }

    private String aiName(String v) {
        return switch (v) {
            case "MEDIUM" -> "Moyen (calcul de dégâts)";
            case "HARD" -> "Difficile (lecture de jeu)";
            case "EXTREME" -> "Extrême (minimax)";
            default -> "Facile (types et placement)";
        };
    }

    static String weatherName(String w) {
        return switch (w) {
            case "sun" -> "Soleil Intense";
            case "rain" -> "Pluie Battante";
            case "sand" -> "Tempête de Sable";
            case "snow" -> "Grêle / Neige";
            case "fog" -> "Brouillard Épais";
            default -> "Aucune";
        };
    }

    static String terrainName(String t) {
        return switch (t) {
            case "electric" -> "Champ Électrifié";
            case "grassy" -> "Champ Herbu";
            case "psychic" -> "Champ Psychique";
            case "misty" -> "Champ Brumeux";
            default -> "Aucun";
        };
    }

    // ------------------------------------------------------------------ Onglet equipe

    private void initTeam() {
        int lx = px + 8;
        int y0 = py + 42;
        for (int i = 0; i < 6; i++) {
            int idx = i;
            String label = i < spec.team.size() ? (i + 1) + ". " + cap(spec.team.get(i).species)
                    + (spec.team.get(i).form.isBlank() ? "" : "-" + cap(spec.team.get(i).form)) + " " + spec.team.get(i).level : "+ Ajouter";
            Button b = Button.builder(Component.literal((sel == i && i < spec.team.size() ? "> " : "") + label), btn -> {
                if (idx >= spec.team.size()) {
                    if (spec.team.size() < 6) {
                        MonSpec m = new MonSpec();
                        m.species = "pikachu";
                        m.level = 50;
                        spec.team.add(m);
                        sel = spec.team.size() - 1;
                    }
                } else {
                    sel = idx;
                }
                rebuildWidgets();
            }).bounds(lx, y0 + i * 20, 96, 18).build();
            b.active = i <= spec.team.size();
            addRenderableWidget(b);
        }
        if (spec.team.isEmpty()) return;
        sel = Math.max(0, Math.min(sel, spec.team.size() - 1));
        MonSpec m = spec.team.get(sel);
        int x = px + 112;
        int w = pw - 120;
        int y = py + 42;
        // Ligne 1 : espece, forme, surnom
        speciesBox = box(x + 40, y, 90, m.species, 40, s -> m.species = s.trim().toLowerCase(Locale.ROOT));
        box(x + 170, y, 70, m.form, 40, s -> m.form = s.trim().toLowerCase(Locale.ROOT));
        box(x + 286, y, w - 290, m.nickname, 24, s -> m.nickname = s);
        y += 20;
        // Ligne 2 : niveau, nature, talent, sexe
        intBox(x + 40, y, 28, m.level, 1, 100, v -> m.level = v);
        cycle(x + 74, y, 90, "Nature", natures, m.nature.isEmpty() ? natures.get(0) : m.nature, TrainerEditorScreen::cap, v -> m.nature = v);
        box(x + 204, y, 66, m.ability, 40, s -> m.ability = s.trim().toLowerCase(Locale.ROOT));
        cycle(x + 274, y, w - 278, "Sexe", GENDERS, m.gender, v -> v.isEmpty() ? "Auto" : "male".equals(v) ? "Mâle" : "female".equals(v) ? "Femelle" : "Asexué",
                v -> m.gender = v);
        y += 20;
        // Ligne 3 : objet, shiny
        box(x + 40, y, 150, m.heldItem, 80, s -> m.heldItem = s.trim().toLowerCase(Locale.ROOT));
        cycle(x + 196, y, 70, "Shiny", List.of(false, true), m.shiny, v -> v ? "Oui" : "Non", v -> m.shiny = v);
        y += 28;
        // IV / EV
        int sx = x + 30;
        int cw = (w - 34) / 6;
        for (int i = 0; i < 6; i++) {
            String st = MonSpec.STATS.get(i);
            intBox(sx + i * cw, y, cw - 4, m.iv(st), 0, 31, v -> m.ivs.put(st, v));
            intBox(sx + i * cw, y + 18, cw - 4, m.ev(st), 0, 252, v -> m.evs.put(st, v));
        }
        y += 50;
        // Capacites + PP
        while (m.moves.size() < 4) m.moves.add(new MonSpec.MoveSpec(""));
        for (int i = 0; i < 4; i++) {
            MonSpec.MoveSpec mv = m.moves.get(i);
            int mx = x + (i % 2) * (w / 2);
            int my = y + (i / 2) * 20;
            EditBox mb = box(mx + 12, my, w / 2 - 74, mv.id, 40, s -> mv.id = s.trim().toLowerCase(Locale.ROOT));
            moveBoxes.add(mb);
            intBox(mx + w / 2 - 60, my, 24, mv.ppMax, 0, 64, v -> mv.ppMax = v);
            intBox(mx + w / 2 - 32, my, 24, mv.pp < 0 ? 0 : mv.pp, 0, 64, v -> mv.pp = v == 0 ? -1 : v);
        }
        y += 44;
        // Tera + autorisations IA
        List<String> tera = new ArrayList<>();
        tera.add("");
        tera.addAll(teraTypes);
        cycle(x, y, 120, "Tera", tera, m.teraType, v -> v.isEmpty() ? "Aucun" : cap(v), v -> m.teraType = v);
        cycle(x + 124, y, 100, "Mega IA", List.of(false, true), m.allowMega, v -> v ? "Oui" : "Non", v -> m.allowMega = v);
        cycle(x + 228, y, 100, "Tera IA", List.of(false, true), m.allowTera, v -> v ? "Oui" : "Non", v -> m.allowTera = v);
        y += 22;
        addRenderableWidget(Button.builder(Component.literal("Supprimer"), b -> {
            spec.team.remove(sel);
            sel = Math.max(0, sel - 1);
            rebuildWidgets();
        }).bounds(x, y, 70, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Monter"), b -> {
            if (sel > 0) {
                spec.team.add(sel - 1, spec.team.remove(sel));
                sel--;
                rebuildWidgets();
            }
        }).bounds(x + 74, y, 56, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Descendre"), b -> {
            if (sel < spec.team.size() - 1) {
                spec.team.add(sel + 1, spec.team.remove(sel));
                sel++;
                rebuildWidgets();
            }
        }).bounds(x + 134, y, 66, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Dupliquer"), b -> {
            if (spec.team.size() < 6) {
                spec.team.add(Json.COMPACT.fromJson(Json.COMPACT.toJson(m), MonSpec.class));
                sel = spec.team.size() - 1;
                rebuildWidgets();
            }
        }).bounds(x + 204, y, 66, 16).build());
    }

    // ------------------------------------------------------------------ Onglet apparence

    private void initAppearance() {
        int gx = px + 8;
        int gy = py + 42;
        int cols = 3;
        int rowsN = Math.max(3, (ph - 120) / 20);
        int perPage = cols * rowsN;
        int pages = Math.max(1, (skins.size() + perPage - 1) / perPage);
        skinPage = Math.max(0, Math.min(skinPage, pages - 1));
        int bw = 92;
        for (int i = 0; i < perPage; i++) {
            int idx = skinPage * perPage + i;
            if (idx >= skins.size()) break;
            SkinEntry s = skins.get(idx);
            boolean selected = s.ref().equals(spec.skin);
            Button b = Button.builder(Component.literal((selected ? "> " : "") + s.label()), btn -> {
                spec.skin = s.ref();
                rebuildWidgets();
            }).bounds(gx + (i % cols) * (bw + 3), gy + (i / cols) * 20, bw, 18)
                    .tooltip(Tooltip.create(Component.literal(s.ref() + (s.slim() ? " (bras fins)" : "")))).build();
            addRenderableWidget(b);
        }
        int navY = gy + rowsN * 20 + 2;
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                skinPage--;
                rebuildWidgets();
            }).bounds(gx, navY, 20, 16).build()).active = skinPage > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                skinPage++;
                rebuildWidgets();
            }).bounds(gx + 24, navY, 20, 16).build()).active = skinPage < pages - 1;
        }
        int py2 = navY + 22;
        EditBox name = box(gx + 74, py2, 110, playerSkinInput, 16, s -> playerSkinInput = s.trim());
        name.setHint(Component.literal("Pseudo Minecraft"));
        addRenderableWidget(Button.builder(Component.literal("Appliquer"), b -> {
            if (!playerSkinInput.isEmpty()) {
                spec.skin = "player:" + playerSkinInput;
                SkinCache.forget(spec.skin);
                rebuildWidgets();
            }
        }).bounds(gx + 188, py2, 70, 16).build());
        previewW = pw - (cols * (bw + 3)) - 24;
        previewH = ph - 70;
        previewX = px + pw - previewW - 8;
        previewY = py + 42;
    }

    // ------------------------------------------------------------------ Rendu

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, BG);
        g.fill(px, py, px + pw, py + ph, PANEL);
        g.fill(px, py + 37, px + pw, py + 38, ACCENT);
        if (tab == 2) g.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0x60000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, heading, px + 8, py + 6, ACCENT, false);
        g.drawString(font, spec.display(), px + pw - 8 - font.width(spec.display()), py + 6, MUTED, false);
        switch (tab) {
            case 0 -> renderGeneralLabels(g);
            case 1 -> renderTeamLabels(g);
            case 3 -> renderWorldLabels(g);
            default -> renderAppearance(g);
        }
        int sy = py + ph - 20;
        if (!status.isEmpty()) g.drawString(font, status, px + 8, sy - (errors.isEmpty() ? 0 : 10), statusColor, false);
        int ey = sy;
        for (int i = 0; i < Math.min(errors.size(), 2); i++) {
            g.drawString(font, font.plainSubstrByWidth("- " + errors.get(i), pw - 270), px + 8, ey + i * 9, ERROR, false);
        }
    }

    private void renderGeneralLabels(GuiGraphics g) {
        int c1 = px + 10;
        int c2 = px + pw / 2 + 4;
        int y = py + 48;
        g.drawString(font, "Nom", c1, y, TEXT, false);
        g.drawString(font, "Titre", c2, y, TEXT, false);
        g.drawString(font, "Message d'intro", c1, py + 44 + 22 * 5 + 8 + 4, TEXT, false);
        g.drawString(font, "Message de défaite", c1, py + 44 + 22 * 6 + 8 + 4, TEXT, false);
        g.drawWordWrap(font, net.minecraft.network.chat.FormattedText.of(
                        "Le tier vérifie l'équipe du joueur avant chaque combat. Le niveau imposé s'applique aux deux camps."),
                c1, py + 44 + 22 * 7 + 12, pw - 20, MUTED);
    }

    private void renderTeamLabels(GuiGraphics g) {
        if (spec.team.isEmpty()) {
            g.drawString(font, "Ajoutez un Pokémon avec + Ajouter, ou importez une équipe Showdown.", px + 112, py + 48, MUTED, false);
            return;
        }
        int x = px + 112;
        int w = pw - 120;
        int y = py + 46;
        g.drawString(font, "Espèce", x, y, TEXT, false);
        g.drawString(font, "Forme", x + 136, y, TEXT, false);
        g.drawString(font, "Surnom", x + 246, y, TEXT, false);
        y += 20;
        g.drawString(font, "Niveau", x, y, TEXT, false);
        g.drawString(font, "Talent", x + 170, y, TEXT, false);
        y += 20;
        g.drawString(font, "Objet", x, y, TEXT, false);
        // Memes ordonnees que les widgets (initTeam) : IV/EV puis capacites
        int iy = py + 42 + 20 + 20 + 28;
        int sx = x + 30;
        int cw = (w - 34) / 6;
        String[] labels = {"PV", "Atq", "Déf", "AtqS", "DéfS", "Vit"};
        for (int i = 0; i < 6; i++) g.drawString(font, labels[i], sx + i * cw + 2, iy - 10, MUTED, false);
        g.drawString(font, "IV", x, iy + 4, TEXT, false);
        g.drawString(font, "EV", x, iy + 22, TEXT, false);
        MonSpec m = spec.team.get(sel);
        int total = 0;
        for (String st : MonSpec.STATS) total += m.ev(st);
        String evTxt = "EV : " + total + "/510";
        g.drawString(font, evTxt, x + w - font.width(evTxt) - 2, iy - 24, total > 510 ? ERROR : MUTED, false);
        int my0 = iy + 50;
        for (int i = 0; i < 4; i++) {
            int mx = x + (i % 2) * (w / 2);
            int my = my0 + (i / 2) * 20 + 4;
            g.drawString(font, String.valueOf(i + 1), mx + 2, my, TEXT, false);
        }
        String ppTxt = "PP max / PP actuels (0 = normal)";
        g.drawString(font, ppTxt, x + w - font.width(ppTxt) - 2, my0 - 11, MUTED, false);
        String suggestion = suggestion();
        if (!suggestion.isEmpty()) g.drawString(font, suggestion, x, py + ph - 34, MUTED, false);
    }

    /** Suggestions d'especes / capacites (registres Cobblemon synchronises au client), Tab pour completer. */
    private String suggestion() {
        try {
            if (speciesBox != null && speciesBox.isFocused()) {
                List<String> s = ClientRegistries.species(speciesBox.getValue(), 5);
                return s.isEmpty() ? "" : "Espèces : " + String.join(", ", s) + " (Tab)";
            }
            for (EditBox mb : moveBoxes) {
                if (mb.isFocused()) {
                    List<String> s = ClientRegistries.moves(mb.getValue(), 5);
                    return s.isEmpty() ? "" : "Capacités : " + String.join(", ", s) + " (Tab)";
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void renderAppearance(GuiGraphics g) {
        int gx = px + 8;
        int rowsN = Math.max(3, (ph - 120) / 20);
        int navY = py + 42 + rowsN * 20 + 2;
        g.drawString(font, "Skin joueur", gx, navY + 26, TEXT, false);
        boolean slim = false;
        for (SkinEntry s : skins) if (s.ref().equals(spec.skin)) slim = s.slim();
        SkinCache.Skin skin = SkinCache.get(spec.skin, slim);
        int cx = previewX + previewW / 2;
        int feet = previewY + previewH - 18;
        float size = Math.min(previewH - 30, previewW * 1.6f) / 2.1f;
        if (skin != null) {
            SkinPreview.render(g, skin, cx, feet, size, yaw, pitch);
        } else {
            String msg = SkinCache.missing(spec.skin) ? "Skin introuvable" : "Chargement...";
            g.drawCenteredString(font, msg, cx, previewY + previewH / 2, MUTED);
        }
        g.drawCenteredString(font, spec.skin, cx, previewY + previewH - 12, MUTED);
        g.drawCenteredString(font, "Glisser pour tourner", cx, previewY + 4, MUTED);
    }

    // ------------------------------------------------------------------ Entrees

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (tab == 2 && mouseX >= previewX && mouseX <= previewX + previewW && mouseY >= previewY && mouseY <= previewY + previewH) {
            yaw += (float) dx * 2.5f;
            pitch = Math.max(-30, Math.min(30, pitch - (float) dy));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_TAB) {
            try {
                if (speciesBox != null && speciesBox.isFocused()) {
                    List<String> s = ClientRegistries.species(speciesBox.getValue(), 1);
                    if (!s.isEmpty()) speciesBox.setValue(s.get(0));
                    return true;
                }
                for (EditBox mb : moveBoxes) {
                    if (mb.isFocused()) {
                        List<String> s = ClientRegistries.moves(mb.getValue(), 1);
                        if (!s.isEmpty()) mb.setValue(s.get(0));
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ Enregistrement

    private void save(boolean force) {
        for (MonSpec m : spec.team) m.moves.removeIf(mv -> mv.id == null || mv.id.isBlank());
        spec.normalize();
        if (!ClientPlayNetworking.canSend(Payloads.SaveTrainer.TYPE)) {
            setStatus("Serveur injoignable (mode hors-ligne).", ERROR);
            return;
        }
        ClientPlayNetworking.send(new Payloads.SaveTrainer(kind, id, Json.COMPACT.toJson(spec), force));
        setStatus("Enregistrement...", MUTED);
    }

    void onResult(Payloads.EditorResult r) {
        errors = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(r.errorsJson()).getAsJsonArray();
            for (JsonElement e : arr) errors.add(e.getAsString());
        } catch (Exception ignored) {
        }
        lastFailed = !r.ok();
        setStatus(r.message(), r.ok() ? OK : ERROR);
        rebuildWidgets();
    }

    /** Auto-test client : change d'onglet. */
    void setTab(int t) {
        tab = t;
        rebuildWidgets();
    }

    void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    /** Remplace l'equipe par une equipe importee (format Showdown). */
    void importTeam(List<MonSpec> team) {
        if (team.isEmpty()) {
            setStatus("Aucun Pokémon reconnu dans le texte collé.", ERROR);
            return;
        }
        spec.team = new ArrayList<>(team);
        sel = 0;
        tab = 1;
        setStatus(team.size() + " Pokémon importé(s). Vérifiez puis enregistrez.", OK);
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
