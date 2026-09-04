package fr.zeffut.multiview.ui;

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
        } catch (ReflectiveOperationException legacyApiAbsent) {
            try {
                Object gui = Minecraft.class.getField("gui").get(minecraft);
                return (Screen) gui.getClass().getMethod("screen").invoke(gui);
            } catch (ReflectiveOperationException modernApiAbsent) {
                throw new IllegalStateException("Unable to access the current Minecraft screen", modernApiAbsent);
            }
        }
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        try {
            Minecraft.class.getMethod("setScreen", Screen.class).invoke(minecraft, screen);
        } catch (ReflectiveOperationException legacyApiAbsent) {
            try {
                Object gui = Minecraft.class.getField("gui").get(minecraft);
                gui.getClass().getMethod("setScreen", Screen.class).invoke(gui, screen);
            } catch (ReflectiveOperationException modernApiAbsent) {
                throw new IllegalStateException("Unable to set the Minecraft screen", modernApiAbsent);
            }
        }
    }
}
