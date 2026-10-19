package multiblockvisualizer.client;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

public final class StructureHelper {

    private StructureHelper() {}

    /**
     * Returns the constructable behind a controller stack, i.e. exactly the machines that BlockRenderer6343's
     * GTNEIMultiblockHandler shows a "Multiblock Structure" page for.
     */
    public static IConstructable getConstructable(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemMachines)) return null;
        IMetaTileEntity mte = ItemMachines.getMetaTileEntity(stack);
        return mte instanceof IConstructable constructable ? constructable : null;
    }

    public static boolean isMultiblock(ItemStack stack) {
        return getConstructable(stack) != null;
    }
}
