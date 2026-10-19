package multiblockvisualizer.client.scene;

import static blockrenderer6343.client.utils.BRUtil.FAKE_PLAYER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
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
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

import blockrenderer6343.api.utils.CreativeItemSource;
import blockrenderer6343.client.renderer.ImmediateWorldSceneRenderer;
import blockrenderer6343.client.utils.BRUtil;
import blockrenderer6343.client.world.TrackedDummyWorld;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.ITurnable;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.misc.GTStructureChannels;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
    private final ImmediateWorldSceneRenderer ingredientHelper = new ImmediateWorldSceneRenderer(world);

    public final List<AnimUnit> units = new ArrayList<>();
    /** packed block position -> index in {@link #units} */
    public final Long2IntMap unitOfBlock = new Long2IntOpenHashMap();
    public final Long2ObjectMap<TileEntity> renderedTiles = new Long2ObjectOpenHashMap<>();

    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int blockCount;
    public String error;

    private int listBase = -1;
    private int listCount;

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

    public void build(ItemStack controller, int tier) {
        releaseGl();
        world.clear();
        units.clear();
        unitOfBlock.clear();
        renderedTiles.clear();
        error = null;

        FAKE_PLAYER.setWorld(world);
        world.unloadEntities(Collections.singletonList(FAKE_PLAYER));

        ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), tier);
        try {
            place(controller, trigger);
        } catch (Throwable t) {
            MultiblockVisualizer.LOG.error("Failed to build preview of {}", controller.getDisplayName(), t);
            error = t.toString();
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
            if (tile != null) renderedTiles.put(pos, tile);
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
        }
        return last;
    }

    public boolean isCompiled() {
        return listBase >= 0;
    }

    /** Compiles two display lists per unit: solid pass at list, translucent pass at list + 1. */
    public void compile() {
        if (isCompiled() || units.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        listCount = units.size() * 2;
        listBase = GLAllocation.generateDisplayLists(listCount);

        RenderBlocks renderBlocks = new RenderBlocks(world);
        // Neighbours are not there yet while the structure builds, so never cull faces against them
        renderBlocks.renderAllFaces = true;
        Tessellator tessellator = Tessellator.instance;
        int savedAo = mc.gameSettings.ambientOcclusion;
        mc.gameSettings.ambientOcclusion = 0;
        try {
            for (int i = 0; i < units.size(); i++) {
                AnimUnit unit = units.get(i);
                unit.list = listBase + i * 2;
                for (int pass = 0; pass < 2; pass++) {
                    ForgeHooksClient.setRenderPass(pass);
                    GL11.glNewList(unit.list + pass, GL11.GL_COMPILE);
                    tessellator.startDrawingQuads();
                    tessellator.setBrightness(15 << 20 | 15 << 4);
                    try {
                        for (long pos : unit.blocks) {
                            Block block = world.blockMap.get(pos);
                            if (block == null || !block.canRenderInPass(pass)) continue;
                            renderBlocks.setRenderBounds(0, 0, 0, 1, 1, 1);
                            renderBlocks.renderBlockByRenderType(
                                block,
                                CoordinatePacker.unpackX(pos),
                                CoordinatePacker.unpackY(pos),
                                CoordinatePacker.unpackZ(pos));
                        }
                    } catch (Throwable t) {
                        MultiblockVisualizer.LOG.warn("Failed to render a preview block", t);
                    } finally {
                        tessellator.draw();
                        tessellator.setTranslation(0, 0, 0);
                        GL11.glEndList();
                    }
                }
            }
        } finally {
            ForgeHooksClient.setRenderPass(-1);
            mc.gameSettings.ambientOcclusion = savedAo;
        }
    }

    public void releaseGl() {
        if (listBase >= 0) {
            GLAllocation.deleteDisplayLists(listBase);
            listBase = -1;
            for (AnimUnit unit : units) unit.list = -1;
        }
    }

    /**
     * @param layer -1 for the whole structure, otherwise the layer index counted from the bottom
     */
    public List<ItemStack> getIngredients(int layer) {
        if (blockCount == 0) return Collections.emptyList();
        if (layer < 0) {
            ingredientHelper.setRenderAllBlocks();
        } else {
            ingredientHelper.setRenderYLayer(layer);
        }
        List<ItemStack> ingredients = BRUtil.getIngredients(ingredientHelper);
        world.setVisibleYLevel(layer < 0 ? -1 : minY + layer);
        return ingredients;
    }
}
