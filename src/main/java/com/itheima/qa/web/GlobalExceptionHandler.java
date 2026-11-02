package com.itheima.qa.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常输出：把业务异常转成 JSON，避免 500 白屏。
 * 【安全改造】兜底异常不再把 e 拼进响应体（会泄露类名/堆栈/SQL 等内部信息），
 * 详情只写服务端日志，前端只看到通用提示。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthController.HttpStatusException.class)
    public ResponseEntity<Map<String, String>> biz(AuthController.HttpStatusException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> illegal(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(java.io.IOException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> io(java.io.IOException e) {
        return Map.of("error", e.getMessage());
    }

    /** 静态资源缺失（如浏览器探测 /favicon.ico）返回 404，避免被下面的兜底误判为 500 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return Map.of("error", "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> other(Exception e) {
        log.error("未处理的服务端异常", e);   // 完整堆栈只进服务端日志
        return Map.of("error", "服务内部错误，请联系管理员"); // 前端不暴露任何内部细节
    }
}
