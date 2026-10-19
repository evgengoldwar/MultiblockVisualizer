package multiblockvisualizer.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.FontRenderer;

/** Minimal flat button drawn in the visualizer's style. */
public class FlatButton {

    public int x, y, width, height = 18;
    private final Supplier<String> label;
    private final Runnable action;
    private BooleanSupplier visible = () -> true;
    private BooleanSupplier enabled = () -> true;
    private BooleanSupplier active = () -> false;
    private Supplier<String> tooltip = () -> null;

    public FlatButton(Supplier<String> label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    public FlatButton visibleWhen(BooleanSupplier visible) {
        this.visible = visible;
        return this;
    }

    public FlatButton enabledWhen(BooleanSupplier enabled) {
        this.enabled = enabled;
        return this;
    }

    public FlatButton activeWhen(BooleanSupplier active) {
        this.active = active;
        return this;
    }

    public FlatButton tooltip(Supplier<String> tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public FlatButton fixedWidth(int width) {
        this.width = width;
        return this;
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }

    public int measure(FontRenderer font) {
        return width > 0 ? width : font.getStringWidth(label.get()) + 14;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return isVisible() && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public String getTooltip() {
        return tooltip.get();
    }

    public boolean click(int mouseX, int mouseY) {
        if (!isHovered(mouseX, mouseY) || !enabled.getAsBoolean()) return false;
        action.run();
        return true;
    }

    public void draw(FontRenderer font, int mouseX, int mouseY) {
        if (!isVisible()) return;
        boolean isEnabled = enabled.getAsBoolean();
        boolean hovered = isEnabled && isHovered(mouseX, mouseY);
        boolean isActive = active.getAsBoolean();

        int border = !isEnabled ? 0x30FFFFFF
            : hovered ? GuiMultiblockVisualizer.ACCENT : isActive ? 0xC0E6B450 : 0x50FFFFFF;
        int fill = hovered ? 0xE0302A20 : isActive ? 0xD0282418 : 0xD0181C24;
        GuiMultiblockVisualizer.drawRoundedPanel(x, y, width, height, fill, border);

        int color = !isEnabled ? 0x606060 : hovered || isActive ? 0xFFE6B450 : 0xFFE0E0E0;
        String text = label.get();
        font.drawStringWithShadow(text, x + (width - font.getStringWidth(text)) / 2, y + (height - 8) / 2, color);
    }
}
