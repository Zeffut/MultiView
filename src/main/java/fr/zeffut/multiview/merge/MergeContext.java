package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.FlashbackReplay;
import java.util.List;

/**
 * État partagé du pipeline de merge. Instancié une fois par MergeOrchestrator.
 * Les mergers reçoivent ce contexte en constructor et lisent/écrivent dessus.
 */
public final class MergeContext {
    public final List<FlashbackReplay> sources;
    public final int[] tickOffsets;              // tickAbs = tickLocal + tickOffsets[sourceIdx]
    public final int mergedStartTick;            // tick 0 du merged = min(tickOffsets)
    public final int primarySourceIdx;           // index de la source la plus longue
    public final MergeReport report;

    public MergeContext(List<FlashbackReplay> sources, int[] tickOffsets,
                        int mergedStartTick, int primarySourceIdx, MergeReport report) {
        this.sources = sources;
        this.tickOffsets = tickOffsets;
        this.mergedStartTick = mergedStartTick;
        this.primarySourceIdx = primarySourceIdx;
        this.report = report;
    }

    public int toAbsTick(int sourceIdx, int tickLocal) {
        return tickOffsets[sourceIdx] + tickLocal - mergedStartTick;
    }
}
