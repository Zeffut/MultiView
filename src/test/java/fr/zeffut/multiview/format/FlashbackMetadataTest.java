package fr.zeffut.multiview.format;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FlashbackMetadataTest {

    @Test
    void parsesMinimalJson() {
        String json = """
                {
                  "uuid": "2b5459d2-d7ad-43dc-ae36-e6922cf1a45f",
                  "name": "test replay",
                  "version_string": "1.21.11",
                  "world_name": "TestWorld",
                  "data_version": 4671,
                  "protocol_version": 774,
                  "bobby_world_name": "test.server",
                  "total_ticks": 6052,
                  "markers": {},
                  "customNamespacesForRegistries": {},
                  "chunks": {
                    "c0.flashback": { "duration": 6000, "forcePlaySnapshot": false },
                    "c1.flashback": { "duration": 52, "forcePlaySnapshot": false }
                  }
                }
                """;
        FlashbackMetadata meta = FlashbackMetadata.fromJson(new StringReader(json));
        assertEquals("2b5459d2-d7ad-43dc-ae36-e6922cf1a45f", meta.uuid());
        assertEquals("test replay", meta.name());
        assertEquals("1.21.11", meta.versionString());
        assertEquals("TestWorld", meta.worldName());
        assertEquals(4671, meta.dataVersion());
        assertEquals(774, meta.protocolVersion());
        assertEquals(6052, meta.totalTicks());
        assertEquals(2, meta.chunks().size());
        assertEquals(6000, meta.chunks().get("c0.flashback").duration());
        assertEquals(52, meta.chunks().get("c1.flashback").duration());
    }

    @Test
    void parsesMarkers() {
        String json = """
                {
                  "uuid": "u", "name": "n", "version_string": "1.21.11", "world_name": "w",
                  "data_version": 0, "protocol_version": 0, "bobby_world_name": "b",
                  "total_ticks": 100,
                  "markers": {
                    "42": { "colour": 11141290, "description": "Changed Dimension" }
                  },
                  "customNamespacesForRegistries": {},
                  "chunks": {}
                }
                """;
        FlashbackMetadata meta = FlashbackMetadata.fromJson(new StringReader(json));
        Map<Integer, FlashbackMetadata.Marker> markers = meta.markers();
        assertNotNull(markers.get(42));
        assertEquals("Changed Dimension", markers.get(42).description());
        assertEquals(11141290, markers.get(42).colour());
    }

    @Test
    void totalTicksMatchesSumOfChunkDurations() {
        String json = """
                {
                  "uuid": "u", "name": "n", "version_string": "1.21.11", "world_name": "w",
                  "data_version": 0, "protocol_version": 0, "bobby_world_name": "b",
                  "total_ticks": 6052, "markers": {}, "customNamespacesForRegistries": {},
                  "chunks": {
                    "c0.flashback": { "duration": 6000, "forcePlaySnapshot": false },
                    "c1.flashback": { "duration": 52, "forcePlaySnapshot": false }
                  }
                }
                """;
        FlashbackMetadata meta = FlashbackMetadata.fromJson(new StringReader(json));
        int sum = meta.chunks().values().stream().mapToInt(FlashbackMetadata.ChunkInfo::duration).sum();
        assertEquals(meta.totalTicks(), sum);
    }

    @Test
    void roundTripPreservesAllFields() {
        String json = """
                {
                  "uuid": "2b5459d2-d7ad-43dc-ae36-e6922cf1a45f",
                  "name": "test replay",
                  "version_string": "1.21.11",
                  "world_name": "TestWorld",
                  "data_version": 4671,
                  "protocol_version": 774,
                  "bobby_world_name": "test.server",
                  "total_ticks": 6052,
                  "markers": {
                    "42": { "colour": 11141290, "description": "Changed Dimension" }
                  },
                  "customNamespacesForRegistries": {},
                  "chunks": {
                    "c0.flashback": { "duration": 6000, "forcePlaySnapshot": false },
                    "c1.flashback": { "duration": 52, "forcePlaySnapshot": false }
                  }
                }
                """;
        FlashbackMetadata original = FlashbackMetadata.fromJson(new java.io.StringReader(json));

        java.io.StringWriter out = new java.io.StringWriter();
        original.toJson(out);
        String reserialized = out.toString();

        FlashbackMetadata parsedAgain = FlashbackMetadata.fromJson(new java.io.StringReader(reserialized));

        assertEquals(original.uuid(), parsedAgain.uuid());
        assertEquals(original.name(), parsedAgain.name());
        assertEquals(original.versionString(), parsedAgain.versionString());
        assertEquals(original.worldName(), parsedAgain.worldName());
        assertEquals(original.dataVersion(), parsedAgain.dataVersion());
        assertEquals(original.protocolVersion(), parsedAgain.protocolVersion());
        assertEquals(original.totalTicks(), parsedAgain.totalTicks());
        assertEquals(original.markers().size(), parsedAgain.markers().size());
        assertEquals(original.markers().get(42).description(), parsedAgain.markers().get(42).description());
        assertEquals(original.chunks().size(), parsedAgain.chunks().size());
        assertEquals(original.chunks().get("c0.flashback").duration(), parsedAgain.chunks().get("c0.flashback").duration());
    }
}
