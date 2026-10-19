package multiblockvisualizer.client.gui;

import java.lang.reflect.Method;

import com.gtnewhorizon.structurelib.structure.IChannelDefinition;

import blockrenderer6343.client.utils.ConstructableData;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;

/**
 * Channel definitions (minimum value, normalisation) only exist in newer StructureLib/BlockRenderer6343. Everything
 * touching them lives here and is only called through {@link #isAvailable()}-guarded code, so on older versions
 * (e.g. GTNH 2.9.0: BlockRenderer6343 1.4.23, StructureLib 1.4.45) the class simply fails to link and the settings
 * fall back to plain values.
 */
final class ChannelCompat {

    private static Boolean available;

    private ChannelCompat() {}

    static boolean isAvailable() {
        if (available == null) {
            try {
                ConstructableData.class.getMethod("getChannelDefinitions");
                Class.forName("com.gtnewhorizon.structurelib.structure.IChannelDefinition");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * Channel name -> highest tier. The getter was renamed: getChannelData() in old BlockRenderer6343 (1.4.21),
     * getChannelMaxTierMap() later.
     */
    @SuppressWarnings("unchecked")
    static Object2IntMap<String> getChannelMaxTiers(ConstructableData data) {
        for (String name : new String[] { "getChannelMaxTierMap", "getChannelData" }) {
            try {
                Method method = ConstructableData.class.getMethod(name);
                Object result = method.invoke(data);
                if (result instanceof Object2IntMap) return (Object2IntMap<String>) result;
            } catch (Throwable ignored) {}
        }
        return Object2IntMaps.emptyMap();
    }

    /** @return the channel's definition, or null when it has none or the API is missing */
    static Object getDefinition(ConstructableData data, String channel) {
        if (!isAvailable()) return null;
        try {
            return Impl.getDefinition(data, channel);
        } catch (LinkageError e) {
            available = false;
            return null;
        }
    }

    static int getMinimum(Object definition, int fallback) {
        if (definition == null) return fallback;
        try {
            return Impl.getMinimum(definition);
        } catch (LinkageError e) {
            return fallback;
        }
    }

    static int normalize(Object definition, int value) {
        if (definition == null) return value;
        try {
            return Impl.normalize(definition, value);
        } catch (LinkageError e) {
            return value;
        }
    }

    /** Separate class so IChannelDefinition is only resolved when it is actually used. */
    private static final class Impl {

        static Object getDefinition(ConstructableData data, String channel) {
            return data.getChannelDefinitions()
                .get(channel);
        }

        static int getMinimum(Object definition) {
            return ((IChannelDefinition) definition).getMinimumValue();
        }

        static int normalize(Object definition, int value) {
            return ((IChannelDefinition) definition).normalizeValue(value);
        }
    }
}
