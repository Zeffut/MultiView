package fr.zeffut.multiview.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rapport stats + warnings d'un merge. Sérialisé en merge-report.json via Gson. */
public final class MergeReport {
    public String version = "0.1.0";
    public List<SourceInfo> sources = new ArrayList<>();
    public int mergedTotalTicks;
    public String alignmentStrategy; // "setTimePacket" | "metadataName" | "cliOverride"
    public Stats stats = new Stats();
    public List<String> warnings = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public static final class SourceInfo {
        public String folder;
        public String uuid;
        public int totalTicks;
        public int tickOffset;
    }

    public static final class Stats {
        public int entitiesMergedByUuid;
        public int entitiesMergedByHeuristic;
        public int entitiesAmbiguousMerged;
        public int blocksLwwConflicts;
        public int blocksLwwOverwrites;
        public int globalPacketsDeduped;
        public List<String> egoTracks = new ArrayList<>();
        public int chunkCachesConcatenated;
        public Map<String, Integer> passthroughPackets = new LinkedHashMap<>();
    }

    public void warn(String msg) { warnings.add(msg); }
    public void error(String msg) { errors.add(msg); }
}
