package multiblockvisualizer;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static int holdTicks = 14;
    public static float animationSpeed = 1.0f;
    public static boolean autoRotate = true;
    public static boolean playSounds = true;
    public static boolean showTooltipHint = true;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        holdTicks = configuration.getInt(
            "holdTicks",
            Configuration.CATEGORY_GENERAL,
            holdTicks,
            1,
            100,
            "How many ticks the key has to be held over a controller to open the visualizer");
        animationSpeed = configuration.getFloat(
            "animationSpeed",
            Configuration.CATEGORY_GENERAL,
            animationSpeed,
            0.1f,
            10f,
            "Speed multiplier of the build animation (2 = twice as fast)");
        autoRotate = configuration
            .getBoolean("autoRotate", Configuration.CATEGORY_GENERAL, autoRotate, "Slowly spin the structure by default");
        playSounds = configuration
            .getBoolean("playSounds", Configuration.CATEGORY_GENERAL, playSounds, "Play a soft sound when layers land");
        showTooltipHint = configuration.getBoolean(
            "showTooltipHint",
            Configuration.CATEGORY_GENERAL,
            showTooltipHint,
            "Show the 'Hold [key] to view structure' line in controller tooltips");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
