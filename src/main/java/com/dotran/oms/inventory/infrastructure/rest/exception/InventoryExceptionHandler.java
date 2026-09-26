package com.dotran.oms.inventory.infrastructure.rest.exception;

import com.dotran.oms.core.rest.ErrorResponse;
import com.dotran.oms.core.rest.GlobalExceptionHandler;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.exception.InvalidQuantityException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
@Slf4j
public class InventoryExceptionHandler extends GlobalExceptionHandler {

    @ExceptionHandler(value = InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse insufficientStockException(InsufficientStockException ex, HttpServletRequest request) {
        this.logError(ex);

        return ErrorResponse.builder()
                .error(ex.getMessage())
                .timestamp(Instant.now())
                .status(HttpStatus.CONFLICT.value())
                .path(request.getRequestURI())
                .build();
    }

    @ExceptionHandler(value = InvalidQuantityException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidQuantityException(InvalidQuantityException ex, HttpServletRequest request) {
        this.logError(ex);

        return ErrorResponse.builder()
                .error(ex.getMessage())
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .path(request.getRequestURI())
                .build();
    }
}
