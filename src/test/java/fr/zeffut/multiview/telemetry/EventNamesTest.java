package fr.zeffut.multiview.telemetry;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventNamesTest {
    @Test
    void allEventConstantsAreUniqueAndNonBlank() throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field f : EventNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class
                    && f.getName().startsWith("EVT_")) {
                String v = (String) f.get(null);
                assertFalse(v == null || v.isBlank(), "blank event constant: " + f.getName());
                values.add(v);
            }
        }
        Set<String> unique = new HashSet<>(values);
        assertEquals(values.size(), unique.size(), "duplicate event names: " + values);
        assertFalse(values.isEmpty(), "no event constants found");
    }
}
