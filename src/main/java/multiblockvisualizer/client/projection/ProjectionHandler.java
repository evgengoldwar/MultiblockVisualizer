package multiblockvisualizer.client.projection;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import multiblockvisualizer.ClientProxy;
import multiblockvisualizer.Config;
import multiblockvisualizer.client.gui.GuiMultiblockVisualizer;

/** Input, world rendering and the build progress panel of the in-world projection. */
public class ProjectionHandler {

    private static final int PANEL_WIDTH = 160;
    private static final int BAR_COLOR = 0xFFE6B450;
    private static final int DONE_COLOR = 0xFF5FD068;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final RenderItem itemRender = new RenderItem();
    private final Projection projection = Projection.INSTANCE;
    private float shownProgress;
    private long lastFrame;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.theWorld == null) {
            projection.remove();
            return;
        }
        if (mc.currentScreen == null) {
            while (ClientProxy.ROTATE_KEY.isPressed()) projection.rotate();
            // cycles all -> bottom ... top -> all, backwards all -> top ... bottom -> all
            while (ClientProxy.LAYER_UP_KEY.isPressed()) stepLayer(1);
            while (ClientProxy.LAYER_DOWN_KEY.isPressed()) stepLayer(-1);
            while (ClientProxy.LAYER_ALL_KEY.isPressed()) projection.setLayer(-1);
            while (ClientProxy.DETACH_KEY.isPressed()) {
                if (projection.getState() == Projection.State.PLACED && !GuiScreen.isShiftKeyDown()) {
                    projection.detach();
                } else {
                    projection.remove();
                }
            }
        }
        projection.onClientTick();
    }

    private void stepLayer(int direction) {
        int layers = projection.getLayerCount();
        if (layers <= 0) return;
        // -1 (all layers) sits between the top and the bottom layer
        int next = projection.getLayer() + direction;
        if (next >= layers) next = -1;
        else if (next < -1) next = layers - 1;
        projection.setLayer(next);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button != 1 || !event.buttonstate || mc.currentScreen != null) return;
        if (projection.getState() != Projection.State.PLACING) return;
        // right click places the projection instead of using the item/block
        if (projection.tryPlace(mc.thePlayer != null && mc.thePlayer.isSneaking())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        projection.renderWorld(event.partialTicks);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) projection.remove();
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (projection.getState() == Projection.State.NONE || projection.getController() == null) return;
        // only in the world view, a GUI on top would cover it anyway
        if (!Config.projectionPanel || mc.gameSettings.showDebugInfo || mc.currentScreen != null) return;

        long now = System.nanoTime();
        float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

        FontRenderer font = mc.fontRenderer;
        List<String> hints = new ArrayList<>();
        String rotate = key(ClientProxy.ROTATE_KEY), detach = key(ClientProxy.DETACH_KEY);
        boolean placing = projection.getState() == Projection.State.PLACING;
        if (placing) {
            hints.add(I18n.format("multiblockvisualizer.projection.place"));
            hints.add(I18n.format("multiblockvisualizer.projection.place_replace"));
            hints.add(I18n.format("multiblockvisualizer.projection.rotate", rotate));
            hints.add(I18n.format("multiblockvisualizer.projection.cancel", detach));
        } else {
            hints.add(I18n.format("multiblockvisualizer.projection.rotate", rotate));
            hints.add(
                I18n.format(
                    "multiblockvisualizer.projection.layers",
                    key(ClientProxy.LAYER_UP_KEY),
                    key(ClientProxy.LAYER_DOWN_KEY)));
            if (projection.getLayer() >= 0) {
                hints.add(I18n.format("multiblockvisualizer.projection.layer_all", key(ClientProxy.LAYER_ALL_KEY)));
            }
            hints.add(I18n.format("multiblockvisualizer.projection.detach", detach));
            hints.add(I18n.format("multiblockvisualizer.projection.remove", detach));
        }

        if (!Config.projectionHints) hints.clear();
        int height = 24 + (placing ? 0 : 26) + hints.size() * 10 + (hints.isEmpty() ? 0 : 4);
        if (!placing && projection.getWrong() > 0) height += 10;
        if (!placing && projection.getVariants() > 0) height += 10;
        if (!placing && projection.getLayer() >= 0) height += 18;
        int x = 6;
        int y = event.resolution.getScaledHeight() / 2 - height / 2;

        // keep every GL state we touch (item lighting, colour, depth...) away from screens drawn afterwards,
        // NEI's structure preview rendered washed out grey otherwise
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GuiMultiblockVisualizer.drawRoundedPanel(x, y, PANEL_WIDTH, height, 0xC0101418, 0x40FFFFFF);

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.renderItemAndEffectIntoGUI(font, mc.getTextureManager(), projection.getController(), x + 4, y + 4);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        String name = font.trimStringToWidth(
            projection.getController()
                .getDisplayName(),
            PANEL_WIDTH - 28);
        font.drawStringWithShadow(name, x + 24, y + 5, 0xFFFFFF);
        String subtitle = placing ? I18n.format("multiblockvisualizer.projection.placing")
            : I18n.format("multiblockvisualizer.projection.title");
        font.drawString(subtitle, x + 24, y + 15, 0x9A9AA6);

        int lineY = y + 26;
        if (!placing) {
            int total = Math.max(1, projection.getTotal());
            float progress = projection.getCorrect() / (float) total;
            shownProgress += (progress - shownProgress) * (1 - (float) Math.exp(-delta * 8));
            boolean done = projection.isCompleted();

            String count = done ? EnumChatFormatting.GREEN + I18n.format("multiblockvisualizer.projection.done")
                : I18n.format(
                    "multiblockvisualizer.projection.progress",
                    projection.getCorrect(),
                    projection.getTotal(),
                    Math.round(progress * 100));
            font.drawStringWithShadow(count, x + 5, lineY, 0xE0E0E0);
            lineY += 11;

            int barX = x + 5, barW = PANEL_WIDTH - 10;
            Gui.drawRect(barX, lineY, barX + barW, lineY + 6, 0x60000000);
            int fill = Math.round(barW * Math.min(1, shownProgress));
            Gui.drawRect(barX, lineY, barX + fill, lineY + 6, done ? DONE_COLOR : BAR_COLOR);
            // soft highlight on the upper half of the bar
            Gui.drawRect(barX, lineY, barX + fill, lineY + 2, 0x40FFFFFF);
            lineY += 12;

            if (projection.getLayer() >= 0) {
                // progress of the layer being built
                int layerTotal = Math.max(1, projection.getLayerTotal());
                float layerProgress = projection.getLayerCorrect() / (float) layerTotal;
                font.drawStringWithShadow(
                    I18n.format(
                        "multiblockvisualizer.projection.layer",
                        projection.getLayer() + 1,
                        projection.getLayerCount(),
                        projection.getLayerCorrect(),
                        projection.getLayerTotal()),
                    x + 5,
                    lineY,
                    0x9AD0FF);
                lineY += 10;
                Gui.drawRect(barX, lineY, barX + barW, lineY + 3, 0x60000000);
                Gui.drawRect(barX, lineY, barX + Math.round(barW * layerProgress), lineY + 3, 0xFF7AB8F0);
                lineY += 8;
            }

            if (projection.getWrong() > 0) {
                font.drawStringWithShadow(
                    I18n.format("multiblockvisualizer.projection.wrong", projection.getWrong()),
                    x + 5,
                    lineY,
                    0xFF6060);
                lineY += 10;
            }
            if (projection.getVariants() > 0) {
                font.drawStringWithShadow(
                    I18n.format("multiblockvisualizer.projection.variants", projection.getVariants()),
                    x + 5,
                    lineY,
                    0xFFA040);
                lineY += 10;
            }
        } else {
            shownProgress = 0;
        }

        lineY += 2;
        for (String hint : hints) {
            font.drawString(font.trimStringToWidth(hint, PANEL_WIDTH - 10), x + 5, lineY, 0x808080);
            lineY += 10;
        }
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static String key(KeyBinding binding) {
        return GameSettings.getKeyDisplayString(binding.getKeyCode());
    }
}
