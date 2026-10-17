package com.dotran.oms.inventory.infrastructure.rest.exception;

import com.dotran.oms.core.rest.ErrorResponse;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.exception.InvalidQuantityException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("InventoryExceptionHandler")
class InventoryExceptionHandlerTest {

    private InventoryExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new InventoryExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/v1/inventories/test");
    }

    @Test
    @DisplayName("InsufficientStockException returns 409 CONFLICT with exception message")
    void shouldReturn409ForInsufficientStock() {
        InsufficientStockException ex = new InsufficientStockException("Not enough stock");

        ErrorResponse response = handler.insufficientStockException(ex, request);

        assertEquals(HttpStatus.CONFLICT.value(), response.getStatus());
        assertEquals("Not enough stock", response.getError());
        assertEquals("/v1/inventories/test", response.getPath());
        assertNotNull(response.getTimestamp());
    }

    @Test
    @DisplayName("InvalidQuantityException returns 400 BAD_REQUEST with exception message")
    void shouldReturn400ForInvalidQuantity() {
        InvalidQuantityException ex = new InvalidQuantityException("Quantity must be positive");

        ErrorResponse response = handler.invalidQuantityException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        assertEquals("Quantity must be positive", response.getError());
        assertEquals("/v1/inventories/test", response.getPath());
        assertNotNull(response.getTimestamp());
    }
}
