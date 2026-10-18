package com.quant.api.web;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.quant.api.web.dto.Dtos;

/** 统一异常 → JSON（不泄露堆栈）。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorDto> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Dtos.ErrorDto(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Dtos.ErrorDto> stateError(IllegalStateException e) {
        log.warn("业务状态异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Dtos.ErrorDto(e.getMessage()));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Dtos.ErrorDto> sqlError(SQLException e) {
        log.error("数据库异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorDto("数据库异常，请检查库路径与表结构"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorDto> unexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorDto("服务内部异常: " + e.getMessage()));
    }
}
