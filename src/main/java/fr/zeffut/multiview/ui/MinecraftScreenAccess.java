package fr.zeffut.multiview.ui;

import java.lang.reflect.InvocationTargetException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Bridges Minecraft's 26.2 GUI ownership move without coupling shared code to
 * either the legacy Minecraft screen field/API or the new Gui owner.
 */
public final class MinecraftScreenAccess {
    private MinecraftScreenAccess() {}

    public static Screen getScreen(Minecraft minecraft) {
        try {
            return (Screen) Minecraft.class.getField("screen").get(minecraft);
        } catch (NoSuchFieldException legacyApiAbsent) {
            try {
                Object gui = Minecraft.class.getField("gui").get(minecraft);
                return (Screen) gui.getClass().getMethod("screen").invoke(gui);
            } catch (ReflectiveOperationException modernApiAbsent) {
                throw new IllegalStateException("Unable to access the current Minecraft screen", modernApiAbsent);
            }
        } catch (IllegalAccessException legacyApiFailed) {
            throw new IllegalStateException("Unable to access the current Minecraft screen", legacyApiFailed);
        }
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        try {
            Minecraft.class.getMethod("setScreen", Screen.class).invoke(minecraft, screen);
        } catch (NoSuchMethodException legacyApiAbsent) {
            try {
                Object gui = Minecraft.class.getField("gui").get(minecraft);
                gui.getClass().getMethod("setScreen", Screen.class).invoke(gui, screen);
            } catch (ReflectiveOperationException modernApiAbsent) {
                throw new IllegalStateException("Unable to set the Minecraft screen", modernApiAbsent);
            }
        } catch (IllegalAccessException | InvocationTargetException legacyApiFailed) {
            throw new IllegalStateException("Unable to set the Minecraft screen", legacyApiFailed);
        }
    }
}
