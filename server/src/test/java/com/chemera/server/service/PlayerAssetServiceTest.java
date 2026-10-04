package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.UserSave;
import com.chemera.server.mapper.SaveMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 后台调资产守住的是"运营手滑"这一类事故：凭空造钱、把余额扣成负数、
 * 以及最隐蔽的一种——把整份存档重写一遍时吃掉模型不认识的字段。
 */
class PlayerAssetServiceTest {

    private SaveMapper saves;
    private SaveService saveService;
    private ObjectMapper om;
    private PlayerAssetService svc;

    @BeforeEach
    void setUp() {
        saves = mock(SaveMapper.class);
        saveService = mock(SaveService.class);
        om = new ObjectMapper();
        svc = new PlayerAssetService(saves, saveService, om);
        when(saves.currentRevision(anyLong())).thenReturn(8L);
    }

    private void have(String payloadJson) {
        UserSave s = new UserSave();
        s.setUserId(7L); s.setPayload(payloadJson); s.setRevision(7L);
        when(saves.find(7L)).thenReturn(s);
    }

    private JsonNode written() {
        ArgumentCaptor<JsonNode> cap = ArgumentCaptor.forClass(JsonNode.class);
        verify(saveService).put(eq(7L), cap.capture(), isNull(), eq(true), eq("admin"));
        return cap.getValue();
    }

    @Test
    void adjustsBothCurrenciesAndReportsBeforeAfter() {
        have("{\"v\":2,\"coins\":1000,\"diamonds\":3,\"level\":1,\"bag\":{},\"discovered\":{}}");
        Map<String, Object> r = svc.adjust(7L, 500, 2);
        assertEquals(1000L, r.get("coinsBefore"));
        assertEquals(1500L, r.get("coinsAfter"));
        assertEquals(3L, r.get("diamondsBefore"));
        assertEquals(5L, r.get("diamondsAfter"));
        assertEquals(8L, r.get("revision"));
        assertEquals(1500L, written().path("coins").asLong());
        assertEquals(5L, written().path("diamonds").asLong());
    }

    /** GameState 是 ignoreUnknown：读成对象再写回会静默丢字段，所以这里必须确认原样保留。 */
    @Test
    void keepsFieldsTheServerModelDoesNotKnowAbout() {
        have("{\"v\":2,\"coins\":10,\"diamonds\":0,\"level\":1,\"bag\":{},\"discovered\":{}," +
             "\"clientLeftover\":{\"keep\":true},\"someFutureField\":42}");
        svc.adjust(7L, 90, 0);
        JsonNode out = written();
        assertTrue(out.path("clientLeftover").path("keep").asBoolean());
        assertEquals(42, out.path("someFutureField").asInt());
        assertEquals(2, out.path("v").asInt());
    }

    @Test
    void legacySaveWithoutDiamondFieldStartsFromZero() {
        have("{\"v\":1,\"coins\":10,\"level\":1,\"bag\":{},\"discovered\":{}}");
        Map<String, Object> r = svc.adjust(7L, 0, 4);
        assertEquals(0L, r.get("diamondsBefore"));
        assertEquals(4L, r.get("diamondsAfter"));
    }

    @Test
    void refusesWhenNothingWouldChange() {
        have("{\"v\":2,\"coins\":10,\"diamonds\":1,\"level\":1,\"bag\":{},\"discovered\":{}}");
        BizException e = assertThrows(BizException.class, () -> svc.adjust(7L, 0, 0));
        assertTrue(e.getMessage().contains("没有要调整"));
        verifyNoInteractions(saveService);
    }

    @Test
    void refusesToInventASaveForAPlayerThatHasNone() {
        when(saves.find(7L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> svc.adjust(7L, 1000, 0));
        assertTrue(e.getMessage().contains("还没有云端存档"));
        verifyNoInteractions(saveService);
    }

    @Test
    void singleStepIsCapped() {
        have("{\"v\":2,\"coins\":10,\"diamonds\":1,\"level\":1,\"bag\":{},\"discovered\":{}}");
        BizException e = assertThrows(BizException.class,
                () -> svc.adjust(7L, PlayerAssetService.MAX_STEP + 1, 0));
        assertTrue(e.getMessage().contains("不超过"));
        verifyNoInteractions(saveService);
    }

    @Test
    void cannotOverdrawIntoNegative() {
        have("{\"v\":2,\"coins\":100,\"diamonds\":0,\"level\":1,\"bag\":{},\"discovered\":{}}");
        BizException e = assertThrows(BizException.class, () -> svc.adjust(7L, -500, 0));
        assertTrue(e.getMessage().contains("不足扣减"));
        verifyNoInteractions(saveService);
    }

    @Test
    void cannotBlowPastTheBalanceCap() {
        have("{\"v\":2,\"coins\":100,\"diamonds\":900000,\"level\":1,\"bag\":{},\"discovered\":{}}");
        BizException e = assertThrows(BizException.class, () -> svc.adjust(7L, 0, 200_000));
        assertTrue(e.getMessage().contains("超过上限"));
        verifyNoInteractions(saveService);
    }

    @Test
    void refusesToTouchACorruptSave() {
        have("{not json");
        BizException e = assertThrows(BizException.class, () -> svc.adjust(7L, 100, 0));
        assertTrue(e.getMessage().contains("合法 JSON"));
        verifyNoInteractions(saveService);
    }

    @Test
    void refusesWhenCoinsAreNotANumber() {
        have("{\"v\":2,\"coins\":\"many\",\"diamonds\":0,\"level\":1,\"bag\":{},\"discovered\":{}}");
        BizException e = assertThrows(BizException.class, () -> svc.adjust(7L, 100, 0));
        assertTrue(e.getMessage().contains("金币字段"));
        verifyNoInteractions(saveService);
    }
}
