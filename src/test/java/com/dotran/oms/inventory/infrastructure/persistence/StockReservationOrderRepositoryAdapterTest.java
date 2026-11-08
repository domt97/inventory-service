package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationOrderRepository;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationOrderRepository.HeaderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockReservationOrderRepositoryAdapter")
class StockReservationOrderRepositoryAdapterTest {

    @Mock
    private SpringDataStockReservationOrderRepository repository;

    private StockReservationOrderRepositoryAdapter adapter;

    private final UUID orderUUID = UUID.randomUUID();
    private final UUID merchantUUID = UUID.randomUUID();
    private final UUID storeUUID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new StockReservationOrderRepositoryAdapter(repository, new ObjectMapper());
    }

    private StockReservationOrder header(OrderReservationStatus status, Boolean restock,
                                         ReservationRejection rejection) {
        return new StockReservationOrder(OrderId.of(orderUUID), MerchantId.of(merchantUUID), StoreId.of(storeUUID),
                status, restock, rejection);
    }

    @Test
    @DisplayName("insertIfAbsent: true when a row was inserted, false on conflict")
    void insertIfAbsent() {
        when(repository.insertIfAbsent(orderUUID, merchantUUID, storeUUID, "CANCELLED", true)).thenReturn(1, 0);

        assertThat(adapter.insertIfAbsent(header(OrderReservationStatus.CANCELLED, true, null))).isTrue();
        assertThat(adapter.insertIfAbsent(header(OrderReservationStatus.CANCELLED, true, null))).isFalse();
    }

    @Test
    @DisplayName("insertIfAbsent refuses a header that carries a rejection")
    void insertWithRejectionRefused() {
        assertThrows(IllegalArgumentException.class, () -> adapter.insertIfAbsent(header(
                OrderReservationStatus.REJECTED, null,
                ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND))));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("update without rejection uses the statement that leaves the JSONB column alone")
    void updateWithoutRejection() {
        when(repository.update(orderUUID, merchantUUID, storeUUID, "CONFIRMED", null, "RESERVED")).thenReturn(1);

        assertThat(adapter.update(header(OrderReservationStatus.CONFIRMED, null, null),
                OrderReservationStatus.RESERVED)).isEqualTo(1);
        verify(repository, never()).updateWithRejection(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a line rejection written by update reads back equal through lockForUpdate")
    void lineRejectionRoundTrip() {
        ReservationRejection rejection = ReservationRejection.ofLines(List.of(
                new ReservationRejection.Line(OrderItemId.of(UUID.randomUUID()), SKU.of("SKU-1"),
                        LineRejectionReason.NOT_ORDERABLE),
                new ReservationRejection.Line(OrderItemId.of(UUID.randomUUID()), SKU.of("SKU-2"),
                        LineRejectionReason.INSUFFICIENT_STOCK)));
        assertThat(roundTrip(rejection)).isEqualTo(header(OrderReservationStatus.REJECTED, null, rejection));
    }

    @Test
    @DisplayName("an order rejection written by update reads back equal through lockForUpdate")
    void orderRejectionRoundTrip() {
        ReservationRejection rejection = ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND);
        assertThat(roundTrip(rejection)).isEqualTo(header(OrderReservationStatus.REJECTED, null, rejection));
    }

    @Test
    @DisplayName("lockForUpdate maps a header without rejection, and empty when not owned")
    void lockMapsHeader() {
        HeaderView view = view("CANCELLED", false, null);
        when(repository.lockForUpdate(orderUUID, merchantUUID, storeUUID)).thenReturn(Optional.of(view));

        assertThat(adapter.lockForUpdate(OrderId.of(orderUUID), MerchantId.of(merchantUUID), StoreId.of(storeUUID)))
                .contains(header(OrderReservationStatus.CANCELLED, false, null));

        UUID otherStore = UUID.randomUUID();
        when(repository.lockForUpdate(orderUUID, merchantUUID, otherStore)).thenReturn(Optional.empty());
        assertThat(adapter.lockForUpdate(OrderId.of(orderUUID), MerchantId.of(merchantUUID), StoreId.of(otherStore)))
                .isEmpty();
    }

    private StockReservationOrder roundTrip(ReservationRejection rejection) {
        when(repository.updateWithRejection(eq(orderUUID), eq(merchantUUID), eq(storeUUID), eq("REJECTED"), eq(null),
                anyString(), eq("RESERVED"))).thenReturn(1);
        adapter.update(header(OrderReservationStatus.REJECTED, null, rejection), OrderReservationStatus.RESERVED);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).updateWithRejection(eq(orderUUID), eq(merchantUUID), eq(storeUUID), eq("REJECTED"),
                eq(null), json.capture(), eq("RESERVED"));

        HeaderView view = view("REJECTED", null, json.getValue());
        when(repository.lockForUpdate(orderUUID, merchantUUID, storeUUID)).thenReturn(Optional.of(view));
        return adapter.lockForUpdate(OrderId.of(orderUUID), MerchantId.of(merchantUUID), StoreId.of(storeUUID))
                .orElseThrow();
    }

    private HeaderView view(String status, Boolean restock, String rejection) {
        HeaderView view = mock(HeaderView.class);
        when(view.getOrderId()).thenReturn(orderUUID);
        when(view.getMerchantId()).thenReturn(merchantUUID);
        when(view.getStoreId()).thenReturn(storeUUID);
        when(view.getStatus()).thenReturn(status);
        when(view.getRestock()).thenReturn(restock);
        when(view.getRejection()).thenReturn(rejection);
        return view;
    }
}
