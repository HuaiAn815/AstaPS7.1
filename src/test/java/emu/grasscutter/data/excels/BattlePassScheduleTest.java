package emu.grasscutter.data.excels;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.battlepass.SafeBattlePassSchedule;
import emu.grasscutter.net.packet.Opcodes;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.SetBattlePassViewedReqOuterClass.SetBattlePassViewedReq;
import emu.grasscutter.server.packet.recv.HandlerSetBattlePassViewedReq;
import emu.grasscutter.utils.JsonUtils;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import org.junit.jupiter.api.Test;

public class BattlePassScheduleTest {
    @Test
    public void viewedRequestReadsAndWritesTheClientScheduleField() throws Exception {
        var wire = new byte[] {0x08, 0x7b};
        assertArrayEquals(
                wire, SetBattlePassViewedReq.newBuilder().setScheduleId(123).build().toByteArray());
        assertEquals(123, SetBattlePassViewedReq.parseFrom(wire).getScheduleId());
        assertEquals(
                1,
                SetBattlePassViewedReq.getDescriptor().findFieldByName("schedule_id").getNumber());
    }

    @Test
    public void viewedRequestRegistersAtTheClientOpcode() {
        assertEquals(23538, PacketOpcodes.SetBattlePassViewedReq);
        assertEquals(
                23538, HandlerSetBattlePassViewedReq.class.getAnnotation(Opcodes.class).value());
    }

    @Test
    public void selectsCurrentResourceScheduleAndFallsBackWithoutResources() {
        var schedules = GameData.getBattlePassScheduleDataMap();
        var saved = new Int2ObjectOpenHashMap<>(schedules);
        try {
            schedules.clear();
            assertEquals(6700, BattlePassScheduleData.currentId());
            for (int id : new int[] {6700, 7000, 7100, 7200})
                schedules.put(
                        id, JsonUtils.decode("{\"id\":" + id + "}", BattlePassScheduleData.class));
            assertEquals(7100, BattlePassScheduleData.currentId());
            assertEquals(7100, SafeBattlePassSchedule.build(null).getScheduleId());
            assertTrue(SafeBattlePassSchedule.build(null).getIsViewed());
        } finally {
            schedules.clear();
            schedules.putAll(saved);
        }
    }
}
