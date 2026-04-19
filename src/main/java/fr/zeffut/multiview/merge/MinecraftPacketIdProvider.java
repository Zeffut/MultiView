package fr.zeffut.multiview.merge;

import net.minecraft.network.packet.PlayPackets;

/**
 * Résout les packet IDs via les classes Minecraft chargées au runtime (Yarn 1.21.11).
 *
 * <p>Uses {@link GamePacketDispatch#findId(net.minecraft.network.packet.PacketType)} which
 * iterates {@code PlayStateFactories.S2C.buildUnbound()} to resolve numeric IDs without
 * any hardcoded values.
 */
final class MinecraftPacketIdProvider implements PacketIdProvider {
    @Override
    public int setTimePacketId() {
        int id = GamePacketDispatch.findId(PlayPackets.SET_TIME);
        if (id < 0) {
            throw new UnsupportedOperationException(
                    "setTimePacketId: PlayPackets.SET_TIME (WorldTimeUpdateS2CPacket) not found"
                            + " in PlayStateFactories.S2C — unexpected protocol state");
        }
        return id;
    }
}
