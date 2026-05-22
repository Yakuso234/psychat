package com.yakuso.psychat.controller;

import com.yakuso.psychat.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static String trace() {
        String tid = MDC.get("traceId");
        return tid != null ? " (trace: " + tid + ")" : "";
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntime(RuntimeException e) {
        log.error("Runtime error: {}", e.getMessage());
        return Result.fail(e.getMessage() + trace());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleOther(Exception e) {
        log.error("Unexpected error", e);
        return Result.fail("服务器内部错误" + trace());
    }
}
