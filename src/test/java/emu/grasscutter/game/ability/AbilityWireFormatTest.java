package emu.grasscutter.game.ability;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.UnknownFieldSet;

import emu.grasscutter.net.proto.AbilityActionCreateGadgetOuterClass.AbilityActionCreateGadget;
import emu.grasscutter.net.proto.AbilityActionGenerateElemBallOuterClass.AbilityActionGenerateElemBall;
import emu.grasscutter.net.proto.EvtBeingHealedNotifyOuterClass.EvtBeingHealedNotify;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

public class AbilityWireFormatTest {
    private static final Vector POS = Vector.newBuilder().setX(1).setY(2).setZ(3).build();
    private static final Vector ROT = Vector.newBuilder().setX(4).setY(5).setZ(6).build();

    @Test
    public void createGadgetUsesClientPositionRotationAndRoomFields() throws Exception {
        var message =
                AbilityActionCreateGadget.newBuilder()
                        .setPos(POS)
                        .setRot(ROT)
                        .setRoomId(42)
                        .build();
        var wire = UnknownFieldSet.parseFrom(message.toByteArray());
        assertEquals(Set.of(7, 9, 14), wire.asMap().keySet());
        assertEquals(List.of(POS.toByteString()), wire.getField(9).getLengthDelimitedList());
        assertEquals(List.of(ROT.toByteString()), wire.getField(7).getLengthDelimitedList());
        assertEquals(List.of(42L), wire.getField(14).getVarintList());

        var incoming =
                UnknownFieldSet.newBuilder()
                        .addField(9, bytes(POS.toByteString()))
                        .addField(7, bytes(ROT.toByteString()))
                        .addField(14, varint(53))
                        .build();
        var decoded = AbilityActionCreateGadget.parseFrom(incoming.toByteArray());
        assertEquals(POS, decoded.getPos());
        assertEquals(ROT, decoded.getRot());
        assertEquals(53, decoded.getRoomId());
    }

    @Test
    public void generateElemBallUsesClientPositionRotationAndRoomFields() throws Exception {
        var message =
                AbilityActionGenerateElemBall.newBuilder()
                        .setPos(POS)
                        .setRot(ROT)
                        .setRoomId(42)
                        .build();
        var wire = UnknownFieldSet.parseFrom(message.toByteArray());
        assertEquals(Set.of(2, 11, 14), wire.asMap().keySet());
        assertEquals(List.of(POS.toByteString()), wire.getField(11).getLengthDelimitedList());
        assertEquals(List.of(ROT.toByteString()), wire.getField(14).getLengthDelimitedList());
        assertEquals(List.of(42L), wire.getField(2).getVarintList());

        var incoming =
                UnknownFieldSet.newBuilder()
                        .addField(11, bytes(POS.toByteString()))
                        .addField(14, bytes(ROT.toByteString()))
                        .addField(2, varint(53))
                        .build();
        var decoded = AbilityActionGenerateElemBall.parseFrom(incoming.toByteArray());
        assertEquals(POS, decoded.getPos());
        assertEquals(ROT, decoded.getRot());
        assertEquals(53, decoded.getRoomId());
    }

    @Test
    public void healAmountAndActualHealAmountUseDistinctClientFields() throws Exception {
        var message =
                EvtBeingHealedNotify.newBuilder()
                        .setSourceId(101)
                        .setTargetId(202)
                        .setHealAmount(400)
                        .setRealHealAmount(125)
                        .build();
        var wire = UnknownFieldSet.parseFrom(message.toByteArray());
        assertEquals(Set.of(3, 5, 8, 14), wire.asMap().keySet());
        assertEquals(List.of(Float.floatToIntBits(400)), wire.getField(8).getFixed32List());
        assertEquals(List.of(Float.floatToIntBits(125)), wire.getField(14).getFixed32List());
        assertEquals(List.of(101L), wire.getField(3).getVarintList());
        assertEquals(List.of(202L), wire.getField(5).getVarintList());

        var incoming =
                UnknownFieldSet.newBuilder()
                        .addField(8, fixed32(60))
                        .addField(14, fixed32(20))
                        .build();
        var decoded = EvtBeingHealedNotify.parseFrom(incoming.toByteArray());
        assertEquals(60f, decoded.getHealAmount());
        assertEquals(20f, decoded.getRealHealAmount());
    }

    @Test
    public void descriptorsMatchTheClientAndRetainOtherHealFields() {
        var gadget = AbilityActionCreateGadget.getDescriptor();
        assertField(gadget, "pos", 9);
        assertField(gadget, "rot", 7);
        assertField(gadget, "room_id", 14);
        var particle = AbilityActionGenerateElemBall.getDescriptor();
        assertField(particle, "pos", 11);
        assertField(particle, "rot", 14);
        assertField(particle, "room_id", 2);
        var heal = EvtBeingHealedNotify.getDescriptor();
        assertField(heal, "heal_amount", 8);
        assertField(heal, "real_heal_amount", 14);
        assertField(heal, "source_id", 3);
        assertField(heal, "target_id", 5);
        assertField(heal, "GOKLCPHOLGL", 4);
        assertField(heal, "CMCCNKMGKHG", 15);
    }

    private static UnknownFieldSet.Field bytes(ByteString value) {
        return UnknownFieldSet.Field.newBuilder().addLengthDelimited(value).build();
    }

    private static UnknownFieldSet.Field varint(long value) {
        return UnknownFieldSet.Field.newBuilder().addVarint(value).build();
    }

    private static UnknownFieldSet.Field fixed32(float value) {
        return UnknownFieldSet.Field.newBuilder().addFixed32(Float.floatToIntBits(value)).build();
    }

    private static void assertField(Descriptor message, String name, int number) {
        assertEquals(number, message.findFieldByName(name).getNumber(), name);
    }
}
