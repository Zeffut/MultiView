package fr.zeffut.multiview.merge;

/**
 * Résout les packet IDs via les classes Minecraft chargées au runtime.
 * Lève UnsupportedOperationException si MC n'est pas sur le classpath.
 *
 * ATTENTION : l'implémentation concrète dépend de la version MC. En 1.21.11,
 * l'API est PlayStateFactories.S2C (Yarn) ou GameProtocols.CLIENTBOUND_TEMPLATE (Mojmap).
 * À compléter par l'engineer au runtime (Task 14).
 */
final class MinecraftPacketIdProvider implements PacketIdProvider {
    @Override
    public int setTimePacketId() {
        // TASK 14 IMPLEMENTATION : résolution via PlayStateFactories.S2C (Yarn 1.21.11)
        // Exemple attendu :
        //   return PlayStateFactories.S2C
        //     .bind(RegistryByteBuf.makeFactory(...))
        //     .codec()
        //     .packetIds().find(ClientboundSetTimePacket.class);
        // Si l'API diffère, fallback : introspection manuelle du registry.
        //
        // Pour Task 11 on lève UnsupportedOp — sera implémenté en Task 14.
        throw new UnsupportedOperationException(
                "setTimePacketId requires MC runtime — implement in Task 14 via PlayStateFactories.S2C lookup");
    }
}
