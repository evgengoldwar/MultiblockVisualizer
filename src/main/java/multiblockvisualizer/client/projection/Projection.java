package multiblockvisualizer.client.projection;

import static multiblockvisualizer.client.scene.Anim.GL_CONSTANT_ALPHA;
import static multiblockvisualizer.client.scene.Anim.GL_ONE_MINUS_CONSTANT_ALPHA;
import static multiblockvisualizer.client.scene.Anim.UNIT_DURATION;
import static multiblockvisualizer.client.scene.Anim.applyUnitTransform;
import static multiblockvisualizer.client.scene.Anim.clamp01;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import multiblockvisualizer.Config;
import multiblockvisualizer.client.scene.StructureScene;
import multiblockvisualizer.client.scene.StructureScene.AnimUnit;

/**
 * Hologram of a multiblock in the world. The controller sits on the anchor block; missing blocks are shown as a
 * translucent ghost, wrongly placed ones are marked red (orange for the right block in another variant).
 */
public class Projection {

    public static final Projection INSTANCE = new Projection();

    public enum State {
        NONE,
        /** follows the block the player looks at until right click */
        PLACING,
        PLACED
    }

    public enum Status {
        MISSING,
        CORRECT,
        WRONG,
        VARIANT
    }

    private static final int CHECK_INTERVAL = 10;
    private static final ResourceLocation PLACE_SOUND = new ResourceLocation("random.pop");
    private static final ResourceLocation DONE_SOUND = new ResourceLocation("random.levelup");

    private final Minecraft mc = Minecraft.getMinecraft();

    private State state = State.NONE;
    private StructureScene scene;
    private ItemStack controller;
    private int rotation;
    private int anchorX, anchorY, anchorZ;
    private long placedAt;
    private float lastUnitStart;

    /** dummy-world position -> status of the real block it maps to */
    private final Long2IntMap statuses = new Long2IntOpenHashMap();
    private final LongSet missing = new LongOpenHashSet();
    private final LongSet ghostBlocks = new LongOpenHashSet();
    /** blocks other than hatches/controller used by the structure, any of them is fine on a hatch position */
    private final LongSet casingKeys = new LongOpenHashSet();
    private int correct, wrong, variants, total;
    /** -1 shows the whole structure, otherwise only this layer (counted from the bottom) is projected */
    private int layer = -1;
    private int[] layerTotals = new int[0], layerCorrects = new int[0];
    /** whether the shown layer was already complete at the last check, so it only advances when finished now */
    private boolean layerWasDone = true;
    private final List<ExtendedFacing> poses = new ArrayList<>();
    private int poseIndex;
    private int ghostList = -1;
    /** every block, shown while choosing a place */
    private int previewList = -1;
    /** mini-block copies of the scene's animation units (2 lists each), the scene only has full size ones */
    private int miniUnitBase = -1;
    private int ticksToCheck;
    private boolean completed;

    public State getState() {
        return state;
    }

    public ItemStack getController() {
        return controller;
    }

    public int getCorrect() {
        return correct;
    }

    public int getWrong() {
        return wrong;
    }

    public int getVariants() {
        return variants;
    }

    public int getTotal() {
        return total;
    }

    public boolean isCompleted() {
        return completed;
    }

    public int getLayer() {
        return layer;
    }

    public int getLayerCount() {
        return scene == null ? 0 : scene.getSizeY();
    }

    public int getLayerCorrect() {
        return layer >= 0 && layer < layerCorrects.length ? layerCorrects[layer] : 0;
    }

    public int getLayerTotal() {
        return layer >= 0 && layer < layerTotals.length ? layerTotals[layer] : 0;
    }

    /** Shows only one layer so the structure can be built bottom-up, -1 shows everything again. */
    public void setLayer(int newLayer) {
        if (state == State.NONE || scene == null) return;
        newLayer = Math.max(-1, Math.min(getLayerCount() - 1, newLayer));
        if (newLayer == layer) return;
        layer = newLayer;
        // a layer picked by hand stays even if it is already complete
        layerWasDone = true;
        if (state == State.PLACED) check();
    }

    /** Takes over a built scene and lets the player place it. */
    public void start(StructureScene scene, ItemStack controller) {
        remove();
        this.scene = scene;
        this.controller = controller.copy();
        scene.clearHighlight();
        findPoses();
        initFromScene();
        rotation = getDefaultRotation();
        layer = -1;
        state = State.PLACING;
    }

    /** Everything derived from the scene's blocks, again after it was rebuilt in another pose. */
    private void initFromScene() {
        lastUnitStart = scene.layoutTimeline(UNIT_DURATION / Config.animationSpeed, Config.animationSpeed);
        total = scene.world.blockMap.size();

        casingKeys.clear();
        for (long pos : scene.world.blockMap.keySet()) {
            if (isHatchSlot(pos) || scene.world.tileMap.containsKey(pos)) continue;
            casingKeys.add(blockKey(scene.world, pos));
        }

        layerTotals = new int[scene.getSizeY()];
        layerCorrects = new int[scene.getSizeY()];
        if (layer >= scene.getSizeY()) layer = -1;
        statuses.clear();
        missing.clear();
        ghostBlocks.clear();
    }

    /**
     * Orientations of the controller the multiblock allows (GT alignment limits): standing, lying on a side,
     * upside down, mirrored, facing up or down. Turning around the vertical axis is done by {@link #rotate()}.
     */
    private void findPoses() {
        poses.clear();
        poseIndex = 0;
        if (!(scene.getConstructable() instanceof IAlignment alignment)) return;
        ExtendedFacing standard = ExtendedFacing.of(ForgeDirection.SOUTH, Rotation.NORMAL, Flip.NONE);
        for (ExtendedFacing facing : ExtendedFacing.VALUES) {
            ForgeDirection direction = facing.getDirection();
            if (direction != ForgeDirection.SOUTH && direction != ForgeDirection.UP
                && direction != ForgeDirection.DOWN) continue;
            try {
                if (!alignment.isNewExtendedFacingValid(facing)) continue;
            } catch (Throwable t) {
                continue;
            }
            if (facing == standard) poses.add(0, facing);
            else poses.add(facing);
        }
    }

    public int getPoseCount() {
        return poses.size();
    }

    public int getPoseIndex() {
        return poseIndex;
    }

    public String getPoseName() {
        if (poses.isEmpty()) return "";
        try {
            return poses.get(poseIndex)
                .getLocalizedName();
        } catch (Throwable t) {
            return poses.get(poseIndex)
                .name();
        }
    }

    /** Next allowed orientation of the controller; the structure is rebuilt around it. */
    public void cyclePose() {
        if (state == State.NONE || scene == null || poses.size() < 2) return;
        poseIndex = (poseIndex + 1) % poses.size();
        ExtendedFacing facing = poses.get(poseIndex);
        // the standard pose is how the scene was first built, without forcing an alignment
        boolean standard = facing == ExtendedFacing.of(ForgeDirection.SOUTH, Rotation.NORMAL, Flip.NONE);
        scene.rebuild(standard ? null : facing);
        onSettingsChanged();
        initFromScene();
        if (state == State.PLACED) {
            placedAt = System.nanoTime();
            completed = false;
            check();
        }
    }

    public void remove() {
        if (scene != null) scene.releaseGl();
        onSettingsChanged(); // frees every list of our own, the mini units belong to this scene
        scene = null;
        controller = null;
        state = State.NONE;
        statuses.clear();
        missing.clear();
        ghostBlocks.clear();
        completed = false;
    }

    /** Back to following the cursor, so the projection can be put somewhere else. */
    public void detach() {
        if (state != State.PLACED) return;
        state = State.PLACING;
        releaseGhost();
        ghostBlocks.clear();
    }

    public void rotate() {
        if (state == State.NONE) return;
        rotation = (rotation + 1) & 3;
        if (state == State.PLACED) {
            placedAt = System.nanoTime();
            check();
        }
    }

    /** Right click while placing: on top of the looked at block, or (sneaking) instead of it. */
    public boolean tryPlace(boolean replace) {
        if (state != State.PLACING) return false;
        int[] target = getTarget(replace);
        if (target == null) return false;
        anchorX = target[0];
        anchorY = target[1];
        anchorZ = target[2];
        state = State.PLACED;
        placedAt = System.nanoTime();
        completed = false;
        check();
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(PLACE_SOUND, 1.2f));
        return true;
    }

    /** Controller faces the player when placed. */
    private int getDefaultRotation() {
        if (mc.thePlayer == null) return 0;
        int facing = MathHelper.floor_double(mc.thePlayer.rotationYaw * 4 / 360 + 0.5) & 3;
        return (facing + 2) & 3;
    }

    private static final double FAR_PLACE_RANGE = 256;

    private int[] getTarget(boolean replace) {
        MovingObjectPosition mop = mc.objectMouseOver;
        boolean hitBlock = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        if (!hitBlock && Config.projectionFarPlace && mc.renderViewEntity != null) {
            // far placement: look much further than the reach distance
            mop = mc.renderViewEntity.rayTrace(FAR_PLACE_RANGE, 1f);
            hitBlock = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
            if (!hitBlock) return getAirTarget();
        }
        if (!hitBlock) return null;
        if (replace) return new int[] { mop.blockX, mop.blockY, mop.blockZ };
        ForgeDirection side = ForgeDirection.getOrientation(mop.sideHit);
        return new int[] { mop.blockX + side.offsetX, mop.blockY + side.offsetY, mop.blockZ + side.offsetZ };
    }

    /** Nothing in sight (sky, unloaded terrain): float the controller in front of the player, clear of the structure. */
    private int[] getAirTarget() {
        EntityLivingBase view = mc.renderViewEntity;
        double distance = Math.max(6, Math.max(scene.getSizeX(), scene.getSizeZ()) + 2);
        Vec3 look = view.getLook(1f);
        return new int[] { MathHelper.floor_double(view.posX + look.xCoord * distance),
            MathHelper.floor_double(view.posY + view.getEyeHeight() + look.yCoord * distance),
            MathHelper.floor_double(view.posZ + look.zCoord * distance) };
    }

    // ------------------------------------------------------------------ dummy <-> world

    private int worldX(long pos) {
        int dx = CoordinatePacker.unpackX(pos) - StructureScene.PLACE_X;
        int dz = CoordinatePacker.unpackZ(pos) - StructureScene.PLACE_Z;
        return anchorX + switch (rotation) {
            case 1 -> -dz;
            case 2 -> -dx;
            case 3 -> dz;
            default -> dx;
        };
    }

    private int worldY(long pos) {
        return anchorY + CoordinatePacker.unpackY(pos) - StructureScene.PLACE_Y;
    }

    private int worldZ(long pos) {
        int dx = CoordinatePacker.unpackX(pos) - StructureScene.PLACE_X;
        int dz = CoordinatePacker.unpackZ(pos) - StructureScene.PLACE_Z;
        return anchorZ + switch (rotation) {
            case 1 -> dx;
            case 2 -> -dz;
            case 3 -> -dx;
            default -> dz;
        };
    }

    private static long blockKey(World world, long pos) {
        int x = CoordinatePacker.unpackX(pos), y = CoordinatePacker.unpackY(pos), z = CoordinatePacker.unpackZ(pos);
        Block block = world.getBlock(x, y, z);
        return Block.getIdFromBlock(block) * 65536L + block.getDamageValue(world, x, y, z);
    }

    // ------------------------------------------------------------------ checking

    public void onClientTick() {
        if (state != State.PLACED || mc.theWorld == null) return;
        if (--ticksToCheck <= 0) check();
    }

    /** Compares every structure position with the real world. */
    private void check() {
        ticksToCheck = CHECK_INTERVAL;
        World world = mc.theWorld;
        if (world == null || scene == null) return;

        int newCorrect = 0, newWrong = 0, newVariants = 0;
        Arrays.fill(layerTotals, 0);
        Arrays.fill(layerCorrects, 0);
        missing.clear();
        for (long pos : scene.world.blockMap.keySet()) {
            Status status = getStatus(world, pos);
            statuses.put(pos, status.ordinal());
            int posLayer = CoordinatePacker.unpackY(pos) - scene.minY;
            layerTotals[posLayer]++;
            switch (status) {
                case CORRECT -> {
                    newCorrect++;
                    layerCorrects[posLayer]++;
                }
                case WRONG -> newWrong++;
                case VARIANT -> newVariants++;
                case MISSING -> {
                    if (layer < 0 || posLayer == layer) missing.add(pos);
                }
            }
        }
        correct = newCorrect;
        wrong = newWrong;
        variants = newVariants;

        // building layer by layer: move on once the current one is done
        boolean layerDone = layer >= 0 && layerCorrects[layer] == layerTotals[layer];
        if (layerDone && !layerWasDone && layer < getLayerCount() - 1) {
            layer++;
            layerWasDone = false; // also skips layers that are already complete
            mc.getSoundHandler()
                .playSound(PositionedSoundRecord.func_147674_a(PLACE_SOUND, 1.6f));
            check();
            return;
        }
        layerWasDone = layerDone;

        if (!missing.equals(ghostBlocks)) {
            ghostBlocks.clear();
            ghostBlocks.addAll(missing);
            releaseGhost();
        }

        if (!completed && correct == total && total > 0) {
            completed = true;
            mc.getSoundHandler()
                .playSound(PositionedSoundRecord.func_147674_a(DONE_SOUND, 1.0f));
        } else if (correct < total) {
            completed = false;
        }
    }

    /**
     * A position the preview filled with a hatch. Not every multiblock uses the GT hatch element BlockRenderer6343's
     * hint dots are read from (GT++ and others have their own), so a hatch in the preview counts as well.
     */
    private boolean isHatchSlot(long pos) {
        if (scene.hatches.dotForPos.containsKey(pos)) return true;
        return scene.world.tileMap.get(pos) instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() instanceof MTEHatch;
    }

    private Status getStatus(World world, long pos) {
        int x = worldX(pos), y = worldY(pos), z = worldZ(pos);
        if (y < 0 || y >= 256 || !world.blockExists(x, y, z)) return Status.MISSING;
        Block real = world.getBlock(x, y, z);
        if (real.isAir(world, x, y, z) || real.isReplaceable(world, x, y, z)) return Status.MISSING;

        Status exact = getExactStatus(world, pos, real, x, y, z);
        if (exact == Status.CORRECT) return exact;

        // anything else the structure allows here: the casing on a hatch position, any hatch, other coil tiers...
        Predicate<ItemStack> accepted = scene.getAcceptedItems(pos);
        if (accepted != null) {
            ItemStack realStack = getStack(world, real, x, y, z);
            try {
                if (realStack != null && accepted.test(realStack)) return Status.CORRECT;
            } catch (Throwable ignored) {}
        }
        return exact;
    }

    /** Compares with the block the preview has at this position (plus the hatch slot rules). */
    private Status getExactStatus(World world, long pos, Block real, int x, int y, int z) {
        int dx = CoordinatePacker.unpackX(pos), dy = CoordinatePacker.unpackY(pos), dz = CoordinatePacker.unpackZ(pos);
        TileEntity realTile = world.getTileEntity(x, y, z);
        IMetaTileEntity realMte = realTile instanceof IGregTechTileEntity gt ? gt.getMetaTileEntity() : null;

        // hatch positions take any hatch, or any casing of this structure
        if (isHatchSlot(pos)) {
            if (realMte instanceof MTEHatch) return Status.CORRECT;
            long realKey = Block.getIdFromBlock(real) * 65536L + real.getDamageValue(world, x, y, z);
            if (realMte == null && casingKeys.contains(realKey)) return Status.CORRECT;
            return Status.WRONG;
        }

        TileEntity expectedTile = scene.world.getTileEntity(dx, dy, dz);
        if (expectedTile instanceof IGregTechTileEntity expectedGt) {
            return realTile instanceof IGregTechTileEntity realGt
                && realGt.getMetaTileID() == expectedGt.getMetaTileID() ? Status.CORRECT : Status.WRONG;
        }

        Block expected = scene.world.getBlock(dx, dy, dz);
        if (real != expected) return Status.WRONG;
        return expected.getDamageValue(scene.world, dx, dy, dz) == real.getDamageValue(world, x, y, z)
            ? Status.CORRECT
            : Status.VARIANT;
    }

    /** The item form of a placed block, what StructureLib's placement predicates test. */
    private static ItemStack getStack(World world, Block block, int x, int y, int z) {
        try {
            List<ItemStack> drops = block.getDrops(world, x, y, z, world.getBlockMetadata(x, y, z), 0);
            if (drops != null && !drops.isEmpty() && drops.get(0) != null) return drops.get(0);
        } catch (Throwable ignored) {}
        try {
            return new ItemStack(block, 1, block.getDamageValue(world, x, y, z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ rendering

    public void renderWorld(float partialTicks) {
        if (state == State.NONE || scene == null || scene.isEmpty()) return;
        int ax = anchorX, ay = anchorY, az = anchorZ;
        if (state == State.PLACING) {
            int[] target = getTarget(isSneaking());
            if (target == null) return;
            ax = target[0];
            ay = target[1];
            az = target[2];
        }
        if (!scene.isCompiled()) scene.compile();

        EntityLivingBase view = mc.renderViewEntity;
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        float time = (System.nanoTime() - placedAt) / 1e9f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1e9 * 3.0);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            mc.entityRenderer.disableLightmap(partialTicks);

            // dummy coordinates -> world: rotate around the controller block
            GL11.glPushMatrix();
            GL11.glTranslated(ax + 0.5 - camX, ay + 0.5 - camY, az + 0.5 - camZ);
            GL11.glRotatef(-90f * rotation, 0, 1, 0);
            GL11.glTranslatef(-StructureScene.PLACE_X - 0.5f, -StructureScene.PLACE_Y - 0.5f, -StructureScene.PLACE_Z - 0.5f);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            // real blocks win where a ghost face lies exactly on top of them
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(1f, 2f);

            if (state == State.PLACING) {
                if (previewList < 0) {
                    previewList = GLAllocation.generateDisplayLists(2);
                    scene.compileBlocks(
                        previewList,
                        scene.world.blockMap.keySet(),
                        Config.projectionMiniBlocks,
                        getBlockScale());
                }
                drawHologram(previewList, 0.35f + 0.1f * pulse);
            } else if (Config.projectionAnimation && time < lastUnitStart + UNIT_DURATION / Config.animationSpeed) {
                drawBuildAnimation(time);
            } else {
                if (ghostList < 0 && !ghostBlocks.isEmpty()) {
                    ghostList = GLAllocation.generateDisplayLists(2);
                    scene.compileBlocks(ghostList, ghostBlocks, true, getBlockScale());
                }
                if (ghostList >= 0) drawHologram(ghostList, 0.4f + 0.1f * pulse);
            }
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDepthMask(false);
            GL11.glPopMatrix();

            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(2f);
            if (state == State.PLACING) {
                // where the controller goes
                GL11.glTranslated(-camX, -camY, -camZ);
                drawBox(ax, ay, az, 0.90f, 0.71f, 0.31f, 0.15f, 0.9f);
            } else {
                drawMarks(camX, camY, camZ, pulse);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private boolean isSneaking() {
        return mc.thePlayer != null && mc.thePlayer.isSneaking();
    }

    /**
     * Draws the lists as one see-through volume: a depth-only pass first, so only the nearest ghost surface gets
     * colour and the faces behind/between ghost blocks do not shine through each other.
     */
    private static void drawHologram(int list, float alpha) {
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(true);
        drawLists(list);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(false);
        GL14.glBlendColor(1f, 1f, 1f, alpha);
        drawLists(list);
    }

    private static void drawLists(int list) {
        for (int pass = 0; pass < 2; pass++) {
            ForgeHooksClient.setRenderPass(pass);
            GL11.glCallList(list + pass);
        }
        ForgeHooksClient.setRenderPass(-1);
    }

    private void drawBuildAnimation(float time) {
        boolean mini = Config.projectionMiniBlocks;
        if (mini && miniUnitBase < 0) {
            miniUnitBase = GLAllocation.generateDisplayLists(scene.units.size() * 2);
            for (int i = 0; i < scene.units.size(); i++) {
                scene.compileBlocks(miniUnitBase + i * 2, scene.units.get(i).blocks, true, getBlockScale());
            }
        }
        for (int i = 0; i < scene.units.size(); i++) {
            AnimUnit unit = scene.units.get(i);
            if (layer >= 0 && unit.y - scene.minY != layer) continue;
            float p = (time - unit.startTime) * Config.animationSpeed / UNIT_DURATION;
            if (p <= 0) continue;
            GL11.glPushMatrix();
            if (p < 1) applyUnitTransform(unit, p);
            drawHologram(mini ? miniUnitBase + i * 2 : unit.list, 0.45f * clamp01(p * 2.5f));
            GL11.glPopMatrix();
        }
    }

    /** Red boxes on wrong blocks, orange on blocks of the right type but another variant. */
    private void drawMarks(double camX, double camY, double camZ, float pulse) {
        if (wrong == 0 && variants == 0) return;
        GL11.glPushMatrix();
        GL11.glTranslated(-camX, -camY, -camZ);
        GL11.glDisable(GL11.GL_CULL_FACE);
        for (Long2IntMap.Entry entry : statuses.long2IntEntrySet()) {
            int status = entry.getIntValue();
            if (status != Status.WRONG.ordinal() && status != Status.VARIANT.ordinal()) continue;
            long pos = entry.getLongKey();
            boolean isWrong = status == Status.WRONG.ordinal();
            drawBox(
                worldX(pos),
                worldY(pos),
                worldZ(pos),
                1f,
                isWrong ? 0.2f : 0.6f,
                isWrong ? 0.2f : 0.1f,
                0.2f + 0.15f * pulse,
                0.9f);
        }
        GL11.glPopMatrix();
    }

    /** Translucent filled box with an outline, in the current coordinate space. */
    private static void drawBox(double x, double y, double z, float r, float g, float b, float fillAlpha,
        float lineAlpha) {
        double e = 0.006;
        double x0 = x - e, y0 = y - e, z0 = z - e, x1 = x + 1 + e, y1 = y + 1 + e, z1 = z + 1 + e;
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(r, g, b, fillAlpha);
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x0, y1, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y1, z0);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y0, z1);
        t.draw();

        double[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 },
            { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[] edges = { 0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7 };
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_F(r, g, b, lineAlpha);
        for (int i : edges) t.addVertex(c[i][0], c[i][1], c[i][2]);
        t.draw();
    }

    private static float getBlockScale() {
        return Config.projectionMiniBlocks ? 0.5f : 1f;
    }

    /** Display options changed (e.g. mini blocks), rebuild what was compiled with the old ones. */
    public void onSettingsChanged() {
        releaseGhost();
        if (miniUnitBase >= 0) GLAllocation.deleteDisplayLists(miniUnitBase);
        miniUnitBase = -1;
    }

    private void releaseGhost() {
        if (ghostList >= 0) GLAllocation.deleteDisplayLists(ghostList);
        if (previewList >= 0) GLAllocation.deleteDisplayLists(previewList);
        ghostList = previewList = -1;
    }
}
