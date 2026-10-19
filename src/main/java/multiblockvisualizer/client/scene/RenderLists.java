package multiblockvisualizer.client.scene;

import net.minecraft.client.renderer.GLAllocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.Loader;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import multiblockvisualizer.Config;
import multiblockvisualizer.MultiblockVisualizer;

/**
 * Display lists, or (immediate mode) the same drawing code run again every time the list is "called".
 * <p>
 * Angelica replaces display lists with its own emulation, and tessellated block geometry recorded into them came out
 * as a wireframe mess there. With Angelica (or when forced in the config) nothing is compiled: {@link #record} keeps
 * the drawing code and {@link #call} runs it, the way BlockRenderer6343 renders its NEI preview.
 */
public final class RenderLists {

    private static Boolean immediate;
    private static final Int2ObjectMap<Runnable> recorded = new Int2ObjectOpenHashMap<>();
    private static final Int2IntMap allocated = new Int2IntOpenHashMap();

    private RenderLists() {}

    public static boolean isImmediate() {
        if (immediate == null) {
            immediate = Config.immediateRendering || Loader.isModLoaded("angelica");
            MultiblockVisualizer.LOG.info(
                "Rendering structures {}",
                immediate ? "in immediate mode (Angelica or config)" : "with display lists");
        }
        return immediate;
    }

    /** Reserves {@code count} consecutive list ids. */
    public static int allocate(int count) {
        int base = GLAllocation.generateDisplayLists(count);
        allocated.put(base, count);
        return base;
    }

    public static void free(int base) {
        if (base < 0) return;
        int count = allocated.remove(base);
        for (int i = 0; i < count; i++) recorded.remove(base + i);
        GLAllocation.deleteDisplayLists(base);
    }

    /** Compiles the drawing into the list, or keeps it to be run on every {@link #call}. */
    public static void record(int list, Runnable drawing) {
        if (isImmediate()) {
            recorded.put(list, drawing);
            return;
        }
        GL11.glNewList(list, GL11.GL_COMPILE);
        try {
            drawing.run();
        } finally {
            GL11.glEndList();
        }
    }

    public static void call(int list) {
        if (!isImmediate()) {
            GL11.glCallList(list);
            return;
        }
        Runnable drawing = recorded.get(list);
        if (drawing != null) drawing.run();
    }
}
