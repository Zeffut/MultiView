package fr.zeffut.multiview.format;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FlashbackMetadata {
    @SerializedName("uuid") private String uuid;
    @SerializedName("name") private String name;
    @SerializedName("version_string") private String versionString;
    @SerializedName("world_name") private String worldName;
    @SerializedName("data_version") private int dataVersion;
    @SerializedName("protocol_version") private int protocolVersion;
    @SerializedName("bobby_world_name") private String bobbyWorldName;
    @SerializedName("total_ticks") private int totalTicks;
    @SerializedName("markers") private Map<String, Marker> rawMarkers = new LinkedHashMap<>();
    @SerializedName("chunks") private Map<String, ChunkInfo> chunks = new LinkedHashMap<>();

    public static FlashbackMetadata fromJson(Reader reader) {
        return new Gson().fromJson(reader, FlashbackMetadata.class);
    }

    public String uuid() { return uuid; }
    public String name() { return name; }
    public String versionString() { return versionString; }
    public String worldName() { return worldName; }
    public int dataVersion() { return dataVersion; }
    public int protocolVersion() { return protocolVersion; }
    public String bobbyWorldName() { return bobbyWorldName; }
    public int totalTicks() { return totalTicks; }

    public Map<Integer, Marker> markers() {
        Map<Integer, Marker> out = new LinkedHashMap<>();
        for (Map.Entry<String, Marker> e : rawMarkers.entrySet()) {
            out.put(Integer.parseInt(e.getKey()), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    public Map<String, ChunkInfo> chunks() {
        return Collections.unmodifiableMap(chunks);
    }

    public static final class Marker {
        @SerializedName("colour") private int colour;
        @SerializedName("description") private String description;

        public int colour() { return colour; }
        public String description() { return description; }
    }

    public static final class ChunkInfo {
        @SerializedName("duration") private int duration;
        @SerializedName("forcePlaySnapshot") private boolean forcePlaySnapshot;

        public int duration() { return duration; }
        public boolean forcePlaySnapshot() { return forcePlaySnapshot; }
    }
}
