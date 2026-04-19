package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentReaderTest {

    /** Écrit un segment synthétique minimal dans un ByteBuf. */
    private static ByteBuf buildSegment(String[] registry, int snapshotActions, int liveActions) {
        ByteBuf buf = Unpooled.buffer();
        // Magic
        buf.writeInt(0xD780E884);
        // Registry
        VarInts.writeVarInt(buf, registry.length);
        for (String id : registry) {
            byte[] b = id.getBytes(StandardCharsets.UTF_8);
            VarInts.writeVarInt(buf, b.length);
            buf.writeBytes(b);
        }
        // Snapshot — on précalcule dans un buffer séparé pour obtenir sa taille
        ByteBuf snap = Unpooled.buffer();
        for (int i = 0; i < snapshotActions; i++) {
            VarInts.writeVarInt(snap, 0);   // ordinal 0 = NextTick
            snap.writeInt(0);                // payloadSize 0
        }
        buf.writeInt(snap.readableBytes());
        buf.writeBytes(snap);
        // Live
        for (int i = 0; i < liveActions; i++) {
            VarInts.writeVarInt(buf, 0);
            buf.writeInt(0);
        }
        return buf;
    }

    @Test
    void readsMagicRegistryAndEmptySnapshotEmptyLive() {
        ByteBuf buf = buildSegment(new String[] { ActionType.NEXT_TICK }, 0, 0);
        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));
        assertEquals(1, reader.registry().size());
        assertEquals(ActionType.NEXT_TICK, reader.registry().get(0));
        assertFalse(reader.hasNext());
    }

    @Test
    void rejectsBadMagic() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0xDEADBEEF);
        assertThrows(IllegalArgumentException.class,
                () -> new SegmentReader("bad.flashback", new FlashbackByteBuf(buf)));
    }

    @Test
    void emitsSnapshotThenLiveWithInSnapshotFlag() {
        ByteBuf buf = buildSegment(new String[] { ActionType.NEXT_TICK }, 2, 3);
        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));

        List<Boolean> flags = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        while (reader.hasNext()) {
            boolean inSnap = reader.isPeekInSnapshot();
            Action a = reader.next();
            flags.add(inSnap);
            actions.add(a);
        }
        assertEquals(5, actions.size());
        assertEquals(List.of(true, true, false, false, false), flags);
        actions.forEach(a -> assertInstanceOf(Action.NextTick.class, a));
    }

    @Test
    void unknownOrdinalProducesUnknownAction() {
        // Un id non connu d'ActionType doit produire Action.Unknown
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0xD780E884);
        VarInts.writeVarInt(buf, 1);
        byte[] idBytes = "custom:mod/foo".getBytes(StandardCharsets.UTF_8);
        VarInts.writeVarInt(buf, idBytes.length);
        buf.writeBytes(idBytes);
        buf.writeInt(0); // snapshot size 0
        // 1 action live avec payload { 0x42 }
        VarInts.writeVarInt(buf, 0);
        buf.writeInt(1);
        buf.writeByte(0x42);

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(buf));
        assertTrue(reader.hasNext());
        Action a = reader.next();
        Action.Unknown u = assertInstanceOf(Action.Unknown.class, a);
        assertEquals("custom:mod/foo", u.id());
        assertEquals(1, u.payload().length);
        assertEquals(0x42, u.payload()[0]);
    }
}
