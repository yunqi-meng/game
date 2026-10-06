package com.chemera.server.controller;

import com.chemera.server.game.ContentRegistry;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.security.AuthContext;
import com.chemera.server.service.ContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 埋点采集开关的落点测试。
 *
 * <p>合规面要的是"运营拨了关停，就真的不再留行为记录"，所以断言全部下在**是否写库**这一层，
 * 而不是接口返回了什么——返回 200 是刻意的（客户端不该因为停止采集看到错误，已发出去的包更不会为此更新）。
 */
class AnalyticsGateTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 走引擎真实的装配路径：mock 掉取 bundle 的 DB 依赖，配置由 Jackson 转成 Content.Config。 */
    private static ContentRegistry registry(Object analyticsEnabled) {
        Map<String, Object> cfg = analyticsEnabled == null
                ? Map.of() : Map.of("analytics_enabled", analyticsEnabled);
        ContentService content = mock(ContentService.class);
        when(content.bundle()).thenReturn(Map.of("version", 1L, "content", Map.of(), "config", cfg));
        return new ContentRegistry(content, OM);
    }

    private static AnalyticsController controller(Object analyticsEnabled, AnalyticsMapper mapper) {
        return new AnalyticsController(mapper, registry(analyticsEnabled), OM);
    }

    private static Map<String, Object> body(String event) {
        return Map.of("event", event, "props", Map.of("id", "H2O"));
    }

    /** 拦截器写好的登录态：埋点接口本来就在鉴权之后，测试不该绕过这件事。 */
    private static MockHttpServletRequest asPlayer() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthContext.ATTR_USER, 7L);
        return req;
    }

    @Test
    void collectsByDefaultBecauseNoSeedMeansNoChange() {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        var r = controller(null, mapper).event(body("discover"), asPlayer());
        assertTrue(r.ok);
        verify(mapper).insert(eq(7L), eq("discover"), anyString());
    }

    @Test
    void explicitTrueKeepsCollecting() {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        controller(true, mapper).event(body("sell"), asPlayer());
        verify(mapper).insert(eq(7L), eq("sell"), anyString());
    }

    @Test
    void switchOffDropsTheEventButStillAnswersTwoHundred() {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        var r = controller(false, mapper).event(body("react_success"), asPlayer());
        assertTrue(r.ok, "关停不是错误，客户端不该因此弹提示");
        verify(mapper, never()).insert(any(), anyString(), any());
    }

    @Test
    void unknownEventIsDroppedEitherWay() {
        AnalyticsMapper on = mock(AnalyticsMapper.class);
        assertTrue(controller(null, on).event(body("some_future_event"), asPlayer()).ok);
        verify(on, never()).insert(any(), anyString(), any());

        AnalyticsMapper off = mock(AnalyticsMapper.class);
        assertTrue(controller(false, off).event(body("discover"), asPlayer()).ok);
        verify(off, never()).insert(any(), anyString(), any());
    }

    @Test
    void propsAreSerialisedWhenCollecting() {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        controller(true, mapper).event(body("discover"), asPlayer());
        verify(mapper).insert(eq(7L), eq("discover"), eq("{\"id\":\"H2O\"}"));
    }

    /**
     * 关停时判定发生在读登录态之前：这条顺序是有意的——采集停了就连身份都不必问，
     * 少一次对 app_user 的读，也少一个"因为令牌过期把关停请求打成 401"的口子。
     */
    @Test
    void switchOffShortCircuitsBeforeTouchingTheSession() {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        var r = controller(false, mapper).event(body("discover"), new MockHttpServletRequest());
        assertTrue(r.ok, "没登录也不报错：这一条本来就没人收");
        verify(mapper, never()).insert(any(), anyString(), any());
    }
}
