package multiblockvisualizer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = MultiblockVisualizer.MODID,
    version = Tags.VERSION,
    name = "MultiblockVisualizer",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
// No "dependencies": required-after entries make FML re-sort the whole load order, and in GTNH that broke the NH
// Core Mod's crafting recipe loader (multiblock controllers lost their recipes). The mods we need are only used
// at runtime and are checked in ClientProxy instead.
public class MultiblockVisualizer {

    public static final String MODID = "multiblockvisualizer";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "multiblockvisualizer.ClientProxy", serverSide = "multiblockvisualizer.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
