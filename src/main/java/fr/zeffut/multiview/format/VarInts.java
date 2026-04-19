package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;

public final class VarInts {
    private static final int MAX_VARINT_BYTES = 5;
    private static final int SEGMENT_BITS = 0x7F;
    private static final int CONTINUE_BIT = 0x80;

    private VarInts() {}

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte current;
        int bytesRead = 0;
        do {
            if (bytesRead >= MAX_VARINT_BYTES) {
                throw new IllegalArgumentException("VarInt too long (> " + MAX_VARINT_BYTES + " bytes)");
            }
            current = buf.readByte();
            value |= (current & SEGMENT_BITS) << position;
            position += 7;
            bytesRead++;
        } while ((current & CONTINUE_BIT) != 0);
        return value;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~SEGMENT_BITS) != 0) {
            buf.writeByte((value & SEGMENT_BITS) | CONTINUE_BIT);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
