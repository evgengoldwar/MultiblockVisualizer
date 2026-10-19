package multiblockvisualizer;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    // The visualizer is purely client-side, the server proxy does nothing.
    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {}
}
