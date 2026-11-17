package fr.zeffut.multiview.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual modern click handler against real Flashback entries and MC list geometry,
 * without starting a client. Constructors requiring a GPU/client are bypassed for this fixture. */
class MergeUiClickIntegrationTest {
    static Object allocate(Class<?> c) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), c);
    }
    static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    @Test void realRowClicksUseTargetLeftButtonAndToggleBothPaths() throws Exception {
        Class<?> ui = Class.forName("fr.zeffut.multiview.ui.MergeUi");
        Assumptions.assumeTrue("modern".equals(ui.getField("UI_VARIANT").get(null)));
        // Flashback registers key mappings during static initialization; Fabric expects a
        // Minecraft singleton whose options have not been initialized yet at that point.
        Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
        Field singleton = mc.getDeclaredField("instance"); singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, allocate(mc));
        try {
        Class<?> screenClass = Class.forName("com.moulberry.flashback.screen.select_replay.SelectReplayScreen");
        Class<?> listClass = Class.forName("com.moulberry.flashback.screen.select_replay.ReplaySelectionList");
        Class<?> abstractList = Class.forName("net.minecraft.client.gui.components.AbstractSelectionList");
        Class<?> widget = Class.forName("net.minecraft.client.gui.components.AbstractWidget");
        Class<?> entryClass = Class.forName("com.moulberry.flashback.screen.select_replay.ReplaySelectionEntry$ReplayListEntry");
        Class<?> entryBase = Class.forName("net.minecraft.client.gui.components.AbstractSelectionList$Entry");
        Class<?> summaryClass = Class.forName("com.moulberry.flashback.screen.ReplaySummary");
        Object screen = allocate(screenClass), list = allocate(listClass);
        field(screen, screenClass, "list", list);
        field(list, widget, "width", 400); field(list, widget, "height", 200);
        // Before 26.3, row positions are calculated from the list's entry height.
        try { field(list, abstractList, "itemHeight", 36); }
        catch (NoSuchFieldException ignored) { /* 26.3 uses entry rectangles instead. */ }
        widget.getMethod("setY", int.class).invoke(list, 48);
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Object summary = allocate(summaryClass), entry = allocate(entryClass);
            field(summary, summaryClass, "path", Path.of("replay-" + i + ".zip"));
            field(entry, entryClass, "summary", summary);
            entryBase.getMethod("setY", int.class).invoke(entry, 60 + i * 36);
            entryBase.getMethod("setHeight", int.class).invoke(entry, 36);
            entries.add(entry);
        }
        field(list, abstractList, "children", entries);
        Class<?> stateClass = Class.forName("fr.zeffut.multiview.ui.MergeUi$SelectionState");
        Constructor<?> ctor = stateClass.getDeclaredConstructor(); ctor.setAccessible(true);
        Object state = ctor.newInstance();
        Method click = ui.getDeclaredMethod("handleRowClick", stateClass, screenClass, double.class, double.class, int.class);
        click.setAccessible(true);
        int left = Class.forName("com.mojang.blaze3d.platform.InputConstants").getField("MOUSE_BUTTON_LEFT").getInt(null);
        int right = Class.forName("com.mojang.blaze3d.platform.InputConstants").getField("MOUSE_BUTTON_RIGHT").getInt(null);
        Field checked = stateClass.getDeclaredField("checkedPaths"); checked.setAccessible(true);
        Set<?> selected = (Set<?>) checked.get(state);
        // x=100 is inside Flashback's centred 270px row; y=70/106 hit distinct entries.
        assertEquals(true, click.invoke(null, state, screen, 100d, 70d, right));
        assertTrue(selected.isEmpty());
        assertEquals(true, click.invoke(null, state, screen, 10d, 70d, left));
        assertTrue(selected.isEmpty());
        assertEquals(false, click.invoke(null, state, screen, 100d, 70d, left));
        assertEquals(false, click.invoke(null, state, screen, 100d, 106d, left));
        assertEquals(Set.of(Path.of("replay-0.zip"), Path.of("replay-1.zip")), selected);
        } finally {
            singleton.set(null, previous);
        }
    }
}
