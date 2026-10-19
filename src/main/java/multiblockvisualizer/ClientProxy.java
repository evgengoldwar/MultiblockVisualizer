package multiblockvisualizer;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import multiblockvisualizer.client.TooltipHandler;

public class ClientProxy extends CommonProxy {

    public static final KeyBinding OPEN_KEY = new KeyBinding(
        "key.multiblockvisualizer.open",
        Keyboard.KEY_Z,
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

        TooltipHandler handler = new TooltipHandler();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }
}
