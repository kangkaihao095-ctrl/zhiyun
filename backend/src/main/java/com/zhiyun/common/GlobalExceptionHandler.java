package com.zhiyun.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("error", ex.getMessage(), "status", ex.getStatus().value()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage() == null || err.getDefaultMessage().isBlank()
                        ? "参数不对" : err.getDefaultMessage())
                .orElse("参数不对");
        return ResponseEntity.badRequest().body(Map.of("error", msg, "status", 400));
    }
}
