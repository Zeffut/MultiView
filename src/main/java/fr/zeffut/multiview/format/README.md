# Flashback Segment Format — Phase 1 Spec

Reverse-engineered from Arcade (CasualChampionships/Arcade, branch `1.21.11`,
module `arcade-replay`, MIT License) as the exclusive Rosetta Stone. No
Moulberry/Flashback source was consulted.

## 1. High-level structure of a replay archive

A Flashback replay is a `.zip` archive containing:

- `metadata.json` — JSON index of the replay (chunks list, markers, etc.).
- `metadata.json.old` — previous metadata (rotated on each chunk finalize).
- `c0.flashback`, `c1.flashback`, ... `cN.flashback` — stream segments
  (a.k.a. "chunks" in Arcade's terminology; we call them **segments** to avoid
  confusion with Minecraft world chunks).
- `level_chunk_caches/0`, `level_chunk_caches/1`, ... — de-duplicated cache of
  Minecraft `ClientboundLevelChunkWithLightPacket` payloads referenced by the
  segments. Each file holds up to `LEVEL_CHUNK_CACHE_SIZE = 10_000` cached
  chunk packets.
- `resource_packs/` (optional) — downloaded server resource-pack blobs, indexed
  by `index.json`.
- `arcade-replay.meta` (only when written by Arcade, absent in Flashback-native
  replays).

Each segment nominally covers `CHUNK_LENGTH = 5 * 60 * 20 = 6000` ticks (5 min
at 20 TPS), but may be cut earlier when the dimension changes, the recording
resumes, or the recording is closed (see §5).

## 2. Binary layout of a `cN.flashback` segment

All integers are **big-endian**. `VarInt` uses Minecraft's
`FriendlyByteBuf.writeVarInt` / `readVarInt` (7 bits per byte, MSB = continuation).

```
Segment := Header Snapshot ActionStream
```

### 2.1 Header

```
Header := Magic ActionRegistry
Magic  := int32  0xD7 80 E8 84      // = -0x287F177C as signed int32
ActionRegistry := VarInt count
                  count * Identifier
Identifier     := VarInt length
                  <length> bytes    // UTF-8 "namespace:path",
                                    // e.g. "flashback:action/next_tick"
```

Example observed in a real file:

```
d7 80 e8 84   # magic
08            # count = 8
31            # VarInt length = 49 (0x31)
66 6c 61 73 68 62 61 63 6b 3a ...  # "flashback:..."
```

**Key design point:** the numeric ID used later in the stream is the *ordinal
within THIS segment's registry*, not a fixed enum value. A reader must build
the `ordinal -> Identifier` map from each segment's header; different writers
(Flashback proper vs. Arcade vs. future versions) may order the registry
differently.

### 2.2 Snapshot

Immediately after the registry:

```
Snapshot := int32 snapshotSize
            <snapshotSize> bytes of ActionStream
```

The snapshot region is itself a sequence of `Action` entries (same shape as the
main stream) — it carries the world/player state needed to start playback at
this segment without replaying prior segments. Internally it typically contains
`ConfigurationPacket` actions, `GamePacket` actions (login/join/level/registry
data), `CreatePlayer`, `CacheChunk`s, etc., terminated by a `NextTick`.

Note: `snapshotSize` is a fixed **int32**, not VarInt. After reading
`snapshotSize` bytes the reader lands at the start of the live action stream.

### 2.3 Action stream

Follows the snapshot and runs until EOF. Each entry:

```
Action  := VarInt actionId
           int32  payloadSize
           <payloadSize> bytes of payload
```

- `actionId` is the ordinal into the `ActionRegistry` from the header
  (`0`-based, declaration order of that particular segment).
- `payloadSize` is a fixed **int32** count of the payload bytes that follow.
  `payloadSize` does NOT include the `actionId` VarInt nor the `payloadSize`
  field itself — it's purely the payload byte count.
- `payloadSize == 0` is valid (e.g. `NextTick` writes no payload).

The stream ends at EOF; there is no trailing terminator.

## 3. Hypothesis validation

The original working hypothesis was:

> `[VarInt totalLen] [VarInt idLen] [UTF-8 id bytes] [payload bytes]`, repeated.

This is **incorrect**. Corrections:

1. The string id is written **once per segment**, in the header's
   `ActionRegistry`, not before every action. In the stream, actions are
   referenced by a **VarInt ordinal** into that registry.
2. The size prefix is **int32 big-endian**, not VarInt.
3. There is a mandatory **magic number** (`0xD780E884`) at offset 0, and a
   mandatory **snapshot block** (with its own `int32` size prefix) between the
   header and the live action stream.
4. `payloadSize` covers only the payload, not the action header.

The `d7 80 e8 84 08 31 "flashback:"` bytes observed at the start of a real file
are consequently: `magic` (4 B) + `VarInt count=8` (1 B) + `VarInt length=49`
(1 B) + start of the first registered identifier string — fully consistent with
§2.1.

## 4. Action catalogue

Source: `FlashbackAction.kt`. Payload shapes come from
`FlashbackWriter.kt` / `FlashbackChunkedWriter.kt`. Arcade registers 8 actions.
A real Flashback-native file we inspected also advertises 8 actions (matching
`count = 8`), but the *order* is writer-specific — always consult the
segment's registry.

Action ids below are given without the `flashback:` namespace prefix for
brevity. `action/encoded_simple_voice_chat_sound_optional` is registered under
the Arcade namespace (`arcade_replay:...`); the other seven are
`flashback:...`.

| Action id | Payload structure | Codec decision for Phase 1 |
|---|---|---|
| `flashback:action/next_tick` | (empty, `payloadSize == 0`) | `NextTick` — advances replay tick counter by 1. |
| `flashback:action/configuration_packet` | One Minecraft clientbound CONFIGURATION packet, encoded via `ProtocolInfo.codec().encode(buf, packet)` (same on-wire format as Minecraft's `CONFIGURATION` protocol). | `ConfigurationPacket { bytes }` — opaque for Phase 1 (raw bytes). |
| `flashback:action/game_packet` | One Minecraft clientbound PLAY packet, encoded via the `PLAY` `ProtocolInfo` codec. | `GamePacket { bytes }` — opaque for Phase 1 (raw bytes). |
| `flashback:action/create_local_player` | `UUID` (16 B) + `double x` + `double y` + `double z` + `float yaw` + `float pitch` + `float headYaw` + `Vec3 velocity` (3x double) + `GameProfile` (`ByteBufCodecs.GAME_PROFILE`) + `VarInt gamemodeId`. | `CreatePlayer` — fully decoded. |
| `flashback:action/move_entities` | `VarInt dimensionCount`; for each dimension: `ResourceKey<Level>` (= `ResourceLocation` via `writeResourceKey`) + `VarInt entityCount`; for each entity: `EntityMovement` = `VarInt entityId` + `double x` + `double y` + `double z` + `float yaw` + `float pitch` + `float headYaw` + `bool onGround` (exact field order matches `EntityMovement.write` in Arcade). | `MoveEntities` — decode dimension map + entity list. |
| `flashback:action/level_chunk_cached` | `VarInt cacheIndex` — index into the `level_chunk_caches/` files. Resolve by opening `level_chunk_caches/ (cacheIndex / 10000)` and reading the (`cacheIndex % 10000`)-th length-prefixed entry (each entry is `int32 size` + `size` bytes = encoded `ClientboundLevelChunkWithLightPacket`). | `CacheChunkRef { index }` for Phase 1; the cache-file parser is a Phase 1 follow-up. |
| `flashback:action/simple_voice_chat_sound_optional` | Simple Voice Chat payload written via `VoicechatPayload.record(buf)` (non-encoded variant). Unknown internal layout for Phase 1. | `VoiceChat { rawBytes }` — opaque (raw bytes). |
| `arcade_replay:action/encoded_simple_voice_chat_sound_optional` | Arcade-only encoded SVC payload (`VoicechatPayload.ENCODED_FLASHBACK_TYPE`). Will not appear in Flashback-native replays. Unknown for Phase 1. | `EncodedVoiceChat { rawBytes }` — opaque (raw bytes), **and likely absent from real `.flashback` files produced by Flashback itself**. |

Any action whose id in the segment registry does not match one of the above
should be decoded as an opaque `UnknownAction { id, rawBytes }` so the reader
is forward-compatible.

## 5. Tick advancement and segment boundaries

- **Tick advance:** there is a dedicated action `flashback:action/next_tick`
  with an empty payload. Each occurrence advances the replay clock by exactly
  one tick. All other actions are applied "at the current tick" — there is no
  per-action tick field. The writer emits one `NextTick` at the end of every
  server tick (`FlashbackWriter.tick()`).
- **Snapshot terminator:** the last action written into the `Snapshot` region
  (see `endInitialization`) is a `NextTick`, so a snapshot always leaves the
  clock at "tick 1 ready".
- **Segment boundary (`endChunk`):** a new segment begins when any of the
  following occurs:
  1. `ticks - ticksSinceLastSnapshot >= CHUNK_LENGTH (6000)`.
  2. The recorded dimension changes (`previous != null && previous != dimension`).
  3. `resume()` is called after a pause.
  4. `close()` finalizes the recording.

  On segment rollover the writer: finishes the current `cN.flashback`, updates
  `metadata.json` (rotating the previous one to `metadata.json.old`), then
  starts `c(N+1).flashback` with a fresh `Header` + `Snapshot` pair. **Every
  segment is therefore self-contained** (has its own magic, registry, and
  snapshot) — a reader can seek directly to any `cN.flashback` without
  touching earlier segments.

## 6. Relationship to `level_chunk_caches/`

- Full `ClientboundLevelChunkWithLightPacket` payloads are heavy and highly
  repetitive across time, so they are NOT inlined in the stream.
- On first sight of a given chunk packet (keyed by `ChunkPacketIdentity`), the
  writer assigns it a monotonically increasing `cacheIndex`, appends the
  length-prefixed encoded packet to
  `level_chunk_caches/(cacheIndex / LEVEL_CHUNK_CACHE_SIZE)`, and emits a
  `CacheChunk` action carrying only `VarInt cacheIndex` into the segment
  stream.
- On subsequent sights of the same chunk packet identity, only the `CacheChunk`
  action is emitted — the cache file is not re-written.
- Each entry inside a `level_chunk_caches/N` file is `int32 entrySize` +
  `entrySize` bytes of encoded packet (same `writeSizeOf` framing used for
  actions), so the file is a concatenation of length-prefixed blobs that can
  be streamed.

## 7. Reader pseudo-code

```
open segment file
expect int32 magic == 0xD780E884
count = readVarInt
registry = [readUtf() for _ in 0..count)
snapshotSize = readInt32
snapshotEnd = pos + snapshotSize
while pos < snapshotEnd:
    actionId = readVarInt
    payloadSize = readInt32
    handleAction(registry[actionId], read(payloadSize), inSnapshot=true)
while not EOF:
    actionId = readVarInt
    payloadSize = readInt32
    handleAction(registry[actionId], read(payloadSize), inSnapshot=false)
```

`handleAction` dispatches on `registry[actionId]` to the decoders listed in §4.
Cross-segment state (accumulated tick count, resolved chunk cache, etc.) lives
outside this loop and is driven by `NextTick` / `CacheChunk` callbacks.

## 8. Sources

- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/FlashbackIO.kt`
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/ReplayFormat.kt`
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/FlashbackWriter.kt`
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/io/writer/flashback/FlashbackChunkedWriter.kt`
- `arcade-replay/src/main/kotlin/net/casual/arcade/replay/util/flashback/FlashbackAction.kt`

All at CasualChampionships/Arcade @ `1.21.11`, MIT License.

## 9. Validation

The reader was validated against two real `.flashback` POVs recorded on
"HIKA CIVILIZATION" (MC 1.21.11, Flashback 0.39.4) during a multi-player role-play
session:

| Replay | Segments | Total ticks (metadata) | Actions decoded | Unknown ids |
|---|---|---|---|---|
| `2026-02-20T23_25_15` | 33 | 184 238 | 3 697 774 | 0 |
| `Sénat_empirenapo2026-02-20T23_20_16` | 34 | 170 068 | ≈ 3.5 M | 0 |

Key invariants verified:
- `sum(chunks[i].duration) == total_ticks` — no drift over 2h30 of timeline.
- Every `NextTick` ordinal boundary aligns with tick count bumps in the live
  stream; snapshot NextTicks do NOT advance the replay clock (documented
  behaviour: snapshots rebuild state at segment start without re-advancing).
- All 8 action ids registered by Arcade were present; no `Unknown` emitted on
  Flashback-native files.

The test `FlashbackReaderIntegrationTest.openAllAvailableReplays()` iterates
any replay folder under `run/replay/` and enforces `sum == total_ticks` plus
"at least one entry decoded", so future regressions are caught locally on
every `./gradlew test`.
