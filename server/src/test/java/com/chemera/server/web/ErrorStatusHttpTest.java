package com.chemera.server.web;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.AuthController;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.RateGuard;
import com.chemera.server.security.SessionGuard;
import com.chemera.server.security.TapTapVerifier;
import com.chemera.server.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A7：HTTP 状态码收口。错误码是客户端与运维唯一的分流依据，而它整个只在"异常落到 advice"
 * 那一刻成立——业务码用 200、鉴权用 401、闸门用 403、方法用错用 405、路径不存在用 404，
 * 这张表一旦漂了，前端会拿 500 去猜"是不是服务器坏了"，运维会在错误日志里翻半天客户端的锅。
 *
 * <p>405 走真路由（standalone MockMvc + 真控制器 + 假服务），404/业务码表直接问 advice：
 * 前者要证明映射接在真实 DispatcherServlet 的异常流上，后者根本没有路由参与，
 * 硬造一个上下文只会让测试测到 Spring 自己。
 */
class ErrorStatusHttpTest {

    private GlobalExceptionHandler advice;

    @BeforeEach
    void setUp() {
        advice = new GlobalExceptionHandler();
    }

    /** 路径对、动词不对：以前落到 {@code Exception → 500}，现在必须是 405 并说清支持哪些动词。 */
    @Test
    void wrongHttpMethodIs405NotAFakeServerError() throws Exception {
        AuthService auth = mock(AuthService.class);
        RateGuard guard = new RateGuard(true, 200, 10, 50, 15, 200, 60, 200, 60, 200, 60, 20, 30, 5, 30, false);
        JwtService jwt = new JwtService("error-status-http-test-secret-32-bytes+", 60);
        TapTapVerifier tap = new TapTapVerifier(true, "open.tapapis.cn", "", new ObjectMapper());
        when(auth.taptap(any(), any())).thenReturn(java.util.Map.of("token", "tk", "user", "tapx", "guest", false));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(auth, guard, tap, new SessionGuard(mock(SessionMapper.class), jwt)))
                .setControllerAdvice(advice)
                .build();
        mvc.perform(get("/api/auth/taptap"))
                .andExpect(status().is(405))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(405))
                // 话术里带上允许的动词：调错的人不用再去翻控制器源码
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("POST")));
    }

    /** 未知路径：静态资源与 API 都归 404，而不是让"你请求的东西不存在"看起来像服务端故障。 */
    @Test
    void unknownPathIs404() {
        ResponseEntity<ApiResponse<Void>> r =
                advice.notFound(new NoResourceFoundException(HttpMethod.GET, "api/definitely/not/here"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertEquals(404, r.getBody().code);
        assertFalse(r.getBody().ok);
    }

    /**
     * 业务码 → 传输码这张表是刻意的：400 一类的"玩法不成立"必须回 200，
     * 客户端统一按 ok:false 走提示；只有鉴权/授权/不存在/限流才占用 HTTP 语义。
     */
    @Test
    void onlySecurityAndMissingResourceCodesEscalateToHttpStatus() {
        assertEquals(HttpStatus.UNAUTHORIZED, advice.biz(BizException.unauthorized("未登录"), null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, advice.biz(BizException.forbidden("时段限制"), null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, advice.biz(BizException.notFound("存档"), null).getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, advice.biz(BizException.tooMany("太快了"), null).getStatusCode());
        ResponseEntity<ApiResponse<Void>> plain = advice.biz(new BizException("金币不足"), null);
        assertEquals(HttpStatus.OK, plain.getStatusCode());
        assertFalse(plain.getBody().ok);
        assertEquals(400, plain.getBody().code);
    }

    /** 闸门话术会被改字，分流靠 tag：这条断言钉住"tag 不会在映射时被丢掉"。 */
    @Test
    void machineReadableTagSurvivesTheMapping() {
        ApiResponse<Void> body = advice.biz(
                BizException.forbidden("今天不是放行时段").tagged("CURFEW"), null).getBody();
        assertEquals("CURFEW", body.tag);
    }

    /** 请求体解析失败是客户端的事：回 400 说明要 UTF-8 JSON，且不进 500 的错误日志。 */
    @Test
    void unparseableBodyIs400() {
        ApiResponse<Void> body = advice.unreadable(
                new HttpMessageNotReadableException("Unexpected character '<'"));
        assertEquals(400, body.code);
        assertTrue(body.msg.contains("UTF-8"));
    }

    /** 兜底仍然是 500：真·服务端故障不能被上面的映射吃掉，否则运维看不到堆栈。 */
    @Test
    void unexpectedFailureStillIs500() {
        ResponseEntity<ApiResponse<Void>> r = advice.other(new IllegalStateException("数据库连接池断了"),
                new MockHttpServletRequest("GET", "/api/game/state"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertEquals(500, r.getBody().code);
    }
}
