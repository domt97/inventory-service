package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.domain.id.InventoryId;

import java.util.Collection;
import java.util.List;

public interface StockMovementRepository {

    StockMovement create(StockMovement stockMovement);

    List<StockMovement> getByInventoryId(InventoryId inventoryId);

    /**
     * One movement of {@code type} per reservation that is now in {@code requiredStatus}: quantity of the reservation,
     * reference {@code ORDER_ITEM} / order item id, {@code reservation_id} set (BR-INV-05). Set-based
     * {@code INSERT ... SELECT ... ON CONFLICT (reservation_id, type) DO NOTHING}. Empty input → 0, no query.
     *
     * @return rows inserted
     */
    int insertForReservations(Collection<StockReservationId> reservationIds, StockMovementType type,
                              ReservationStatus requiredStatus, String reason);
}
