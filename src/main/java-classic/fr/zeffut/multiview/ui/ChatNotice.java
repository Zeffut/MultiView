package fr.zeffut.multiview.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Per-version chat helper (MC 1.21.x / classic). Shows a client-side chat line.
 *
 * <p>The chat API diverges across MC versions ({@code ChatComponent#addMessage(Component)} here vs
 * {@code addClientSystemMessage(Component)} in 26.1+), so this lives in the version-specific UI
 * source root and is invoked reflectively by the UI-agnostic shared code.
 */
public final class ChatNotice {
    private ChatNotice() {}

    /** Append {@code msg} to the chat HUD. No-op if the HUD isn't ready. */
    public static void show(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.gui.getChat().addMessage(msg);
        }
    }
}
