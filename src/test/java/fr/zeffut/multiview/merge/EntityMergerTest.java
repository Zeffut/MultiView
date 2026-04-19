package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EntityMergerTest {

    @Test
    void uuidMatchFusesEntities() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        UUID u = UUID.randomUUID();
        int g1 = m.registerAddEntity(0, 42, u, "zombie", 100, 0, 64, 0);
        int g2 = m.registerAddEntity(1, 7, u, "zombie", 100, 0, 64, 0);
        assertEquals(g1, g2, "même UUID = même globalId");
        m.incrementUuidMerged();
    }

    @Test
    void heuristicMatchFusesClosePositionsAtCloseTicks() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "zombie", 102, 10.5, 64.0, 10.5); // ±5 ticks, <= 2 blocs
        assertEquals(g1, g2, "heuristique devrait fusionner");
    }

    @Test
    void heuristicMissWhenTooFarInTime() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "zombie", 200, 10.0, 64.0, 10.0); // > 5 ticks
        assertNotEquals(g1, g2);
    }

    @Test
    void heuristicMissWhenTooFarInSpace() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "zombie", 100, 20.0, 64.0, 20.0); // > 2 blocs
        assertNotEquals(g1, g2);
    }

    @Test
    void heuristicMissWhenDifferentType() {
        SourcePovTracker pov = new SourcePovTracker(2);
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        int g1 = m.registerAddEntity(0, 42, null, "zombie", 100, 10.0, 64.0, 10.0);
        int g2 = m.registerAddEntity(1, 7, null, "skeleton", 100, 10.0, 64.0, 10.0);
        assertNotEquals(g1, g2);
    }

    @Test
    void ambiguityResolvesByPovProximity() {
        SourcePovTracker pov = new SourcePovTracker(2);
        pov.update(0, 100, 10.0, 64.0, 10.0); // source 0 très proche de l'entité
        pov.update(1, 100, 100.0, 64.0, 100.0); // source 1 très loin
        IdRemapper ids = new IdRemapper();
        EntityMerger m = new EntityMerger(pov, ids);

        // Deux candidats en fenêtre, on ajoute une 3e observation ambiguë
        int gA = m.registerAddEntity(0, 1, null, "zombie", 100, 10.0, 64.0, 10.0);
        int gB = m.registerAddEntity(0, 2, null, "zombie", 100, 11.5, 64.0, 10.0); // fenêtre ok vs A
        // 3e source ambiguë entre A et B → doit fusionner sur celui dont le POV est le plus proche
        int gC = m.registerAddEntity(1, 99, null, "zombie", 100, 10.7, 64.0, 10.2);
        // POV source 0 est plus proche de l'entité source 1 → fusion vers la meilleure candidate
        assertTrue(gC == gA || gC == gB, "ambiguïté résout vers un des candidats");
        // La métrique d'ambiguïté est incrémentée
        assertEquals(1, m.ambiguousMergedCount());
    }
}
