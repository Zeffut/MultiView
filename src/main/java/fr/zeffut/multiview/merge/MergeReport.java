package fr.zeffut.multiview.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merge stats + warnings report. Serialised to merge-report.json via Gson. */
public final class MergeReport {
    /** Schema version of this report file format (not the mod version). */
    public String version = "0.2.0";
    /** ISO-8601 UTC timestamp of when the merge finished. */
    public String mergedAt;
    /** MultiView mod version that produced this merge. */
    public String multiviewVersion;
    public List<SourceInfo> sources = new ArrayList<>();
    public int mergedTotalTicks;
    public String alignmentStrategy; // "setTimePacket" | "metadataName" | "cliOverride"
    public Stats stats = new Stats();
    public List<String> warnings = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public static final class SourceInfo {
        public String folder;
        /** Absolute path of the source replay folder/zip on the producing machine. */
        public String absolutePath;
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
        public int outOfOrderPackets;
    }

    public void warn(String msg) { warnings.add(msg); }
    public void error(String msg) { errors.add(msg); }
}
