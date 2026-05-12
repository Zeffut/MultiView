package fr.zeffut.multiview.merge;

import net.minecraft.network.protocol.game.GamePacketTypes;

/**
 * Résout les packet IDs via les classes Minecraft chargées au runtime (Yarn 1.21.11).
 *
 * <p>Uses {@link GamePacketDispatch#findId(net.minecraft.network.protocol.PacketType)} which
 * iterates {@code GameProtocols.S2C.buildUnbound()} to resolve numeric IDs without
 * any hardcoded values.
 */
final class MinecraftPacketIdProvider implements PacketIdProvider {
    @Override
    public int setTimePacketId() {
        int id = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_SET_TIME);
        if (id < 0) {
            throw new UnsupportedOperationException(
                    "setTimePacketId: GamePacketTypes.CLIENTBOUND_SET_TIME (WorldTimeUpdateS2CPacket) not found"
                            + " in GameProtocols.S2C — unexpected protocol state");
        }
        return id;
    }
}
