package fr.zeffut.multiview.merge;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Concatène les level_chunk_caches des N sources dans un dossier de sortie
 * et fournit le remapping (sourceIdx, localCacheIdx) → globalCacheIdx.
 *
 * Les caches sont renumérotés de 0 à N-1 dans l'ordre des sources.
 *
 * @implNote Non thread-safe. Appelé en phase séquentielle pré-streaming.
 */
public final class CacheRemapper {

    private final Map<Long, Integer> mapping = new HashMap<>();
    private int concatCount = 0;

    public void concat(List<Path> sourceCacheDirs, Path destCacheDir) throws IOException {
        Files.createDirectories(destCacheDir);
        int nextGlobal = 0;
        for (int sourceIdx = 0; sourceIdx < sourceCacheDirs.size(); sourceIdx++) {
            Path src = sourceCacheDirs.get(sourceIdx);
            if (!Files.isDirectory(src)) continue;

            TreeMap<Integer, Path> indexed = new TreeMap<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
                for (Path p : ds) {
                    try {
                        indexed.put(Integer.parseInt(p.getFileName().toString()), p);
                    } catch (NumberFormatException ignore) {
                        // fichier non-numérique, on ignore
                    }
                }
            }

            for (Map.Entry<Integer, Path> e : indexed.entrySet()) {
                int localIdx = e.getKey();
                int globalIdx = nextGlobal++;
                Files.copy(e.getValue(), destCacheDir.resolve(Integer.toString(globalIdx)),
                        StandardCopyOption.REPLACE_EXISTING);
                mapping.put(key(sourceIdx, localIdx), globalIdx);
                concatCount++;
            }
        }
    }

    public int remap(int sourceIdx, int localIdx) {
        Integer g = mapping.get(key(sourceIdx, localIdx));
        if (g == null) {
            throw new IllegalStateException(
                    "CacheRemapper: (" + sourceIdx + ", " + localIdx + ") inconnu");
        }
        return g;
    }

    public boolean contains(int sourceIdx, int localIdx) {
        return mapping.containsKey(key(sourceIdx, localIdx));
    }

    public int concatenatedCount() { return concatCount; }

    private static long key(int sourceIdx, int localIdx) {
        return ((long) sourceIdx << 32) | (localIdx & 0xFFFFFFFFL);
    }
}
