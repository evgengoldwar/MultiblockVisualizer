package multiblockvisualizer;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    private static final String PROJECTION = "projection";

    public static int holdTicks = 14;
    public static float animationSpeed = 1.0f;
    public static boolean autoRotate = true;
    public static boolean viewerAnimation = true;
    public static boolean playSounds = true;
    public static boolean showTooltipHint = true;

    public static boolean projectionAnimation = true;
    public static boolean projectionPanel = true;
    public static boolean projectionHints = true;
    public static boolean projectionMiniBlocks = false;
    public static boolean projectionFarPlace = false;

    private static Configuration configuration;

    public static void synchronizeConfiguration(File configFile) {
        configuration = new Configuration(configFile);

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
        autoRotate = configuration.getBoolean(
            "autoRotate",
            Configuration.CATEGORY_GENERAL,
            autoRotate,
            "Slowly spin the structure by default");
        viewerAnimation = configuration.getBoolean(
            "viewerAnimation",
            Configuration.CATEGORY_GENERAL,
            viewerAnimation,
            "Play the build animation when the visualizer opens");
        playSounds = configuration
            .getBoolean("playSounds", Configuration.CATEGORY_GENERAL, playSounds, "Play a soft sound when layers land");
        showTooltipHint = configuration.getBoolean(
            "showTooltipHint",
            Configuration.CATEGORY_GENERAL,
            showTooltipHint,
            "Show the 'Hold [key] to view structure' line in controller tooltips");

        projectionAnimation = configuration.getBoolean(
            "animation",
            PROJECTION,
            projectionAnimation,
            "Play the build animation when a projection is placed in the world");
        projectionPanel = configuration
            .getBoolean("panel", PROJECTION, projectionPanel, "Show the build progress panel of the projection");
        projectionHints = configuration
            .getBoolean("keyHints", PROJECTION, projectionHints, "Show the key hints on the projection panel");
        projectionMiniBlocks = configuration.getBoolean(
            "miniBlocks",
            PROJECTION,
            projectionMiniBlocks,
            "Draw the projected blocks at half size, so every block of the structure stays visible");

        projectionFarPlace = configuration.getBoolean(
            "farPlace",
            PROJECTION,
            projectionFarPlace,
            "Place the projection at any distance (far blocks, or in the air when nothing is in sight)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /** Writes the options that can be changed in game (the visualizer's settings window). */
    public static void save() {
        if (configuration == null) return;
        configuration.get(Configuration.CATEGORY_GENERAL, "autoRotate", true)
            .set(autoRotate);
        configuration.get(Configuration.CATEGORY_GENERAL, "viewerAnimation", true)
            .set(viewerAnimation);
        configuration.get(PROJECTION, "animation", true)
            .set(projectionAnimation);
        configuration.get(PROJECTION, "panel", true)
            .set(projectionPanel);
        configuration.get(PROJECTION, "keyHints", true)
            .set(projectionHints);
        configuration.get(PROJECTION, "miniBlocks", false)
            .set(projectionMiniBlocks);
        configuration.get(PROJECTION, "farPlace", false)
            .set(projectionFarPlace);
        configuration.save();
    }
}
