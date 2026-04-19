package fr.zeffut.multiview.merge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Options de merge parsées depuis /mv merge.
 *
 * @param sources   chemins absolus des dossiers replay sources (≥ 2)
 * @param destination chemin absolu du dossier de sortie
 * @param tickOverrides offset en ticks à ajouter à une source, clé = nom du dossier
 * @param force     si true, écrase une destination existante
 */
public record MergeOptions(
        List<Path> sources,
        Path destination,
        Map<String, Integer> tickOverrides,
        boolean force
) {
}
