package multiblockvisualizer.client.scene;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.StructureEvent;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import blockrenderer6343.integration.nei.StructureHacks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.HatchElementBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import multiblockvisualizer.MultiblockVisualizer;

/**
 * Finds the hatch positions of a GT multiblock and the hint dot each belongs to, the same way BlockRenderer6343's
 * "H" button does: StructureLib reports every visited structure element while the preview is built, and the
 * GT hatch elements carry their hint dot in their HatchElementBuilder.
 */
public class HatchScanner {

    public static final int NO_DOT = 9999;
    private static final Pattern HINT_PATTERN = Pattern.compile("Hint \\d+ dot:");
    private static final List<String> HATCH_ELEMENTS;
    private static final String HATCH_ELEMENT;
    private static final MethodHandle HATCH_BUILDER_GETTER;
    private static final Object2IntMap<IStructureElement<?>> cachedDots = new Object2IntOpenHashMap<>();

    private static HatchScanner active;

    static {
        HatchElementBuilder<?> builder = GTStructureUtility.buildHatchAdder()
            .adder((a, b, c) -> true)
            .casingIndex(1)
            .hint(1);
        String hatchNoPlacement = builder.build()
            .getClass()
            .getName();
        IStructureElement<?> hatchElement = builder.hatchItemFilter((a, b) -> c -> true)
            .build();
        HATCH_ELEMENT = hatchElement.getClass()
            .getName();
        String chained = builder.buildAndChain()
            .getClass()
            .getName();
        HATCH_ELEMENTS = ImmutableList.of(HATCH_ELEMENT, hatchNoPlacement, chained);
        try {
            HATCH_BUILDER_GETTER = MethodHandles.lookup()
                .unreflectGetter(ReflectionHelper.findField(hatchElement.getClass(), "this$0"));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        MinecraftForge.EVENT_BUS.register(new Listener());
    }

    private final Long2ObjectMap<IStructureElement<Object>> elements = new Long2ObjectOpenHashMap<>();
    /** dot -> positions, sorted by dot */
    public final Int2ObjectMap<LongSet> groups = new Int2ObjectRBTreeMap<>();
    public final Long2IntMap dotForPos = new Long2IntOpenHashMap();
    public final Int2ObjectMap<String> hintForDot = new Int2ObjectOpenHashMap<>();

    public HatchScanner() {
        dotForPos.defaultReturnValue(NO_DOT);
    }

    /** Structure element StructureLib visited at this preview position during the build, or null. */
    public IStructureElement<Object> getElement(long pos) {
        return elements.get(pos);
    }

    public void clear() {
        elements.clear();
        groups.clear();
        dotForPos.clear();
        hintForDot.clear();
    }

    /** Collects structure elements for everything that happens inside the runnable. */
    public void record(Runnable build) {
        active = this;
        try {
            build.run();
        } finally {
            active = null;
        }
    }

    /** Groups the recorded positions by hint dot, call after the structure was built. */
    public void resolve(IConstructable controller, ItemStack trigger, LongSet existingBlocks) {
        for (Long2ObjectMap.Entry<IStructureElement<Object>> entry : elements.long2ObjectEntrySet()) {
            long pos = entry.getLongKey();
            if (!existingBlocks.contains(pos)) continue;
            IStructureElement<Object> element = entry.getValue();
            try {
                if (!StructureHacks.anyElementMatches(HATCH_ELEMENTS, controller, element)) continue;
                int dot = getDot(controller, element);
                if (dot == NO_DOT) continue;
                groups.computeIfAbsent(dot, k -> new LongOpenHashSet())
                    .add(pos);
                dotForPos.put(pos, dot);
            } catch (Throwable t) {
                MultiblockVisualizer.LOG.debug("Could not inspect structure element", t);
            }
        }
        findHints(controller, trigger);
    }

    private void findHints(IConstructable controller, ItemStack trigger) {
        try {
            for (String line : controller.getStructureDescription(trigger)) {
                Matcher matcher = HINT_PATTERN.matcher(line);
                if (matcher.find()) {
                    String match = matcher.group();
                    int dot = Integer.parseInt(match.split(" ")[1]);
                    hintForDot.put(
                        dot,
                        line.replace(match, "")
                            .trim());
                }
            }
        } catch (Throwable t) {
            MultiblockVisualizer.LOG.debug("Could not read structure description", t);
        }

        for (Int2ObjectMap.Entry<LongSet> group : groups.int2ObjectEntrySet()) {
            if (hintForDot.containsKey(group.getIntKey())) continue;
            for (long pos : group.getValue()) {
                String hint = getFallbackHint(controller, elements.get(pos));
                if (hint.isEmpty()) continue;
                hintForDot.put(group.getIntKey(), hint);
                break;
            }
        }
    }

    private static int getDot(IConstructable controller, IStructureElement<Object> element) {
        return cachedDots.computeIfAbsent(element, e -> {
            IStructureElement<?> match = StructureHacks.getFirstMatchingElement(HATCH_ELEMENT, controller, element);
            return match == null ? NO_DOT : getHatchBuilder(match).getHint();
        });
    }

    private static HatchElementBuilder<?> getHatchBuilder(IStructureElement<?> element) {
        try {
            return (HatchElementBuilder<?>) HATCH_BUILDER_GETTER.invoke(element);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static String getFallbackHint(IConstructable controller, IStructureElement<Object> element) {
        if (element == null) return "";
        IStructureElement<Object> hatch = StructureHacks.getFirstMatchingElement(HATCH_ELEMENT, controller, element);
        if (hatch == null) return "";

        String hint = getHatchBuilder(hatch).getHatchItemTypeName();
        StringBuilder builder = new StringBuilder(hint);
        if (builder.indexOf("of type") == -1) return hint;

        builder.replace(0, 7, "");
        int index;
        while ((index = builder.indexOf(" or")) != -1) {
            int last = builder.lastIndexOf(" or");
            builder.replace(index, index + 3, last == index ? " and " : ", ");
        }
        return builder.toString()
            .replaceAll(",\\s*", ", ")
            .trim();
    }

    public static class Listener {

        @SubscribeEvent
        @SuppressWarnings("unchecked")
        public void onElementVisited(StructureEvent.StructureElementVisitedEvent event) {
            HatchScanner scanner = active;
            if (scanner == null || !MultiblockVisualizer.MODID.equals(event.getInstrumentIdentifier())) return;
            long pos = CoordinatePacker.pack(event.getX(), event.getY(), event.getZ());
            scanner.elements.put(pos, (IStructureElement<Object>) event.getElement());
        }
    }
}
