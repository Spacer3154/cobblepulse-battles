package fr.cobblepulse.battletower.client;

import fr.cobblepulse.battletower.data.ShowdownFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Collage d'une equipe au format texte de Pokemon Showdown. */
final class ImportScreen extends Screen {
    private final TrainerEditorScreen parent;
    private MultiLineEditBox area;

    ImportScreen(TrainerEditorScreen parent) {
        super(Component.literal("Importer une equipe Showdown"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = Math.min(width - 20, 420);
        int h = Math.min(height - 70, 220);
        int x = (width - w) / 2;
        int y = 30;
        area = new MultiLineEditBox(font, x, y, w, h, Component.literal("Collez ici l'export du teambuilder (Ctrl+V)"), Component.empty());
        area.setCharacterLimit(20000);
        addRenderableWidget(area);
        addRenderableWidget(Button.builder(Component.literal("Importer"), b -> {
            parent.importTeam(ShowdownFormat.parse(area.getValue()));
            minecraft.setScreen(parent);
        }).bounds(x + w - 180, y + h + 8, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Annuler"), b -> minecraft.setScreen(parent)).bounds(x + w - 90, y + h + 8, 90, 20).build());
        setInitialFocus(area);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFE0B040);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
