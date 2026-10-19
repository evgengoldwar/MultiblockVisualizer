package multiblockvisualizer;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import multiblockvisualizer.client.TooltipHandler;
import multiblockvisualizer.client.projection.ProjectionHandler;

public class ClientProxy extends CommonProxy {

    public static final KeyBinding OPEN_KEY = new KeyBinding(
        "key.multiblockvisualizer.open",
        Keyboard.KEY_Z,
        "key.categories.multiblockvisualizer");
    public static final KeyBinding ROTATE_KEY = new KeyBinding(
        "key.multiblockvisualizer.rotate",
        Keyboard.KEY_R,
        "key.categories.multiblockvisualizer");
    public static final KeyBinding DETACH_KEY = new KeyBinding(
        "key.multiblockvisualizer.detach",
        Keyboard.KEY_K,
        "key.categories.multiblockvisualizer");

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientRegistry.registerKeyBinding(OPEN_KEY);
        ClientRegistry.registerKeyBinding(ROTATE_KEY);
        ClientRegistry.registerKeyBinding(DETACH_KEY);

        TooltipHandler handler = new TooltipHandler();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);

        ProjectionHandler projection = new ProjectionHandler();
        MinecraftForge.EVENT_BUS.register(projection);
        FMLCommonHandler.instance()
            .bus()
            .register(projection);
    }
}
