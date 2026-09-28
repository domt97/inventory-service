package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqsReservationExpireConsumer {

    private final ExpireStockReservationUseCase expireStockReservationUseCase;

    @SqsListener("${aws.sqs.queues.reservation-expire}")
    public void consume(String message) {
        log.info("Received reservation expire event");

        expireStockReservationUseCase.execute();

        log.info("Finished processing reservation expire event");
    }
}
