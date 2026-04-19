package fr.zeffut.multiview.merge;

/** Fournit les packet IDs du protocole courant. Permet l'injection en test. */
public interface PacketIdProvider {
    int setTimePacketId();

    /** Implémentation par défaut, utilisée à runtime (exige MC chargé). */
    static PacketIdProvider minecraftRuntime() {
        return new MinecraftPacketIdProvider();
    }
}
