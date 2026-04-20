package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SegmentWriterTest {

    @Test
    void writesHeaderRegistryEmptySnapshotAndLive() throws Exception {
        SegmentWriter w = new SegmentWriter("test.flashback", List.of(ActionType.NEXT_TICK));
        w.endSnapshot();
        w.writeLiveAction(0, new byte[0]);
        w.writeLiveAction(0, new byte[0]);
        ByteBuf out = w.finish();

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(out));
        assertEquals(1, reader.registry().size());
        assertEquals(ActionType.NEXT_TICK, reader.registry().get(0));
        int snap = 0, live = 0;
        while (reader.hasNext()) {
            if (reader.isPeekInSnapshot()) snap++; else live++;
            reader.next();
        }
        assertEquals(0, snap);
        assertEquals(2, live);
    }

    @Test
    void snapshotActionsGoInSnapshotBlock() throws Exception {
        SegmentWriter w = new SegmentWriter("test.flashback", List.of(ActionType.NEXT_TICK));
        w.writeSnapshotAction(0, new byte[0]);
        w.writeSnapshotAction(0, new byte[0]);
        w.endSnapshot();
        w.writeLiveAction(0, new byte[0]);
        ByteBuf out = w.finish();

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(out));
        int snap = 0, live = 0;
        while (reader.hasNext()) {
            if (reader.isPeekInSnapshot()) snap++; else live++;
            reader.next();
        }
        assertEquals(2, snap);
        assertEquals(1, live);
    }

    @Test
    void preservesPayloadBytes() throws Exception {
        byte[] payload = new byte[] { 0x0A, 0x0B, 0x0C, 0x0D };
        SegmentWriter w = new SegmentWriter("test.flashback",
                List.of(ActionType.NEXT_TICK, ActionType.GAME_PACKET));
        w.endSnapshot();
        w.writeLiveAction(1, payload);
        ByteBuf out = w.finish();

        SegmentReader reader = new SegmentReader("test.flashback", new FlashbackByteBuf(out));
        Action a = reader.next();
        Action.GamePacket gp = assertInstanceOf(Action.GamePacket.class, a);
        assertArrayEquals(payload, gp.bytes());
    }

    @Test
    void writesMagicAtStart() {
        SegmentWriter w = new SegmentWriter("test.flashback", List.of(ActionType.NEXT_TICK));
        w.endSnapshot();
        ByteBuf out = w.finish();
        assertEquals(0xD780E884, out.readInt());
    }
}
