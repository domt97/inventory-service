package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.support.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three stock commands really run with the configured transaction timeout: the property is set to 7 (not the
 * default 10), so a typo in the placeholder key would fall back to 10 and fail. 3 s < 5 s < 7 s keeps the properties valid.
 * Own property → own (cached) Spring context; the Postgres container is shared.
 */
@DisplayName("Stock command transaction attributes")
@TestPropertySource(properties = "app.reservation.transaction-timeout-seconds=7")
class StockCommandTransactionIT extends PostgresIT {

    @Autowired
    private TransactionAttributeSource transactionAttributeSource;

    private int timeout(Class<?> service, String method, Class<?> command) throws NoSuchMethodException {
        Method m = service.getMethod(method, command);
        TransactionAttribute attribute = transactionAttributeSource.getTransactionAttribute(m, service);
        assertThat(attribute).as("%s.%s is transactional", service.getSimpleName(), method).isNotNull();
        return attribute.getTimeout();
    }

    @Test
    @DisplayName("reserve, confirm and release use app.reservation.transaction-timeout-seconds")
    void timeouts() throws NoSuchMethodException {
        assertThat(timeout(ReserveStockService.class, "reserve", ReserveStockCmd.class)).isEqualTo(7);
        assertThat(timeout(ConfirmStockReservationService.class, "confirm", ConfirmStockReservationCmd.class))
                .isEqualTo(7);
        assertThat(timeout(ReleaseStockReservationService.class, "release", ReleaseStockReservationCmd.class))
                .isEqualTo(7);
    }
}
