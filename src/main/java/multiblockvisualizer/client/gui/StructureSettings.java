package multiblockvisualizer.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.apache.commons.lang3.StringUtils;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;

import blockrenderer6343.client.utils.ConstructableData;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * Tier and structure channels (coils, glass, manipulator, shielding...) of a multiblock, the same settings the
 * sliders on BlockRenderer6343's NEI page offer.
 */
public class StructureSettings {

    public static class Setting {

        /** null for the general tier (stack size of the trigger) */
        public final String channel;
        public final String name;
        /** IChannelDefinition on newer StructureLib, null otherwise (see ChannelCompat) */
        public final Object definition;
        public final int min, max;
        public int value;
        /** channels can be left unset, the structure then uses its default */
        public boolean unset;

        Setting(String channel, String name, Object definition, int min, int max, int value, boolean unset) {
            this.channel = channel;
            this.name = name;
            this.definition = definition;
            this.min = min;
            this.max = max;
            this.value = value;
            this.unset = unset;
        }

        public boolean isTier() {
            return channel == null;
        }

        public String getValueText() {
            return unset ? I18n.format("multiblockvisualizer.gui.not_set") : String.valueOf(value);
        }

        /** @return true if the value changed */
        public boolean step(int amount) {
            int oldValue = value;
            boolean oldUnset = unset;
            if (unset) {
                if (amount > 0) {
                    unset = false;
                    value = min;
                }
            } else {
                long next = (long) value + amount;
                if (next < min) {
                    if (isTier()) {
                        value = min;
                    } else {
                        unset = true;
                    }
                } else {
                    value = (int) Math.min(max, next);
                }
            }
            return value != oldValue || unset != oldUnset;
        }

        public boolean reset() {
            if (isTier()) {
                boolean changed = value != 1;
                value = 1;
                return changed;
            }
            boolean changed = !unset;
            unset = true;
            return changed;
        }
    }

    public final List<Setting> settings = new ArrayList<>();

    public StructureSettings(IConstructable constructable) {
        if (constructable == null) return;
        ConstructableData data = ConstructableData.getTierData(constructable);

        int maxTier = Math.max(1, data.getMaxTotalTier());
        if (maxTier > 1) {
            settings
                .add(new Setting(null, I18n.format("multiblockvisualizer.gui.tier_name"), null, 1, maxTier, 1, false));
        }

        for (Object2IntMap.Entry<String> entry : ChannelCompat.getChannelMaxTiers(data)
            .object2IntEntrySet()) {
            String channel = entry.getKey();
            Object definition = ChannelCompat.getDefinition(data, channel);
            int min = Math.max(1, ChannelCompat.getMinimum(definition, 1));
            int max = Math.max(min, entry.getIntValue());
            settings.add(new Setting(channel, StringUtils.capitalize(channel), definition, min, max, min, true));
        }
    }

    public boolean isEmpty() {
        return settings.isEmpty();
    }

    /** Hologram stack the structure is built with. */
    public ItemStack createTrigger() {
        ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), 1);
        for (Setting setting : settings) {
            if (setting.isTier()) {
                trigger.stackSize = setting.value;
            } else if (!setting.unset) {
                int value = ChannelCompat.normalize(setting.definition, setting.value);
                ChannelDataAccessor.setChannelData(trigger, setting.channel, value);
            }
        }
        return trigger;
    }
}
