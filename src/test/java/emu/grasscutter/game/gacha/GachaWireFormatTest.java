package emu.grasscutter.game.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.UnknownFieldSet;

import emu.grasscutter.net.proto.DoGachaRspOuterClass.DoGachaRsp;
import emu.grasscutter.net.proto.GachaInfoOuterClass.GachaInfo;
import emu.grasscutter.net.proto.GachaItemOuterClass.GachaItem;
import emu.grasscutter.server.packet.send.PacketDoGachaRsp;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

public class GachaWireFormatTest {
    @Test
    public void bannerUrlsUseTheirClientFieldNumbers() throws Exception {
        var message =
                GachaInfo.newBuilder()
                        .setGachaRecordUrl("record")
                        .setGachaRecordUrlOversea("oversea")
                        .setTitleTextmap("title")
                        .build();
        var wire = UnknownFieldSet.parseFrom(message.toByteArray());
        assertEquals(Set.of(7, 600, 2032), wire.asMap().keySet());
        assertEquals(
                List.of(ByteString.copyFromUtf8("record")),
                wire.getField(7).getLengthDelimitedList());
        assertEquals(
                List.of(ByteString.copyFromUtf8("oversea")),
                wire.getField(600).getLengthDelimitedList());
        assertEquals(
                List.of(ByteString.copyFromUtf8("title")),
                wire.getField(2032).getLengthDelimitedList());

        var incoming =
                UnknownFieldSet.newBuilder()
                        .addField(7, text("client-record"))
                        .addField(600, text("client-oversea"))
                        .addField(2032, text("client-title"))
                        .build();
        var decoded = GachaInfo.parseFrom(incoming.toByteArray());
        assertEquals("client-record", decoded.getGachaRecordUrl());
        assertEquals("client-oversea", decoded.getGachaRecordUrlOversea());
        assertEquals("client-title", decoded.getTitleTextmap());
    }

    @Test
    public void itemFlagsEncodeAndDecodeIndependently() throws Exception {
        var flash =
                UnknownFieldSet.parseFrom(
                        GachaItem.newBuilder().setIsFlashCard(true).build().toByteArray());
        var fresh =
                UnknownFieldSet.parseFrom(
                        GachaItem.newBuilder().setIsGachaItemNew(true).build().toByteArray());
        assertEquals(Set.of(13), flash.asMap().keySet());
        assertEquals(List.of(1L), flash.getField(13).getVarintList());
        assertEquals(Set.of(12), fresh.asMap().keySet());
        assertEquals(List.of(1L), fresh.getField(12).getVarintList());

        var decodedFlash =
                GachaItem.parseFrom(
                        UnknownFieldSet.newBuilder().addField(13, varint(1)).build().toByteArray());
        var decodedFresh =
                GachaItem.parseFrom(
                        UnknownFieldSet.newBuilder().addField(12, varint(1)).build().toByteArray());
        assertTrue(decodedFlash.getIsFlashCard());
        assertFalse(decodedFlash.getIsGachaItemNew());
        assertTrue(decodedFresh.getIsGachaItemNew());
        assertFalse(decodedFresh.getIsFlashCard());
    }

    @Test
    public void responseUsesCorrectedFieldsAndRetainsWishFields() throws Exception {
        var message =
                DoGachaRsp.newBuilder()
                        .setLeftGachaTimes(20)
                        .setGachaTimesLimit(30)
                        .setTenCostItemId(223)
                        .setTenCostItemNum(10)
                        .setWishProgress(1)
                        .setWishItemId(11501)
                        .setWishMaxProgress(2)
                        .setCurScheduleDailyGachaTimes(40)
                        .setIsCapturingRadiance(true)
                        .build();
        var wire = UnknownFieldSet.parseFrom(message.toByteArray());
        assertEquals(Set.of(3, 5, 8, 9, 10, 12, 15, 286, 1794), wire.asMap().keySet());
        assertEquals(List.of(20L), wire.getField(5).getVarintList());
        assertEquals(List.of(30L), wire.getField(3).getVarintList());
        assertEquals(List.of(223L), wire.getField(9).getVarintList());
        assertEquals(List.of(10L), wire.getField(10).getVarintList());
        assertEquals(List.of(1L), wire.getField(286).getVarintList());

        var incoming =
                UnknownFieldSet.newBuilder()
                        .addField(5, varint(4))
                        .addField(3, varint(12))
                        .addField(9, varint(224))
                        .addField(286, varint(1))
                        .build();
        var decoded = DoGachaRsp.parseFrom(incoming.toByteArray());
        assertEquals(4, decoded.getLeftGachaTimes());
        assertEquals(12, decoded.getGachaTimesLimit());
        assertEquals(224, decoded.getTenCostItemId());
        assertTrue(decoded.getIsCapturingRadiance());
    }

    @Test
    public void descriptorsMatchTheWireAndKeepAstaFields() {
        var info = GachaInfo.getDescriptor();
        assertField(info, "gacha_record_url", 7);
        assertField(info, "gacha_record_url_oversea", 600);
        assertField(info, "title_textmap", 2032);
        assertField(info, "HMOJLEMLHDK", 410);
        assertField(info, "is_new_wish", 1937);
        var item = GachaItem.getDescriptor();
        assertField(item, "is_flash_card", 13);
        assertField(item, "is_gacha_item_new", 12);
        var response = DoGachaRsp.getDescriptor();
        assertField(response, "left_gacha_times", 5);
        assertField(response, "gacha_times_limit", 3);
        assertField(response, "ten_cost_item_id", 9);
        assertField(response, "ten_cost_item_num", 10);
        assertField(response, "is_capturing_radiance", 286);
        assertField(response, "wish_progress", 8);
        assertField(response, "wish_item_id", 12);
        assertField(response, "wish_max_progress", 15);
        assertField(response, "cur_schedule_daily_gacha_times", 1794);
        assertNull(response.findFieldByName("is_epitomized"));
    }

    @Test
    public void packetRadianceFlagFollowsThePullResult() throws Exception {
        var character = JsonUtils.decode("{\"bannerType\":\"CHARACTER\"}", GachaBanner.class);
        character.onLoad();
        var info = new PlayerGachaBannerInfo();
        var result = List.of(GachaItem.newBuilder().setIsFlashCard(true).build());
        var radiance =
                DoGachaRsp.parseFrom(new PacketDoGachaRsp(character, result, info).getData());
        assertTrue(radiance.getIsCapturingRadiance());

        var weapon = JsonUtils.decode("{\"bannerType\":\"WEAPON\"}", GachaBanner.class);
        weapon.onLoad();
        info.setWishItemId(11501);
        info.setFailedChosenItemPulls(1);
        var ordinary =
                DoGachaRsp.parseFrom(
                        new PacketDoGachaRsp(weapon, List.of(GachaItem.getDefaultInstance()), info)
                                .getData());
        assertFalse(ordinary.getIsCapturingRadiance());
        assertEquals(11501, ordinary.getWishItemId());
        assertEquals(1, ordinary.getWishProgress());
        assertEquals(1, ordinary.getWishMaxProgress());
    }

    private static UnknownFieldSet.Field text(String value) {
        return UnknownFieldSet.Field.newBuilder()
                .addLengthDelimited(ByteString.copyFromUtf8(value))
                .build();
    }

    private static UnknownFieldSet.Field varint(long value) {
        return UnknownFieldSet.Field.newBuilder().addVarint(value).build();
    }

    private static void assertField(Descriptor message, String name, int number) {
        assertEquals(number, message.findFieldByName(name).getNumber(), name);
    }
}
