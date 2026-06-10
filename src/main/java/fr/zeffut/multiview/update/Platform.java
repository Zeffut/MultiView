package fr.zeffut.multiview.update;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Minimal loader-facts provider for the embedded auto-update module. Mapping-agnostic: references
 * no {@code net.minecraft} class — only the Fabric loader API ({@code net.fabricmc.loader.api}),
 * so the {@code update/} package stays free of any Minecraft mapping dependency.
 *
 * <p>MultiView is Fabric-only, so {@link #loader()} is constant; the other facts come from the
 * running {@link FabricLoader} instance (MC version, this mod's version, the game directory).
 */
public final class Platform {

    private Platform() {}

    public static String loader() {
        return "fabric";
    }

    public static String mcVersion() {
        return modVersion("minecraft");
    }

    public static String modVersion() {
        return modVersion("multiview");
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    private static String modVersion(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
