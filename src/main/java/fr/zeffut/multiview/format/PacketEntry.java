package fr.zeffut.multiview.format;

/**
 * Action horodatée en tick absolu (depuis le début du replay, tick 0 = première frame).
 *
 * @param tick          tick absolu dans la timeline du replay
 * @param segmentName   nom du segment source (ex. "c3.flashback") — utile pour debug
 * @param inSnapshot    true si l'action vient du bloc Snapshot (état initial du segment),
 *                      false si elle vient du live stream. Phase 3 a besoin de la distinction
 *                      pour ne pas doubler l'état au boundary de segment.
 * @param action        l'action décodée
 */
public record PacketEntry(int tick, String segmentName, boolean inSnapshot, Action action) {
}
