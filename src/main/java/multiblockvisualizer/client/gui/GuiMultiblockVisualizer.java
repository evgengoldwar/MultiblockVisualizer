package multiblockvisualizer.client.gui;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.ForgeHooksClient;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.util.glu.GLU;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;

import blockrenderer6343.client.utils.ConstructableData;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import multiblockvisualizer.Config;
import multiblockvisualizer.MultiblockVisualizer;
import multiblockvisualizer.client.StructureHelper;
import multiblockvisualizer.client.scene.StructureScene;
import multiblockvisualizer.client.scene.StructureScene.AnimUnit;

/**
 * Ponder-like viewer: the structure assembles itself layer by layer, blocks dropping into place, and can then be
 * rotated, zoomed, sliced into layers and inspected.
 */
public class GuiMultiblockVisualizer extends GuiScreen {

    public static final int ACCENT = 0xFFE6B450;

    private static final float FOV = 40f;
    private static final float UNIT_DURATION = 0.45f;
    private static final float DROP_HEIGHT = 1.6f;
    private static final float INTRO_DURATION = 0.9f;
    private static final float AUTO_ROTATE_SPEED = 9f;
    private static final int ROW_HEIGHT = 18;
    // LWJGL 2 only exposes these through ARB_imaging, the values are core since OpenGL 1.4
    private static final int GL_CONSTANT_ALPHA = 0x8003;
    private static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;
    private static final float GHOST_ALPHA = 0.12f;
    private static final ResourceLocation LAND_SOUND = new ResourceLocation("random.click");

    private final ItemStack controller;
    private final GuiScreen parent;
    private final IConstructable constructable;
    private StructureScene scene;

    private int tier = 1;
    private int maxTier = 1;
    private int layer = -1;
    private List<ItemStack> ingredients = Collections.emptyList();
    private int ingredientScroll;

    // camera
    private float yaw, pitch, distance;
    private float centerX, centerY, centerZ;
    private boolean autoRotate = Config.autoRotate;

    // animation
    private long animStart;
    private long lastFrame;
    private float lastUnitStart;
    private int lastSoundLayer;
    private long lastSoundTime;

    // layout
    private int sceneX, sceneY, sceneW, sceneH;
    private int panelX, panelY, panelW, panelH;
    private final List<FlatButton> buttons = new ArrayList<>();
    private FlatButton closeButton;

    // input
    private int dragButton = -1;
    private int lastMouseX, lastMouseY;

    // picking
    private final FloatBuffer modelMatrix = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projMatrix = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer unprojected = BufferUtils.createFloatBuffer(3);
    private final LongSet pickTargets = new LongOpenHashSet();
    private MovingObjectPosition hovered;
    private final float[] pickState = new float[12];
    private final float[] lastPickState = new float[12];

    // cached floor plate
    private int floorList = -1;
    private float floorListY;

    public GuiMultiblockVisualizer(ItemStack controller, GuiScreen parent) {
        this.controller = controller;
        this.parent = parent;
        this.constructable = StructureHelper.getConstructable(controller);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        int margin = 8;
        int header = 34;
        int footer = 30;
        panelW = Math.max(100, Math.min(150, width / 4));
        sceneX = margin;
        sceneY = header;
        sceneW = width - panelW - margin * 3;
        sceneH = height - header - footer - margin;
        panelX = sceneX + sceneW + margin;
        panelY = sceneY;
        panelH = sceneH;

        if (scene == null) {
            ConstructableData data = constructable == null ? null : ConstructableData.getTierData(constructable);
            maxTier = data == null ? 1 : Math.max(1, data.getMaxTotalTier());
            scene = new StructureScene();
            rebuild(true);
        }

        buildButtons();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        // Display lists are recreated lazily if we come back (e.g. from an NEI recipe page)
        if (scene != null) scene.releaseGl();
        releaseFloor();
    }

    private void releaseFloor() {
        if (floorList >= 0) GLAllocation.deleteDisplayLists(floorList);
        floorList = -1;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    private void rebuild(boolean resetCamera) {
        long start = System.nanoTime();
        scene.build(controller, tier);
        releaseFloor();
        if (scene.getSizeY() > 0 && layer >= scene.getSizeY()) layer = -1;
        lastUnitStart = scene.layoutTimeline(UNIT_DURATION / Config.animationSpeed, Config.animationSpeed);
        pickTargets.clear();
        for (AnimUnit unit : scene.units) pickTargets.addAll(unit.blocks);
        refreshIngredients();
        if (resetCamera) resetCamera();
        replay();
        MultiblockVisualizer.LOG.debug(
            "Built {} ({} blocks) in {} ms",
            controller.getDisplayName(),
            scene.blockCount,
            (System.nanoTime() - start) / 1_000_000);
    }

    private void refreshIngredients() {
        try {
            ingredients = scene.getIngredients(layer);
        } catch (Throwable t) {
            MultiblockVisualizer.LOG.warn("Failed to collect ingredients", t);
            ingredients = Collections.emptyList();
        }
        ingredientScroll = 0;
    }

    private void replay() {
        scene.setSelection(null, layer);
        animStart = System.nanoTime();
        lastSoundLayer = -1;
    }

    private float getTotalDuration() {
        return lastUnitStart + UNIT_DURATION / Config.animationSpeed;
    }

    /** Highlights all blocks of a component, clicking the same one again clears it. */
    private void toggleSelection(ItemStack stack) {
        if (StructureScene.sameItem(stack, scene.getSelection())) {
            scene.setSelection(null, layer);
            return;
        }
        // the highlight shows the finished structure
        float time = (System.nanoTime() - animStart) / 1e9f;
        if (time < getTotalDuration()) {
            animStart = System.nanoTime() - (long) ((getTotalDuration() + 0.01f) * 1e9);
            lastSoundLayer = scene.getSizeY();
        }
        scene.setSelection(stack, layer);
    }

    private void resetCamera() {
        centerX = scene.minX + scene.getSizeX() / 2f;
        centerY = scene.minY + scene.getSizeY() / 2f;
        centerZ = scene.minZ + scene.getSizeZ() / 2f;
        yaw = -35f;
        pitch = 25f;
        distance = fitDistance();
    }

    private float fitDistance() {
        float sx = scene.getSizeX(), sy = scene.getSizeY(), sz = scene.getSizeZ();
        float radius = (float) Math.sqrt(sx * sx + sy * sy + sz * sz) / 2f;
        float aspect = sceneH > 0 ? sceneW / (float) sceneH : 1f;
        float fit = radius / (float) Math.sin(Math.toRadians(FOV / 2));
        if (aspect < 1) fit /= aspect;
        return Math.max(3f, fit * 0.95f);
    }

    private void buildButtons() {
        buttons.clear();
        buttons.add(new FlatButton(() -> I18n.format("multiblockvisualizer.gui.replay"), this::replay));
        buttons.add(new FlatButton(() -> "<", () -> setLayer(layer - 1)).fixedWidth(18));
        buttons.add(
            new FlatButton(
                () -> I18n.format(
                    "multiblockvisualizer.gui.layer",
                    layer < 0 ? I18n.format("multiblockvisualizer.gui.all")
                        : (layer + 1) + "/" + scene.getSizeY()),
                () -> setLayer(-1)).fixedWidth(86)
                    .activeWhen(() -> layer >= 0)
                    .tooltip(() -> I18n.format("multiblockvisualizer.gui.layer.tooltip")));
        buttons.add(new FlatButton(() -> ">", () -> setLayer(layer + 1)).fixedWidth(18));
        buttons.add(
            new FlatButton(() -> "-", () -> setTier(tier - 1)).fixedWidth(18)
                .visibleWhen(() -> maxTier > 1)
                .enabledWhen(() -> tier > 1));
        buttons.add(
            new FlatButton(() -> I18n.format("multiblockvisualizer.gui.tier", tier), () -> {}).fixedWidth(60)
                .visibleWhen(() -> maxTier > 1)
                .tooltip(() -> I18n.format("multiblockvisualizer.gui.tier.tooltip")));
        buttons.add(
            new FlatButton(() -> "+", () -> setTier(tier + 1)).fixedWidth(18)
                .visibleWhen(() -> maxTier > 1)
                .enabledWhen(() -> tier < maxTier));
        buttons.add(
            new FlatButton(() -> I18n.format("multiblockvisualizer.gui.rotate"), () -> autoRotate = !autoRotate)
                .activeWhen(() -> autoRotate));
        buttons.add(new FlatButton(() -> I18n.format("multiblockvisualizer.gui.reset_view"), this::resetCamera));
        buttons.add(new FlatButton(() -> "?", () -> {}).fixedWidth(18).tooltip(GuiMultiblockVisualizer::getControlsHelp));

        int gap = 3;
        int total = -gap;
        for (FlatButton button : buttons) {
            if (!button.isVisible()) continue;
            button.width = button.measure(fontRendererObj);
            total += button.width + gap;
        }
        int x = sceneX + (sceneW - total) / 2;
        int y = sceneY + sceneH + 8;
        for (FlatButton button : buttons) {
            button.x = x;
            button.y = y;
            if (button.isVisible()) x += button.width + gap;
        }

        closeButton = new FlatButton(() -> "x", this::close).fixedWidth(16);
        closeButton.height = 16;
        closeButton.x = width - 8 - 16;
        closeButton.y = 8;
    }

    private static String getControlsHelp() {
        StringBuilder help = new StringBuilder(EnumChatFormatting.GOLD + I18n.format("multiblockvisualizer.gui.controls"));
        for (int i = 1; i <= 7; i++) {
            help.append('\n')
                .append(EnumChatFormatting.GRAY)
                .append(I18n.format("multiblockvisualizer.gui.controls." + i));
        }
        return help.toString();
    }

    private void setLayer(int newLayer) {
        int layers = scene.getSizeY();
        if (newLayer < -1) newLayer = layers - 1;
        if (newLayer >= layers) newLayer = -1;
        if (newLayer == layer) return;
        layer = newLayer;
        refreshIngredients();
        if (scene.getSelection() != null) scene.setSelection(scene.getSelection(), layer);
    }

    private void setTier(int newTier) {
        newTier = Math.max(1, Math.min(maxTier, newTier));
        if (newTier == tier) return;
        tier = newTier;
        rebuild(false);
        // the structure may have grown, keep it in frame
        distance = Math.max(distance, fitDistance());
        centerY = scene.minY + scene.getSizeY() / 2f;
        buildButtons();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float time = (now - animStart) / 1e9f;

        if (autoRotate && dragButton < 0) yaw += AUTO_ROTATE_SPEED * delta;

        drawGradientRect(0, 0, width, height, 0xE8101318, 0xF0080A0E);

        drawRoundedPanel(sceneX - 1, sceneY - 1, sceneW + 2, sceneH + 2, 0x30000000, 0x20FFFFFF);
        hovered = null;
        if (!scene.isEmpty()) {
            if (!scene.isCompiled()) scene.compile();
            renderScene(mouseX, mouseY, time, partialTicks);
            playLandingSounds(time);
        } else {
            String msg = I18n.format(
                scene.error != null ? "multiblockvisualizer.gui.error" : "multiblockvisualizer.gui.empty");
            drawCenteredString(fontRendererObj, msg, sceneX + sceneW / 2, sceneY + sceneH / 2 - 4, 0xFF8080);
        }

        drawHeader(time);
        drawIngredientPanel(mouseX, mouseY);
        for (FlatButton button : buttons) button.draw(fontRendererObj, mouseX, mouseY);
        closeButton.draw(fontRendererObj, mouseX, mouseY);

        drawTooltips(mouseX, mouseY);
    }

    private void drawHeader(float time) {
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), controller, 10, 9);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);

        fontRendererObj.drawStringWithShadow(
            EnumChatFormatting.BOLD + controller.getDisplayName(),
            32,
            8,
            0xFFFFFF);

        String info = I18n.format(
            "multiblockvisualizer.gui.info",
            scene.getSizeX(),
            scene.getSizeY(),
            scene.getSizeZ(),
            scene.blockCount);
        float progress = getBuildProgress(time);
        if (progress < 1) {
            info += "  " + EnumChatFormatting.GOLD
                + I18n.format("multiblockvisualizer.gui.building", Math.round(progress * 100));
        }
        fontRendererObj.drawString(info, 32, 19, 0x9A9AA6);

        // thin progress line under the header, Ponder style
        int lineY = sceneY - 4;
        drawRect(sceneX, lineY, sceneX + sceneW, lineY + 1, 0x20FFFFFF);
        drawRect(sceneX, lineY, sceneX + Math.round(sceneW * progress), lineY + 1, ACCENT);
    }

    private float getBuildProgress(float time) {
        if (scene.isEmpty()) return 1;
        return Math.max(0, Math.min(1, time / getTotalDuration()));
    }

    private void drawIngredientPanel(int mouseX, int mouseY) {
        drawRoundedPanel(panelX, panelY, panelW, panelH, 0xC0101418, 0x30FFFFFF);
        String title = I18n.format(
            layer < 0 ? "multiblockvisualizer.gui.components" : "multiblockvisualizer.gui.components_layer");
        fontRendererObj.drawStringWithShadow(title, panelX + 6, panelY + 6, ACCENT);
        drawRect(panelX + 4, panelY + 17, panelX + panelW - 4, panelY + 18, 0x30FFFFFF);

        int listY = panelY + 21;
        int rows = getVisibleRows();
        int maxScroll = Math.max(0, ingredients.size() - rows);
        ingredientScroll = Math.max(0, Math.min(maxScroll, ingredientScroll));

        for (int i = 0; i < rows && i + ingredientScroll < ingredients.size(); i++) {
            ItemStack stack = ingredients.get(i + ingredientScroll);
            int rowY = listY + i * ROW_HEIGHT;
            if (StructureScene.sameItem(stack, scene.getSelection())) {
                drawRoundedPanel(panelX + 2, rowY, panelW - 4, ROW_HEIGHT, 0x40E6B450, ACCENT);
            } else if (mouseX >= panelX + 2 && mouseX < panelX + panelW - 2 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                drawRect(panelX + 2, rowY, panelX + panelW - 2, rowY + ROW_HEIGHT, 0x30FFFFFF);
            }

            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            ItemStack icon = stack.copy();
            icon.stackSize = 1;
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, panelX + 4, rowY + 1);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            String count = formatCount(stack.stackSize);
            int countWidth = fontRendererObj.getStringWidth(count);
            fontRendererObj.drawStringWithShadow(count, panelX + 24, rowY + 5, ACCENT);
            int nameX = panelX + 24 + countWidth + 4;
            String name = fontRendererObj.trimStringToWidth(stack.getDisplayName(), panelX + panelW - 6 - nameX);
            fontRendererObj.drawString(name, nameX, rowY + 5, 0xD0D0D0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }

        if (maxScroll > 0) {
            int trackH = rows * ROW_HEIGHT;
            int barH = Math.max(8, trackH * rows / ingredients.size());
            int barY = listY + (trackH - barH) * ingredientScroll / maxScroll;
            drawRect(panelX + panelW - 3, listY, panelX + panelW - 2, listY + trackH, 0x20FFFFFF);
            drawRect(panelX + panelW - 3, barY, panelX + panelW - 2, barY + barH, 0xA0FFFFFF);
        }
    }

    private int getVisibleRows() {
        return Math.max(1, (panelH - 24) / ROW_HEIGHT);
    }

    private ItemStack getHoveredIngredient(int mouseX, int mouseY) {
        int listY = panelY + 21;
        if (mouseX < panelX + 2 || mouseX >= panelX + panelW - 2 || mouseY < listY) return null;
        int row = (mouseY - listY) / ROW_HEIGHT;
        if (row >= getVisibleRows()) return null;
        int index = row + ingredientScroll;
        return index < ingredients.size() ? ingredients.get(index) : null;
    }

    private static String formatCount(int count) {
        if (count >= 10_000) return (count / 1000) + "k";
        return count + "x";
    }

    @SuppressWarnings("unchecked")
    private void drawTooltips(int mouseX, int mouseY) {
        for (FlatButton button : buttons) {
            if (button.isHovered(mouseX, mouseY) && button.getTooltip() != null) {
                drawHoveringText(fontRendererObj.listFormattedStringToWidth(button.getTooltip(), 240), mouseX, mouseY, fontRendererObj);
                return;
            }
        }

        ItemStack ingredient = getHoveredIngredient(mouseX, mouseY);
        if (ingredient != null) {
            List<String> lines = ingredient.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
            lines.add("");
            lines.add(EnumChatFormatting.DARK_GRAY + I18n.format("multiblockvisualizer.gui.nei_hint"));
            drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
            return;
        }

        if (hovered != null && dragButton < 0) {
            ItemStack stack = getPickedStack(hovered);
            if (stack != null) renderToolTip(stack, mouseX, mouseY);
        }
    }

    private ItemStack getPickedStack(MovingObjectPosition mop) {
        try {
            Block block = scene.world.getBlock(mop.blockX, mop.blockY, mop.blockZ);
            return block.getPickBlock(mop, scene.world, mop.blockX, mop.blockY, mop.blockZ, mc.thePlayer);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 3D scene

    private void renderScene(int mouseX, int mouseY, float time, float partialTicks) {
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = res.getScaleFactor();
        int vx = sceneX * scale;
        int vy = mc.displayHeight - (sceneY + sceneH) * scale;
        int vw = sceneW * scale;
        int vh = sceneH * scale;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
        GL11.glViewport(vx, vy, vw, vh);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(vx, vy, vw, vh);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GLU.gluPerspective(FOV, vw / (float) vh, 0.1f, 4000f);

        float intro = easeOutCubic(clamp01(time / INTRO_DURATION));
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0, 0, -distance * (1.25f - 0.25f * intro));
        GL11.glRotatef(pitch, 1, 0, 0);
        GL11.glRotatef(yaw, 0, 1, 0);
        GL11.glTranslatef(-centerX, -centerY, -centerZ);

        modelMatrix.clear();
        projMatrix.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrix);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projMatrix);
        viewport.put(0, vx).put(1, vy).put(2, vw).put(3, vh);

        try {
            mc.entityRenderer.disableLightmap(0);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glColor4f(1, 1, 1, 1);

            drawFloor(intro);
            drawBlocks(time);
            drawTileEntities(time, partialTicks);

            if (isInScene(mouseX, mouseY) && dragButton < 0) {
                hovered = pickCached(time);
                if (hovered != null) drawHighlight(hovered);
            }
        } catch (Throwable t) {
            MultiblockVisualizer.LOG.error("Error while rendering structure preview", t);
        } finally {
            ForgeHooksClient.setRenderPass(-1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private boolean isUnitVisible(AnimUnit unit) {
        return layer < 0 || unit.y == scene.minY + layer;
    }

    private float unitProgress(AnimUnit unit, float time) {
        return (time - unit.startTime) * Config.animationSpeed / UNIT_DURATION;
    }

    private void drawFloor(float alpha) {
        if (alpha <= 0) return;
        float y = (layer < 0 ? scene.minY : scene.minY + layer) - 0.02f;
        if (alpha < 1) {
            drawFloorGeometry(y, alpha);
            return;
        }
        if (floorList < 0 || floorListY != y) {
            if (floorList < 0) floorList = GLAllocation.generateDisplayLists(1);
            floorListY = y;
            GL11.glNewList(floorList, GL11.GL_COMPILE);
            drawFloorGeometry(y, 1f);
            GL11.glEndList();
        }
        GL11.glCallList(floorList);
    }

    private void drawFloorGeometry(float y, float alpha) {
        int x0 = scene.minX - 1, x1 = scene.maxX + 1;
        int z0 = scene.minZ - 1, z1 = scene.maxZ + 1;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glDepthMask(false);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                float a = (((x + z) & 1) == 0 ? 0.22f : 0.14f) * (edge ? 0.5f : 1f) * alpha;
                tessellator.setColorRGBA_F(0.70f, 0.76f, 0.88f, a);
                tessellator.addVertex(x, y, z);
                tessellator.addVertex(x, y, z + 1);
                tessellator.addVertex(x + 1, y, z + 1);
                tessellator.addVertex(x + 1, y, z);
            }
        }
        tessellator.draw();

        GL11.glLineWidth(1.5f);
        tessellator.startDrawing(GL11.GL_LINE_LOOP);
        tessellator.setColorRGBA_F(1f, 1f, 1f, 0.35f * alpha);
        tessellator.addVertex(x0, y, z0);
        tessellator.addVertex(x0, y, z1 + 1);
        tessellator.addVertex(x1 + 1, y, z1 + 1);
        tessellator.addVertex(x1 + 1, y, z0);
        tessellator.draw();

        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void drawBlocks(float time) {
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);

        if (scene.getSelection() != null) {
            drawSelection(time);
            return;
        }

        float duration = UNIT_DURATION / Config.animationSpeed;
        boolean finished = time >= getTotalDuration();
        int fromLayer = layer < 0 ? 0 : layer;
        int toLayer = layer < 0 ? scene.getSizeY() - 1 : layer;

        for (int pass = 0; pass < 2; pass++) {
            ForgeHooksClient.setRenderPass(pass);
            setPassState(pass, 1f);
            if (layer < 0 && finished) {
                // one call for the whole structure, hidden faces culled
                GL11.glCallList(scene.getFullList() + pass);
                continue;
            }

            // landed layers in one call each, settled units of the layers still building one by one
            for (int l = fromLayer; l <= toLayer; l++) {
                if (time >= scene.layerLastStart[l] + duration) {
                    GL11.glCallList(scene.getLayerList(l) + pass);
                    continue;
                }
                for (int i = scene.layerUnitStart[l]; i < scene.layerUnitStart[l + 1]; i++) {
                    AnimUnit unit = scene.units.get(i);
                    if (unitProgress(unit, time) >= 1) GL11.glCallList(unit.list + pass);
                }
            }

            // then the falling ones, blended on top
            for (int l = fromLayer; l <= toLayer; l++) {
                if (time >= scene.layerLastStart[l] + duration) continue;
                for (int i = scene.layerUnitStart[l]; i < scene.layerUnitStart[l + 1]; i++) {
                    AnimUnit unit = scene.units.get(i);
                    float p = unitProgress(unit, time);
                    if (p <= 0 || p >= 1) continue;
                    setPassState(pass, clamp01(p * 3f));
                    GL11.glPushMatrix();
                    applyUnitTransform(unit, p);
                    GL11.glCallList(unit.list + pass);
                    GL11.glPopMatrix();
                }
            }
        }
        setPassState(0, 1f);
    }

    /** Selected component solid and outlined, everything else as a faint ghost. */
    private void drawSelection(float time) {
        for (int pass = 0; pass < 2; pass++) {
            ForgeHooksClient.setRenderPass(pass);
            setPassState(pass, 1f);
            GL11.glCallList(scene.getSelectedList() + pass);
        }

        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendColor(1, 1, 1, GHOST_ALPHA);
        GL11.glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
        GL11.glDepthMask(false);
        for (int pass = 0; pass < 2; pass++) {
            ForgeHooksClient.setRenderPass(pass);
            GL11.glCallList(scene.getGhostList() + pass);
        }
        ForgeHooksClient.setRenderPass(-1);

        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4.0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);
        // faint x-ray outlines for selected blocks hidden behind others, bright ones where visible
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(0.90f, 0.71f, 0.31f, 0.25f);
        GL11.glCallList(scene.getOutlineList());
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(0.90f, 0.71f, 0.31f, 0.55f + 0.45f * pulse);
        GL11.glCallList(scene.getOutlineList());
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        setPassState(0, 1f);
    }

    private static void applyUnitTransform(AnimUnit unit, float p) {
        float drop = (1 - easeOutCubic(p)) * DROP_HEIGHT;
        float s = 0.55f + 0.45f * easeOutBack(p);
        GL11.glTranslatef(unit.centerX, unit.centerY + drop, unit.centerZ);
        GL11.glScalef(s, s, s);
        GL11.glTranslatef(-unit.centerX, -unit.centerY, -unit.centerZ);
    }

    private static void setPassState(int pass, float alpha) {
        GL11.glColor4f(1, 1, 1, 1);
        if (pass == 0 && alpha >= 1) {
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthMask(true);
        } else if (pass == 0) {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(1, 1, 1, alpha);
            GL11.glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
            GL11.glDepthMask(true);
        } else {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
        }
    }

    private void drawTileEntities(float time, float partialTicks) {
        if (scene.renderedTiles.isEmpty()) return;
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        RenderHelper.enableStandardItemLighting();
        for (int pass = 0; pass < 2; pass++) {
            ForgeHooksClient.setRenderPass(pass);
            for (Long2ObjectMap.Entry<TileEntity> entry : scene.renderedTiles.long2ObjectEntrySet()) {
                TileEntity tile = entry.getValue();
                if (!tile.shouldRenderInPass(pass)) continue;
                long pos = entry.getLongKey();
                if (scene.getSelection() != null && !scene.selectedBlocks.contains(pos)) continue;
                AnimUnit unit = scene.units.get(scene.unitOfBlock.get(pos));
                if (!isUnitVisible(unit)) continue;
                float p = unitProgress(unit, time);
                if (p <= 0) continue;

                setPassState(pass, 1f);
                GL11.glPushMatrix();
                if (p < 1) applyUnitTransform(unit, p);
                try {
                    dispatcher.renderTileEntityAt(
                        tile,
                        CoordinatePacker.unpackX(pos),
                        CoordinatePacker.unpackY(pos),
                        CoordinatePacker.unpackZ(pos),
                        partialTicks);
                } catch (Throwable ignored) {
                    // a broken TESR must not take the whole preview down
                }
                GL11.glPopMatrix();
            }
        }
        ForgeHooksClient.setRenderPass(-1);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        setPassState(0, 1f);
    }

    private void drawHighlight(MovingObjectPosition mop) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glDepthMask(false);
        GL11.glLineWidth(2.5f);
        double e = 0.004;
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
            mop.blockX - e,
            mop.blockY - e,
            mop.blockZ - e,
            mop.blockX + 1 + e,
            mop.blockY + 1 + e,
            mop.blockZ + 1 + e);
        RenderGlobal.drawOutlinedBoundingBox(box, 0xFFFFFF);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Ray tracing is only redone when the mouse, the camera or the visible blocks changed. */
    private MovingObjectPosition pickCached(float time) {
        pickState[0] = Mouse.getX();
        pickState[1] = Mouse.getY();
        pickState[2] = yaw;
        pickState[3] = pitch;
        pickState[4] = distance;
        pickState[5] = centerX;
        pickState[6] = centerY;
        pickState[7] = centerZ;
        pickState[8] = layer;
        pickState[9] = scene.getSelection() == null ? -1 : scene.selectedBlocks.size();
        pickState[10] = time < getTotalDuration() + INTRO_DURATION ? time : -1;
        pickState[11] = sceneW * 10000f + sceneH;
        if (Arrays.equals(pickState, lastPickState)) return lastPick;
        System.arraycopy(pickState, 0, lastPickState, 0, pickState.length);
        return lastPick = pick();
    }

    private MovingObjectPosition lastPick;

    private MovingObjectPosition pick() {
        float winX = Mouse.getX();
        float winY = Mouse.getY();
        if (!GLU.gluUnProject(winX, winY, 0f, modelMatrix, projMatrix, viewport, unprojected)) return null;
        double nx = unprojected.get(0), ny = unprojected.get(1), nz = unprojected.get(2);
        if (!GLU.gluUnProject(winX, winY, 1f, modelMatrix, projMatrix, viewport, unprojected)) return null;
        double dx = unprojected.get(0) - nx, dy = unprojected.get(1) - ny, dz = unprojected.get(2) - nz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return null;
        dx /= len;
        dy /= len;
        dz /= len;

        // The dummy world's ray trace walks at most ~200 blocks, so start right in front of the structure
        double[] range = intersectBounds(nx, ny, nz, dx, dy, dz);
        if (range == null) return null;
        double from = Math.max(0, range[0] - 0.5), to = range[1] + 0.5;
        Vec3 start = Vec3.createVectorHelper(nx + dx * from, ny + dy * from, nz + dz * from);
        Vec3 end = Vec3.createVectorHelper(nx + dx * to, ny + dy * to, nz + dz * to);

        LongSet targets = scene.getSelection() != null ? scene.selectedBlocks : pickTargets;
        MovingObjectPosition result = scene.world.rayTraceBlocksWithTargetMap(start, end, targets);
        if (result == null) return null;
        long pos = CoordinatePacker.pack(result.blockX, result.blockY, result.blockZ);
        if (!scene.unitOfBlock.containsKey(pos)) return null;
        AnimUnit unit = scene.units.get(scene.unitOfBlock.get(pos));
        float time = (System.nanoTime() - animStart) / 1e9f;
        return isUnitVisible(unit) && unitProgress(unit, time) >= 1 ? result : null;
    }

    /** Slab test against the structure's bounding box, returns {tEnter, tExit} or null. */
    private double[] intersectBounds(double ox, double oy, double oz, double dx, double dy, double dz) {
        double[] min = { scene.minX, scene.minY, scene.minZ };
        double[] max = { scene.maxX + 1, scene.maxY + 1, scene.maxZ + 1 };
        double[] o = { ox, oy, oz };
        double[] d = { dx, dy, dz };
        double tMin = 0, tMax = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
                continue;
            }
            double t1 = (min[i] - o[i]) / d[i];
            double t2 = (max[i] - o[i]) / d[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return null;
        }
        return new double[] { tMin, tMax };
    }

    private void playLandingSounds(float time) {
        if (!Config.playSounds || layer >= 0 || lastSoundLayer >= scene.getSizeY() - 1) return;
        float landed = time * Config.animationSpeed;
        // a layer "lands" when its first unit finishes falling
        for (AnimUnit unit : scene.units) {
            int unitLayer = unit.y - scene.minY;
            if (unitLayer <= lastSoundLayer) continue;
            if (unit.startTime * Config.animationSpeed + UNIT_DURATION > landed) break;
            lastSoundLayer = unitLayer;
            long now = System.currentTimeMillis();
            if (now - lastSoundTime < 70) continue;
            lastSoundTime = now;
            float pitch = 0.8f + 0.6f * unitLayer / Math.max(1f, scene.getSizeY() - 1);
            mc.getSoundHandler()
                .playSound(PositionedSoundRecord.func_147674_a(LAND_SOUND, pitch));
        }
    }

    // ------------------------------------------------------------------ input

    private boolean isInScene(int mouseX, int mouseY) {
        return mouseX >= sceneX && mouseX < sceneX + sceneW && mouseY >= sceneY && mouseY < sceneY + sceneH;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            if (closeButton.click(mouseX, mouseY)) return;
            for (FlatButton b : buttons) {
                if (b.click(mouseX, mouseY)) {
                    playClick();
                    return;
                }
            }
        }

        ItemStack ingredient = getHoveredIngredient(mouseX, mouseY);
        if (ingredient != null) {
            if (button == 0) {
                toggleSelection(ingredient);
                playClick();
            } else if (button == 1) {
                openNei(ingredient, true);
            }
            return;
        }

        if (isInScene(mouseX, mouseY) && (button == 0 || button == 1)) {
            dragButton = button;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button >= 0 && button == dragButton) dragButton = -1;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceClick) {
        if (dragButton < 0 || button != dragButton) return;
        int dx = mouseX - lastMouseX;
        int dy = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (dragButton == 0) {
            yaw += dx * 0.9f;
            pitch = Math.max(-89.9f, Math.min(89.9f, pitch + dy * 0.9f));
        } else {
            // pan in the camera plane, one GUI pixel = one pixel of movement at the focus distance
            float perPixel = 2f * distance * (float) Math.tan(Math.toRadians(FOV / 2)) / sceneH;
            double yawR = Math.toRadians(yaw), pitchR = Math.toRadians(pitch);
            float rx = (float) Math.cos(yawR), rz = (float) Math.sin(yawR);
            float ux = (float) (Math.sin(pitchR) * Math.sin(yawR));
            float uy = (float) Math.cos(pitchR);
            float uz = (float) (-Math.sin(pitchR) * Math.cos(yawR));
            centerX += (-rx * dx + ux * dy) * perPixel;
            centerY += (uy * dy) * perPixel;
            centerZ += (-rz * dx + uz * dy) * perPixel;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int steps = wheel > 0 ? 1 : -1;

        if (mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH) {
            ingredientScroll -= steps;
        } else if (isInScene(mouseX, mouseY)) {
            if (isShiftKeyDown()) {
                setLayer(layer + steps);
            } else {
                distance = Math.max(2f, Math.min(1000f, distance * (steps > 0 ? 0.88f : 1f / 0.88f)));
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE && scene.getSelection() != null) {
            scene.setSelection(null, layer);
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()) {
            close();
            return;
        }

        int mouseX = Mouse.getX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getY() * height / mc.displayHeight - 1;
        ItemStack ingredient = getHoveredIngredient(mouseX, mouseY);
        if (ingredient == null && hovered != null) ingredient = getPickedStack(hovered);

        switch (keyCode) {
            case Keyboard.KEY_R -> {
                if (ingredient != null) openNei(ingredient, false);
                else replay();
            }
            case Keyboard.KEY_U -> {
                if (ingredient != null) openNei(ingredient, true);
            }
            case Keyboard.KEY_UP, Keyboard.KEY_PRIOR -> setLayer(layer + 1);
            case Keyboard.KEY_DOWN, Keyboard.KEY_NEXT -> setLayer(layer - 1);
            case Keyboard.KEY_SPACE -> autoRotate = !autoRotate;
            default -> {}
        }
    }

    private void openNei(ItemStack stack, boolean usage) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        if (usage) {
            GuiUsageRecipe.openRecipeGui("item", copy);
        } else {
            GuiCraftingRecipe.openRecipeGui("item", copy);
        }
    }

    private void playClick() {
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0f));
    }

    // ------------------------------------------------------------------ helpers

    /** Filled rectangle with 1px rounded corners and a border. */
    public static void drawRoundedPanel(int x, int y, int w, int h, int fill, int border) {
        Gui.drawRect(x + 1, y, x + w - 1, y + 1, border);
        Gui.drawRect(x + 1, y + h - 1, x + w - 1, y + h, border);
        Gui.drawRect(x, y + 1, x + 1, y + h - 1, border);
        Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, border);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float easeOutCubic(float t) {
        float f = 1 - clamp01(t);
        return 1 - f * f * f;
    }

    private static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f, c3 = c1 + 1;
        float f = t - 1;
        return 1 + c3 * f * f * f + c1 * f * f;
    }
}
