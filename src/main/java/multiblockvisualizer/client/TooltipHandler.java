package multiblockvisualizer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import multiblockvisualizer.ClientProxy;
import multiblockvisualizer.Config;
import multiblockvisualizer.client.gui.GuiMultiblockVisualizer;

/**
 * Adds the "Hold [key] to view structure" line to multiblock controllers and opens the visualizer once the key has
 * been held long enough, Ponder style.
 */
public class TooltipHandler {

    private final Minecraft mc = Minecraft.getMinecraft();

    private ItemStack hoveredStack;
    private int hoveredTick = -100;
    private int clientTicks;
    private int holdTicks;
    private float partialTicks;
    private boolean insideRender;
    private boolean waitForRelease;

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        insideRender = event.phase == TickEvent.Phase.START;
        partialTicks = event.renderTickTime;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onTooltip(ItemTooltipEvent event) {
        // Tooltips are also built off-screen (NEI search indexing etc.), only react to the ones actually drawn.
        if (!insideRender || mc.currentScreen == null || mc.currentScreen instanceof GuiMultiblockVisualizer) return;
        if (!StructureHelper.isMultiblock(event.itemStack)) return;

        hoveredStack = event.itemStack;
        hoveredTick = clientTicks;

        if (Config.showTooltipHint || holdTicks > 0) {
            event.toolTip.add(Math.min(1, event.toolTip.size()), buildHintLine());
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        clientTicks++;

        boolean keyDown = isKeyDown();
        if (!keyDown) waitForRelease = false;

        GuiScreen screen = mc.currentScreen;
        boolean hovering = hoveredStack != null && clientTicks - hoveredTick <= 2;
        if (screen == null || screen instanceof GuiMultiblockVisualizer || !hovering) {
            hoveredStack = null;
            holdTicks = 0;
            return;
        }

        if (keyDown && !waitForRelease) {
            if (++holdTicks >= Config.holdTicks) {
                holdTicks = 0;
                waitForRelease = true;
                open(hoveredStack, screen);
            }
        } else {
            holdTicks = Math.max(0, holdTicks - 2);
        }
    }

    private void open(ItemStack stack, GuiScreen parent) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        // Skip parent.onGuiClosed(): we come back to it afterwards, and closing a GuiContainer client-side would
        // e.g. throw the crafting grid contents out of the player inventory.
        mc.currentScreen = null;
        mc.displayGuiScreen(new GuiMultiblockVisualizer(copy, parent));
    }

    private boolean isKeyDown() {
        int code = ClientProxy.OPEN_KEY.getKeyCode();
        if (code == 0) return false;
        if (code < 0) return Mouse.isButtonDown(code + 100);
        return Keyboard.isKeyDown(code);
    }

    private String buildHintLine() {
        String key = GameSettings.getKeyDisplayString(ClientProxy.OPEN_KEY.getKeyCode());
        String text = I18n.format("multiblockvisualizer.tooltip.hold", key);

        if (holdTicks <= 0) {
            return EnumChatFormatting.DARK_GRAY + "["
                + EnumChatFormatting.GRAY
                + key
                + EnumChatFormatting.DARK_GRAY
                + "] "
                + EnumChatFormatting.GRAY
                + text;
        }

        // Fill the text itself as a progress bar while the key is held
        float progress = Math.min(1f, (holdTicks + (isKeyDown() ? partialTicks : 0)) / (float) Config.holdTicks);
        String full = "[" + key + "] " + text;
        int filled = Math.round(full.length() * progress);
        return EnumChatFormatting.GOLD + full.substring(0, filled) + EnumChatFormatting.DARK_GRAY + full.substring(filled);
    }
}
