package multiblockvisualizer.client.scene;

import static blockrenderer6343.client.utils.BRUtil.FAKE_PLAYER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructableProvider;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

import blockrenderer6343.api.utils.CreativeItemSource;
import blockrenderer6343.client.utils.BRUtil;
import blockrenderer6343.client.utils.EnumColor;
import blockrenderer6343.client.world.TrackedDummyWorld;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.ITurnable;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.misc.GTStructureChannels;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import multiblockvisualizer.MultiblockVisualizer;

/**
 * Builds a GregTech multiblock into a dummy world (the same way BlockRenderer6343 does for its NEI page) and splits
 * it into animation units, each with its own display lists so they can be dropped into place one by one.
 */
public class StructureScene {

    public static final int PLACE_X = 0, PLACE_Y = 64, PLACE_Z = 0;
    private static final int MAX_PLACE_ROUNDS = 2000;
    /** Above this many units the per-frame cost of animating each one separately starts to hurt. */
    private static final int MAX_UNITS = 2500;

    public final TrackedDummyWorld world = new TrackedDummyWorld();

    public final List<AnimUnit> units = new ArrayList<>();
    /** packed block position -> index in {@link #units} */
    public final Long2IntMap unitOfBlock = new Long2IntOpenHashMap();
    /** Only the tiles that actually have a TESR, everything else is in the display lists. */
    public final Long2ObjectMap<TileEntity> renderedTiles = new Long2ObjectOpenHashMap<>();
    /** index of the first unit of every layer in {@link #units}, plus a final entry == units.size() */
    public int[] layerUnitStart = new int[0];
    /** latest start time of any unit in the layer */
    public float[] layerLastStart = new float[0];
    private final Long2ObjectMap<ItemStack> blockStacks = new Long2ObjectOpenHashMap<>();

    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int blockCount;
    public String error;

    public final HatchScanner hatches = new HatchScanner();
    private IConstructable constructable;
    private ItemStack trigger;
    /** what StructureLib accepts at a position, null when unknown */
    private final Long2ObjectMap<Predicate<ItemStack>> acceptedItems = new Long2ObjectOpenHashMap<>();
    private static final int HIGHLIGHT_COLOR = 0xE6B550;

    private int listBase = -1;
    /** whole structure with hidden faces culled, solid at fullList and translucent at fullList + 1 */
    private int fullList = -1;
    /** every layer with all faces, 2 lists per layer */
    private int layerListBase = -1;

    // highlight of one component: selected solid/translucent, ghost solid/translucent, outlines
    private int selectionBase = -1;
    private ItemStack selectionStack;
    /** highlight every hatch position instead of one component */
    private boolean hatchHighlight;
    private int selectionLayer;
    public final LongSet selectedBlocks = new LongOpenHashSet();

    public static class AnimUnit {

        public final int y;
        public final LongArrayList blocks = new LongArrayList();
        public float centerX, centerY, centerZ;
        /** seconds after the start of the animation at which this unit begins to fall in */
        public float startTime;
        public int list = -1;

        AnimUnit(int y) {
            this.y = y;
        }
    }

    public int getSizeX() {
        return maxX - minX + 1;
    }

    public int getSizeY() {
        return maxY - minY + 1;
    }

    public int getSizeZ() {
        return maxZ - minZ + 1;
    }

    public boolean isEmpty() {
        return units.isEmpty();
    }

    /**
     * @param trigger hologram stack carrying the tier (stack size) and channel values, it is not modified
     */
    public void build(ItemStack controller, IConstructable constructable, ItemStack trigger) {
        releaseGl();
        this.constructable = constructable;
        this.trigger = trigger.copy();
        acceptedItems.clear();
        world.clear();
        units.clear();
        unitOfBlock.clear();
        renderedTiles.clear();
        blockStacks.clear();
        selectionStack = null;
        hatchHighlight = false;
        selectedBlocks.clear();
        hatches.clear();
        error = null;

        FAKE_PLAYER.setWorld(world);
        world.unloadEntities(Collections.singletonList(FAKE_PLAYER));

        ItemStack buildTrigger = trigger.copy();
        boolean instrumentEnabledByUs = !StructureLibAPI.isInstrumentEnabled();
        if (instrumentEnabledByUs) StructureLibAPI.enableInstrument(MultiblockVisualizer.MODID);
        try {
            hatches.record(() -> place(controller, buildTrigger));
        } catch (Throwable t) {
            MultiblockVisualizer.LOG.error("Failed to build preview of {}", controller.getDisplayName(), t);
            error = t.toString();
        } finally {
            if (instrumentEnabledByUs) StructureLibAPI.disableInstrument();
        }

        blockCount = world.blockMap.size();
        if (blockCount == 0) return;

        minX = (int) world.getMinPos().x;
        minY = (int) world.getMinPos().y;
        minZ = (int) world.getMinPos().z;
        maxX = (int) world.getMaxPos().x;
        maxY = (int) world.getMaxPos().y;
        maxZ = (int) world.getMaxPos().z;

        splitIntoUnits();

        if (constructable != null) {
            try {
                hatches.resolve(constructable, trigger, world.blockMap.keySet());
            } catch (Throwable t) {
                MultiblockVisualizer.LOG.warn("Failed to find hatch positions", t);
            }
        }
    }

    /** Mirrors GTGuiMultiblockHandler#placeMultiblock from BlockRenderer6343. */
    private void place(ItemStack stackForm, ItemStack trigger) {
        boolean machineUpdatesEnabled = RunnableMachineUpdate.isCurrentThreadEnabled();
        if (machineUpdatesEnabled) RunnableMachineUpdate.setCurrentThreadEnabled(false);
        try {
            ItemStack copy = stackForm.copy();
            copy.getItem()
                .onItemUse(copy, FAKE_PLAYER, world, PLACE_X, PLACE_Y, PLACE_Z, 0, PLACE_X, PLACE_Y, PLACE_Z);

            TileEntity tile = world.getTileEntity(PLACE_X, PLACE_Y, PLACE_Z);
            if (tile instanceof ITurnable turnable) turnable.setFrontFacing(ForgeDirection.SOUTH);
            IMetaTileEntity mte = tile instanceof IGregTechTileEntity gtTile ? gtTile.getMetaTileEntity() : null;

            ChannelDataAccessor.setChannelData(trigger, GTStructureChannels.HATCH.get(), 1);
            if (mte instanceof INEIPreviewModifier modifier) modifier.onPreviewConstruct(trigger);

            IConstructable constructable = null;
            if (mte instanceof ISurvivalConstructable survivalConstructable) {
                int iterations = 0;
                do {
                    survivalConstructable.survivalConstruct(
                        trigger,
                        Integer.MAX_VALUE,
                        ISurvivalBuildEnvironment.create(CreativeItemSource.instance, FAKE_PLAYER));
                    iterations++;
                } while (world.hasChanged() && iterations < MAX_PLACE_ROUNDS);
            } else if (tile instanceof IConstructableProvider provider) {
                constructable = provider.getConstructable();
            } else if (tile instanceof IConstructable tileConstructable) {
                constructable = tileConstructable;
            } else if (mte instanceof IConstructable mteConstructable) {
                constructable = mteConstructable;
            }

            if (constructable != null) constructable.construct(trigger, false);

            if (mte instanceof INEIPreviewModifier modifier) modifier.onPreviewStructureComplete(trigger);
        } finally {
            if (machineUpdatesEnabled) RunnableMachineUpdate.setCurrentThreadEnabled(true);
        }
    }

    private void splitIntoUnits() {
        // Small structures animate block by block, huge ones in g*g columns per layer
        int grid = Math.max(1, (int) Math.ceil(Math.sqrt(blockCount / (double) MAX_UNITS)));

        Long2ObjectMap<AnimUnit> byCell = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Block> entry : world.blockMap.long2ObjectEntrySet()) {
            long pos = entry.getLongKey();
            int x = CoordinatePacker.unpackX(pos);
            int y = CoordinatePacker.unpackY(pos);
            int z = CoordinatePacker.unpackZ(pos);
            long cell = CoordinatePacker.pack((x - minX) / grid, y, (z - minZ) / grid);
            AnimUnit unit = byCell.get(cell);
            if (unit == null) {
                unit = new AnimUnit(y);
                byCell.put(cell, unit);
            }
            unit.blocks.add(pos);

            TileEntity tile = world.tileMap.get(pos);
            if (tile != null && TileEntityRendererDispatcher.instance.hasSpecialRenderer(tile)) {
                renderedTiles.put(pos, tile);
            }
        }

        units.addAll(byCell.values());
        for (AnimUnit unit : units) {
            float sx = 0, sy = 0, sz = 0;
            for (long pos : unit.blocks) {
                sx += CoordinatePacker.unpackX(pos);
                sy += CoordinatePacker.unpackY(pos);
                sz += CoordinatePacker.unpackZ(pos);
            }
            int n = unit.blocks.size();
            unit.centerX = sx / n + 0.5f;
            unit.centerY = sy / n + 0.5f;
            unit.centerZ = sz / n + 0.5f;
        }

        // Bottom-up, every layer sweeping diagonally across the footprint
        units.sort((a, b) -> {
            if (a.y != b.y) return Integer.compare(a.y, b.y);
            return Float.compare(a.centerX + a.centerZ, b.centerX + b.centerZ);
        });
        int layers = getSizeY();
        layerUnitStart = new int[layers + 1];
        layerLastStart = new float[layers];
        int unitIndex = 0;
        for (int l = 0; l < layers; l++) {
            layerUnitStart[l] = unitIndex;
            while (unitIndex < units.size() && units.get(unitIndex).y - minY == l) unitIndex++;
        }
        layerUnitStart[layers] = units.size();

        for (int i = 0; i < units.size(); i++) {
            for (long pos : units.get(i).blocks) {
                unitOfBlock.put(pos, i);
            }
        }
    }

    /**
     * Assigns every unit its start time.
     *
     * @return the time at which the last unit starts falling
     */
    public float layoutTimeline(float unitDuration, float speed) {
        int layers = getSizeY();
        float total = Math.max(1.0f, Math.min(4.5f, 0.8f + 0.14f * layers)) / speed;
        float available = Math.max(0.05f, total - unitDuration);
        float layerStep = available / (layers + 0.25f);
        float sweep = layerStep * 1.25f;
        float diagonalSpan = Math.max(1, getSizeX() + getSizeZ() - 2);

        float last = 0;
        for (AnimUnit unit : units) {
            float diagonal = (unit.centerX - 0.5f - minX + unit.centerZ - 0.5f - minZ) / diagonalSpan;
            unit.startTime = (unit.y - minY) * layerStep + Math.max(0, Math.min(1, diagonal)) * sweep;
            last = Math.max(last, unit.startTime);
            int l = unit.y - minY;
            layerLastStart[l] = Math.max(layerLastStart[l], unit.startTime);
        }
        return last;
    }

    public boolean isCompiled() {
        return listBase >= 0;
    }

    /**
     * Compiles the geometry: two lists per animation unit (only used while the unit is falling), two per layer and
     * two for the whole, finished structure, so a settled scene costs a couple of glCallList per frame.
     */
    public void compile() {
        if (isCompiled() || units.isEmpty()) return;

        listBase = GLAllocation.generateDisplayLists(units.size() * 2);
        for (int i = 0; i < units.size(); i++) {
            AnimUnit unit = units.get(i);
            unit.list = listBase + i * 2;
            // Neighbours are not there yet while the structure builds, so never cull faces against them
            compileBlocks(unit.list, unit.blocks, true);
        }

        int layers = getSizeY();
        layerListBase = GLAllocation.generateDisplayLists(layers * 2);
        for (int l = 0; l < layers; l++) {
            LongArrayList blocks = new LongArrayList();
            for (int i = layerUnitStart[l]; i < layerUnitStart[l + 1]; i++) blocks.addAll(units.get(i).blocks);
            compileBlocks(layerListBase + l * 2, blocks, true);
        }

        fullList = GLAllocation.generateDisplayLists(2);
        compileBlocks(fullList, world.blockMap.keySet(), false);

        // the GL side of an active highlight was dropped together with everything else
        if (isHighlightActive()) applyHighlight(selectionLayer);
    }

    public int getFullList() {
        return fullList;
    }

    public int getLayerList(int layer) {
        return layerListBase + layer * 2;
    }

    /** Compiles the given blocks into list (solid pass) and list + 1 (translucent pass). */
    public void compileBlocks(int list, LongIterable blocks, boolean allFaces) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderBlocks renderBlocks = new RenderBlocks(world);
        renderBlocks.renderAllFaces = allFaces;
        Tessellator tessellator = Tessellator.instance;
        int savedAo = mc.gameSettings.ambientOcclusion;
        mc.gameSettings.ambientOcclusion = 0;
        try {
            for (int pass = 0; pass < 2; pass++) {
                ForgeHooksClient.setRenderPass(pass);
                GL11.glNewList(list + pass, GL11.GL_COMPILE);
                tessellator.startDrawingQuads();
                tessellator.setBrightness(15 << 20 | 15 << 4);
                try {
                    for (long pos : blocks) {
                        Block block = world.blockMap.get(pos);
                        if (block == null || !block.canRenderInPass(pass)) continue;
                        renderBlocks.setRenderBounds(0, 0, 0, 1, 1, 1);
                        try {
                            renderBlocks.renderBlockByRenderType(
                                block,
                                CoordinatePacker.unpackX(pos),
                                CoordinatePacker.unpackY(pos),
                                CoordinatePacker.unpackZ(pos));
                        } catch (Throwable t) {
                            MultiblockVisualizer.LOG.warn("Failed to render a preview block", t);
                        }
                    }
                } finally {
                    tessellator.draw();
                    tessellator.setTranslation(0, 0, 0);
                    GL11.glEndList();
                }
            }
        } finally {
            ForgeHooksClient.setRenderPass(-1);
            mc.gameSettings.ambientOcclusion = savedAo;
        }
    }

    public void releaseGl() {
        if (listBase >= 0) GLAllocation.deleteDisplayLists(listBase);
        if (layerListBase >= 0) GLAllocation.deleteDisplayLists(layerListBase);
        if (fullList >= 0) GLAllocation.deleteDisplayLists(fullList);
        listBase = layerListBase = fullList = -1;
        for (AnimUnit unit : units) unit.list = -1;
        releaseSelection();
    }

    private void releaseSelection() {
        if (selectionBase >= 0) GLAllocation.deleteDisplayLists(selectionBase);
        selectionBase = -1;
    }

    // ------------------------------------------------------------------ component highlight

    /** The item a block counts as in the component list (same rule as BRUtil#getIngredients). */
    public ItemStack getBlockStack(long pos) {
        if (blockStacks.containsKey(pos)) return blockStacks.get(pos);
        ItemStack result = null;
        int x = CoordinatePacker.unpackX(pos), y = CoordinatePacker.unpackY(pos), z = CoordinatePacker.unpackZ(pos);
        Block block = world.getBlock(x, y, z);
        try {
            if (block.quantityDropped(world.rand) != 1) {
                result = new ItemStack(block);
            } else {
                List<ItemStack> drops = block.getDrops(world, x, y, z, world.getBlockMetadata(x, y, z), 0);
                if (!drops.isEmpty()) result = drops.get(0);
            }
        } catch (Throwable ignored) {}
        blockStacks.put(pos, result);
        return result;
    }

    /**
     * Everything the structure accepts at a preview position (the candidates NEI lists on right click), e.g. any
     * hatch or the casing on a hatch position, or every coil tier on a coil position. Null when unknown.
     */
    public Predicate<ItemStack> getAcceptedItems(long pos) {
        if (acceptedItems.containsKey(pos)) return acceptedItems.get(pos);
        Predicate<ItemStack> predicate = null;
        IStructureElement<Object> element = hatches.getElement(pos);
        if (element != null && constructable != null) {
            try {
                IStructureElement.BlocksToPlace blocks = element.getBlocksToPlace(
                    constructable,
                    world,
                    CoordinatePacker.unpackX(pos),
                    CoordinatePacker.unpackY(pos),
                    CoordinatePacker.unpackZ(pos),
                    trigger,
                    BRUtil.AUTO_PLACE_ENVIRONMENT);
                if (blocks != null) predicate = blocks.getPredicate();
            } catch (Throwable t) {
                MultiblockVisualizer.LOG.debug("Could not get accepted blocks", t);
            }
        }
        acceptedItems.put(pos, predicate);
        return predicate;
    }

    public static boolean sameItem(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    public ItemStack getSelection() {
        return selectionStack;
    }

    public boolean isHatchHighlight() {
        return hatchHighlight;
    }

    public boolean isHighlightActive() {
        return selectionStack != null || hatchHighlight;
    }

    public void clearHighlight() {
        selectionStack = null;
        hatchHighlight = false;
        selectedBlocks.clear();
        releaseSelection();
    }

    /**
     * Highlights every block of the given component (null clears it), replacing a hatch highlight.
     *
     * @param layer the layer filter currently shown, -1 for all
     */
    public void setSelection(ItemStack stack, int layer) {
        if (stack == null) {
            if (selectionStack != null) clearHighlight();
            return;
        }
        if (sameItem(stack, selectionStack) && layer == selectionLayer && selectionBase >= 0) return;
        selectionStack = stack.copy();
        hatchHighlight = false;
        applyHighlight(layer);
    }

    /** Highlights all hatch positions, outlined in the colour of their hint dot, replacing a component highlight. */
    public void setHatchHighlight(boolean enabled, int layer) {
        if (!enabled) {
            if (hatchHighlight) clearHighlight();
            return;
        }
        if (hatchHighlight && layer == selectionLayer && selectionBase >= 0) return;
        selectionStack = null;
        hatchHighlight = true;
        applyHighlight(layer);
    }

    /** Re-applies the current highlight for another layer filter. */
    public void setHighlightLayer(int layer) {
        if (isHighlightActive() && layer != selectionLayer) applyHighlight(layer);
    }

    private boolean isHighlighted(long pos) {
        if (hatchHighlight) return hatches.dotForPos.containsKey(pos);
        return sameItem(getBlockStack(pos), selectionStack);
    }

    private int getOutlineColor(long pos) {
        if (!hatchHighlight) return HIGHLIGHT_COLOR;
        int dot = hatches.dotForPos.get(pos);
        return EnumColor.VALUES[dot % EnumColor.VALUES.length].color;
    }

    private void applyHighlight(int layer) {
        selectionLayer = layer;
        releaseSelection();

        LongArrayList selected = new LongArrayList();
        LongArrayList ghosts = new LongArrayList();
        selectedBlocks.clear();
        for (long pos : world.blockMap.keySet()) {
            if (layer >= 0 && CoordinatePacker.unpackY(pos) != minY + layer) continue;
            if (isHighlighted(pos)) {
                selected.add(pos);
                selectedBlocks.add(pos);
            } else {
                ghosts.add(pos);
            }
        }

        selectionBase = GLAllocation.generateDisplayLists(5);
        compileBlocks(selectionBase, selected, true);
        compileBlocks(selectionBase + 2, ghosts, true);

        // outlines of the highlighted blocks, alpha comes from the blend colour so they can pulse
        GL11.glNewList(selectionBase + 4, GL11.GL_COMPILE);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);
        double e = 0.004;
        for (long pos : selected) {
            tessellator.setColorOpaque_I(getOutlineColor(pos));
            double x = CoordinatePacker.unpackX(pos), y = CoordinatePacker.unpackY(pos),
                z = CoordinatePacker.unpackZ(pos);
            addBoxLines(tessellator, x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e);
        }
        tessellator.draw();
        GL11.glEndList();
    }

    /** solid/translucent lists of the highlighted blocks */
    public int getSelectedList() {
        return selectionBase;
    }

    /** solid/translucent lists of every other block */
    public int getGhostList() {
        return selectionBase + 2;
    }

    public int getOutlineList() {
        return selectionBase + 4;
    }

    private static void addBoxLines(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] corners = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 },
            { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[] edges = { 0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7 };
        for (int i : edges) t.addVertex(corners[i][0], corners[i][1], corners[i][2]);
    }

    /**
     * @param layer -1 for the whole structure, otherwise the layer index counted from the bottom
     */
    public List<ItemStack> getIngredients(int layer) {
        if (blockCount == 0) return Collections.emptyList();
        world.setVisibleYLevel(layer < 0 ? -1 : minY + layer);

        List<ItemStack> controllers = new ArrayList<>();
        List<ItemStack> blocks = new ArrayList<>();
        List<ItemStack> hatchStacks = new ArrayList<>();
        for (long pos : world.blockMap.keySet()) {
            if (layer >= 0 && CoordinatePacker.unpackY(pos) != minY + layer) continue;
            ItemStack stack = getBlockStack(pos);
            if (stack == null) continue;

            TileEntity tile = world.tileMap.get(pos);
            IMetaTileEntity mte = tile instanceof IGregTechTileEntity gtTile ? gtTile.getMetaTileEntity() : null;
            boolean isController = mte instanceof MTEMultiBlockBase
                || pos == CoordinatePacker.pack(PLACE_X, PLACE_Y, PLACE_Z);
            List<ItemStack> target = isController ? controllers : mte instanceof MTEHatch ? hatchStacks : blocks;
            addCounted(target, stack);
        }

        // controller first, then blocks and (after a separator, null) hatches, both by amount
        Comparator<ItemStack> byAmount = Comparator.comparingInt((ItemStack stack) -> -stack.stackSize)
            .thenComparing(ItemStack::getDisplayName);
        blocks.sort(byAmount);
        hatchStacks.sort(byAmount);

        List<ItemStack> result = new ArrayList<>(controllers);
        result.addAll(blocks);
        if (!hatchStacks.isEmpty()) {
            if (!result.isEmpty()) result.add(null);
            result.addAll(hatchStacks);
        }
        return result;
    }

    private static void addCounted(List<ItemStack> list, ItemStack stack) {
        for (ItemStack existing : list) {
            if (sameItem(existing, stack)) {
                existing.stackSize++;
                return;
            }
        }
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        list.add(copy);
    }
}
