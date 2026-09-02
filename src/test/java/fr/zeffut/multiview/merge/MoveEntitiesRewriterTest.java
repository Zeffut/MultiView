package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveEntitiesRewriterTest {

    @Test
    void rewriteMoveEntitiesPayload_roundTripsAllFieldsWithRemappedEntityIds() {
        byte[] payload = encode(
                new Dimension("minecraft:overworld", List.of(
                        new Movement(42, 1.25, 64.0, -3.5, 90.0f, -10.0f, 45.0f, true),
                        new Movement(7, 2.0, 70.0, 8.0, 0.0f, 15.0f, 180.0f, false))),
                new Dimension("minecraft:the_nether", List.of(
                        new Movement(200, -1.0, 80.5, 0.0, 12.0f, 30.0f, 60.0f, true))));
        Map<Integer, Integer> remappedIds = Map.of(42, 100_000_000, 7, 100_000_001, 200, 100_000_002);
        IntUnaryOperator remapper = remappedIds::get;

        byte[] rewritten = EntityPacketRewriter.rewriteMoveEntitiesPayload(payload, remapper);

        assertEquals(List.of(
                        new Dimension("minecraft:overworld", List.of(
                                new Movement(100_000_000, 1.25, 64.0, -3.5, 90.0f, -10.0f, 45.0f, true),
                                new Movement(100_000_001, 2.0, 70.0, 8.0, 0.0f, 15.0f, 180.0f, false))),
                        new Dimension("minecraft:the_nether", List.of(
                                new Movement(100_000_002, -1.0, 80.5, 0.0, 12.0f, 30.0f, 60.0f, true)))),
                decode(rewritten));
    }

    private static byte[] encode(Dimension... dimensions) {
        ByteBuf out = Unpooled.buffer();
        VarInts.writeVarInt(out, dimensions.length);
        for (Dimension dimension : dimensions) {
            byte[] key = dimension.key().getBytes(StandardCharsets.UTF_8);
            VarInts.writeVarInt(out, key.length);
            out.writeBytes(key);
            VarInts.writeVarInt(out, dimension.movements().size());
            for (Movement movement : dimension.movements()) {
                writeMovement(out, movement);
            }
        }
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    private static List<Dimension> decode(byte[] payload) {
        ByteBuf in = Unpooled.wrappedBuffer(payload);
        List<Dimension> dimensions = new ArrayList<>();
        int dimensionCount = VarInts.readVarInt(in);
        for (int dimension = 0; dimension < dimensionCount; dimension++) {
            int keyLength = VarInts.readVarInt(in);
            byte[] key = new byte[keyLength];
            in.readBytes(key);
            List<Movement> movements = new ArrayList<>();
            int entityCount = VarInts.readVarInt(in);
            for (int entity = 0; entity < entityCount; entity++) {
                movements.add(readMovement(in));
            }
            dimensions.add(new Dimension(new String(key, StandardCharsets.UTF_8), movements));
        }
        assertEquals(0, in.readableBytes(), "round-tripped payload must not contain trailing bytes");
        return dimensions;
    }

    private static void writeMovement(ByteBuf out, Movement movement) {
        VarInts.writeVarInt(out, movement.entityId());
        out.writeDouble(movement.x());
        out.writeDouble(movement.y());
        out.writeDouble(movement.z());
        out.writeFloat(movement.yaw());
        out.writeFloat(movement.pitch());
        out.writeFloat(movement.headYaw());
        out.writeBoolean(movement.onGround());
    }

    private static Movement readMovement(ByteBuf in) {
        return new Movement(VarInts.readVarInt(in), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean());
    }

    private record Dimension(String key, List<Movement> movements) {}

    private record Movement(int entityId, double x, double y, double z,
                            float yaw, float pitch, float headYaw, boolean onGround) {}
}
