package fr.zeffut.multiview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SmokeTest {

    @Test
    void modIdIsCorrect() {
        assertEquals("multiview", MultiViewMod.MOD_ID);
    }

    @Test
    void loggerIsInitialized() {
        assertNotNull(MultiViewMod.LOGGER);
    }
}
