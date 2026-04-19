package fr.zeffut.multiview.format;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public final class FlashbackByteBuf {
    private final ByteBuf underlying;

    public FlashbackByteBuf(ByteBuf underlying) {
        this.underlying = underlying;
    }

    public int readVarInt() {
        return VarInts.readVarInt(underlying);
    }

    /** int32 big-endian (Netty default). Utilisé pour le magic, snapshotSize et payloadSize. */
    public int readInt() {
        return underlying.readInt();
    }

    public String readString() {
        int length = readVarInt();
        byte[] bytes = new byte[length];
        underlying.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String readNamespacedId() {
        return readString();
    }

    public byte[] readBytes(int length) {
        byte[] out = new byte[length];
        underlying.readBytes(out);
        return out;
    }

    public int readerIndex() {
        return underlying.readerIndex();
    }

    public boolean isReadable() {
        return underlying.isReadable();
    }

    public ByteBuf raw() {
        return underlying;
    }
}
