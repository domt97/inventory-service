package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.ReserveStockCmd;
import com.dotran.example.inventory.application.mapper.StockReservationMapper;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.application.usecase.inventory.LoadInventoryUseCase;
import com.dotran.example.inventory.application.usecase.reservation.ReserveStockUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.domain.model.StockReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class ReserveStockService implements ReserveStockUseCase {

    private final StockReservationRepository stockReservationRepository;
    private final StockReservationMapper stockReservationMapper;
    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void reserve(ReserveStockCmd reserveStockCmd) {
        log.info("Reserving stock for inventory: {}, quantity: {}",
                reserveStockCmd.getInventoryId().getValue(), reserveStockCmd.getQuantity());

        Inventory inventory = inventoryRepository.getById(reserveStockCmd.getInventoryId())
                .orElseThrow(() -> new NotFoundException("Inventory not found"));
        inventory.reserve(reserveStockCmd.getQuantity());

        StockReservation stockReservation = stockReservationMapper.toDomain(reserveStockCmd);
        stockReservation.reserve();

        inventoryRepository.save(inventory);
        stockReservationRepository.create(stockReservation);

        log.info("Stock reserved successfully for inventory: {}, quantity: {}",
                reserveStockCmd.getInventoryId().getValue(), reserveStockCmd.getQuantity());
    }
}
