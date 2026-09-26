package com.dotran.oms.inventory.infrastructure.jobs;

import com.dotran.oms.inventory.application.jobs.AwaitingExpireStockReservationUseCase;
import com.dotran.oms.inventory.application.service.ExpireStockReservationService;
import com.dotran.oms.core.annotation.JobAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@JobAdapter
@RequiredArgsConstructor
@Slf4j
public class AwaitingExpireStockReservationAdapter implements AwaitingExpireStockReservationUseCase {

    private final ExpireStockReservationService expireStockReservationService;

    @Scheduled(cron = "${app.inventory.jobs.awaiting-expire-stock-reservation.cron}")
    @Override
    public void execute() {
        log.info("Executing AwaitingExpireStockReservationAdapter");

        expireStockReservationService.execute();

        log.info("Finished executing AwaitingExpireStockReservationAdapter");
    }
}
