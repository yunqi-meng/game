package com.chemera.server.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> biz(BizException e, HttpServletRequest req) {
        HttpStatus hs = switch (e.code) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.OK; // 业务错误用 200 + ok:false，便于客户端统一处理
        };
        return ResponseEntity.status(hs).body(ApiResponse.err(e.code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> valid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String msg = fe == null ? "参数校验失败" : fe.getField() + " " + fe.getDefaultMessage();
        return ApiResponse.err(400, msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.err(404, "资源不存在"));
    }

    /** 请求体不是合法 JSON（含非 UTF-8 编码）：是客户端的锅，别按 500 记错误日志。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> unreadable(HttpMessageNotReadableException e) {
        return ApiResponse.err(400, "请求体无法解析：请发送 UTF-8 编码的 JSON");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception e, HttpServletRequest req) {
        log.error("未处理异常 {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.err(500, "服务器内部错误"));
    }
}
