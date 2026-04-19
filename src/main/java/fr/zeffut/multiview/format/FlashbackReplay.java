package fr.zeffut.multiview.format;

import java.nio.file.Path;
import java.util.List;

/**
 * Représente un replay Flashback ouvert. Contient la metadata et les chemins
 * des segments dans l'ordre déclaré par metadata.json.
 */
public record FlashbackReplay(Path folder, FlashbackMetadata metadata, List<Path> segmentPaths) {
}
