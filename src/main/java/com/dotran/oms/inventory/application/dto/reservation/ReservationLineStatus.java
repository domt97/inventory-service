package com.dotran.oms.inventory.application.dto.reservation;

import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;

/** Current status of one order line's reservation, as reported in confirm / release results. */
public record ReservationLineStatus(OrderItemId orderItemId, ReservationStatus status) {
}
