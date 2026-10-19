package multiblockvisualizer.client.scene;

import org.lwjgl.opengl.GL11;

import multiblockvisualizer.client.scene.StructureScene.AnimUnit;

/** Build animation shared by the visualizer and the in-world projection: units drop in and pop to full size. */
public final class Anim {

    public static final float UNIT_DURATION = 0.45f;
    public static final float DROP_HEIGHT = 1.6f;

    // LWJGL 2 only exposes these through ARB_imaging, the values are core since OpenGL 1.4
    public static final int GL_CONSTANT_COLOR = 0x8001;
    public static final int GL_ONE_MINUS_CONSTANT_COLOR = 0x8002;
    public static final int GL_CONSTANT_ALPHA = 0x8003;
    public static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;

    private Anim() {}

    public static void applyUnitTransform(AnimUnit unit, float p) {
        float drop = (1 - easeOutCubic(p)) * DROP_HEIGHT;
        float s = 0.55f + 0.45f * easeOutBack(p);
        GL11.glTranslatef(unit.centerX, unit.centerY + drop, unit.centerZ);
        GL11.glScalef(s, s, s);
        GL11.glTranslatef(-unit.centerX, -unit.centerY, -unit.centerZ);
    }

    public static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    public static float easeOutCubic(float t) {
        float f = 1 - clamp01(t);
        return 1 - f * f * f;
    }

    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f, c3 = c1 + 1;
        float f = t - 1;
        return 1 + c3 * f * f * f + c1 * f * f;
    }
}
