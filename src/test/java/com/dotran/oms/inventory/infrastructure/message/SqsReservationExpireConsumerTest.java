package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SqsReservationExpireConsumer")
class SqsReservationExpireConsumerTest {

    @Mock
    private ExpireStockReservationUseCase expireStockReservationUseCase;

    @InjectMocks
    private SqsReservationExpireConsumer consumer;

    @Test
    @DisplayName("delegates to expireStockReservationUseCase on every message")
    void shouldDelegateToUseCase() {
        consumer.consume("trigger");

        verify(expireStockReservationUseCase).execute();
    }
}
