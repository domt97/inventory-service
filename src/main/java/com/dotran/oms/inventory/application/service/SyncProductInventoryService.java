package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.SkuSnapshot;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.Source;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.ProductCatalogVersionRepository;
import com.dotran.oms.inventory.application.repository.ProductCatalogVersionRepository.ProductCatalogVersion;
import com.dotran.oms.inventory.application.usecase.inventory.SyncProductInventoryUseCase;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.ProductSkuStatus;
import com.dotran.oms.inventory.domain.enums.ProductStatus;
import com.dotran.oms.inventory.domain.exception.CatalogOwnershipMismatchException;
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
import com.dotran.oms.inventory.domain.model.Inventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies a product's catalog snapshot to its inventory rows (ProductCreated and ProductUpdated).
 * <p>
 * Order inside the transaction matters: the version gate runs first and locks the product's gate row, so work on one
 * product is serialised; then ownership is checked, missing SKUs are inserted (sorted by code) and, for
 * ProductUpdated, each row's status is set with a guarded UPDATE in Java UUID order (the service-wide lock order).
 * Quantities are never written and rows are never deleted.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
public class SyncProductInventoryService implements SyncProductInventoryUseCase {

    static final int MAX_UPDATED_SKUS = 100;
    static final int MAX_SKU_LENGTH = 100;

    private final ProductCatalogVersionRepository catalogVersionRepository;
    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void sync(SyncProductInventoryCmd cmd) {
        Map<SKU, ProductSkuStatus> snapshot = validate(cmd);

        if (!catalogVersionRepository.advance(cmd.getProductId(), cmd.getMerchantId(), cmd.getStoreId(), cmd.getVersion())) {
            skipNotNewer(cmd);
            return;
        }

        List<Inventory> ownRows = inventoryRepository.getByProductId(cmd.getProductId());
        checkOwnership(cmd, ownRows);
        Set<SKU> ownSkus = ownRows.stream().map(Inventory::getSku).collect(Collectors.toSet());

        Set<SKU> claimedByOtherProducts = new HashSet<>();
        if (!snapshot.isEmpty()) {
            for (Inventory row : inventoryRepository.getByStoreIdAndSKUs(cmd.getStoreId(), new ArrayList<>(snapshot.keySet()))) {
                if (!row.getProductId().equals(cmd.getProductId())) {
                    log.warn("SKU {} in store {} belongs to product {}, not {}; skipped", row.getSku().getValue(),
                            cmd.getStoreId().getValue(), row.getProductId().getValue(), cmd.getProductId().getValue());
                    claimedByOtherProducts.add(row.getSku());
                }
            }
        }

        snapshot.entrySet().stream()
                .filter(entry -> !ownSkus.contains(entry.getKey()) && !claimedByOtherProducts.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(SKU::getValue)))
                .forEach(entry -> insert(cmd, entry.getKey(),
                        InventoryStatus.orderable(cmd.getProductStatus(), entry.getValue(), true)));

        if (cmd.getSource() == Source.PRODUCT_UPDATED) {
            // Re-read to include the rows just inserted. Safe only because nothing above changed existing rows with
            // native SQL: managed entities from the first read keep their status. Flush/clear first if that changes.
            List<Inventory> rows = inventoryRepository.getByProductId(cmd.getProductId());
            checkOwnership(cmd, rows);
            rows.stream()
                    .sorted(Comparator.comparing(row -> row.getId().getValue()))
                    .forEach(row -> setStatus(cmd, row, InventoryStatus.orderable(
                            cmd.getProductStatus(), snapshot.get(row.getSku()), snapshot.containsKey(row.getSku()))));
        }

        log.info("Synced product {} (store {}) to catalog version {}",
                cmd.getProductId().getValue(), cmd.getStoreId().getValue(), cmd.getVersion());
    }

    private Map<SKU, ProductSkuStatus> validate(SyncProductInventoryCmd cmd) {
        if (cmd.getSource() == null || cmd.getMerchantId() == null || cmd.getStoreId() == null
                || cmd.getProductId() == null || cmd.getVersion() == null || cmd.getProductStatus() == null
                || cmd.getSkus() == null) {
            throw new InvalidCatalogEventException("Catalog event is missing a required field");
        }
        boolean updated = cmd.getSource() == Source.PRODUCT_UPDATED;
        if (updated ? cmd.getVersion() < 1 : cmd.getVersion() != 0) {
            throw new InvalidCatalogEventException("Invalid version %d for %s of product %s"
                    .formatted(cmd.getVersion(), cmd.getSource(), cmd.getProductId().getValue()));
        }
        // ProductCreated is not capped: create and import have no SKU limit yet (open question 42).
        if (updated && cmd.getSkus().size() > MAX_UPDATED_SKUS) {
            throw new InvalidCatalogEventException("ProductUpdated for product %s has %d SKUs (max %d)"
                    .formatted(cmd.getProductId().getValue(), cmd.getSkus().size(), MAX_UPDATED_SKUS));
        }

        Map<SKU, ProductSkuStatus> snapshot = new LinkedHashMap<>();
        for (SkuSnapshot sku : cmd.getSkus()) {
            if (sku == null || sku.sku() == null || sku.sku().getValue() == null || sku.sku().getValue().isBlank()
                    || sku.sku().getValue().length() > MAX_SKU_LENGTH || sku.status() == null) {
                throw new InvalidCatalogEventException("Invalid SKU in catalog event for product %s"
                        .formatted(cmd.getProductId().getValue()));
            }
            ProductSkuStatus previous = snapshot.putIfAbsent(sku.sku(), sku.status());
            if (previous != null && previous != sku.status()) {
                throw new InvalidCatalogEventException("SKU %s appears twice with different statuses in product %s"
                        .formatted(sku.sku().getValue(), cmd.getProductId().getValue()));
            }
        }
        return snapshot;
    }

    private void skipNotNewer(SyncProductInventoryCmd cmd) {
        // advance() kept the gate row locked, so this read is consistent with the decision.
        ProductCatalogVersion current = catalogVersionRepository.findByProductId(cmd.getProductId()).orElse(null);
        if (current != null
                && (!current.merchantId().equals(cmd.getMerchantId()) || !current.storeId().equals(cmd.getStoreId()))) {
            throw new CatalogOwnershipMismatchException(
                    "Product %s is recorded for merchant %s / store %s, event says merchant %s / store %s".formatted(
                            cmd.getProductId().getValue(), current.merchantId().getValue(), current.storeId().getValue(),
                            cmd.getMerchantId().getValue(), cmd.getStoreId().getValue()));
        }
        log.info("Skipping {} version {} of product {}: already at version {}", cmd.getSource(), cmd.getVersion(),
                cmd.getProductId().getValue(), current != null ? current.version() : null);
    }

    private void checkOwnership(SyncProductInventoryCmd cmd, List<Inventory> rows) {
        for (Inventory row : rows) {
            if (!row.getMerchantId().equals(cmd.getMerchantId()) || !row.getStoreId().equals(cmd.getStoreId())) {
                throw new CatalogOwnershipMismatchException(
                        "Inventory %s of product %s belongs to merchant %s / store %s, event says merchant %s / store %s"
                                .formatted(row.getId().getValue(), cmd.getProductId().getValue(),
                                        row.getMerchantId().getValue(), row.getStoreId().getValue(),
                                        cmd.getMerchantId().getValue(), cmd.getStoreId().getValue()));
            }
        }
    }

    private void insert(SyncProductInventoryCmd cmd, SKU sku, InventoryStatus status) {
        Inventory inventory = Inventory.forCatalogSku(cmd.getMerchantId(), cmd.getStoreId(), cmd.getProductId(), sku, status);
        if (inventoryRepository.insertIfAbsent(inventory) == 0) {
            log.warn("Inventory for store {} sku {} already existed when product {} tried to create it (concurrent writer)",
                    cmd.getStoreId().getValue(), sku.getValue(), cmd.getProductId().getValue());
        }
    }

    private void setStatus(SyncProductInventoryCmd cmd, Inventory row, InventoryStatus target) {
        if (row.getStatus() == target) {
            return;
        }
        if (inventoryRepository.updateStatus(row.getId(), cmd.getMerchantId(), cmd.getStoreId(), cmd.getProductId(), target) > 0) {
            log.info("Inventory {} (sku {}) status {} -> {} (product {} version {})", row.getId().getValue(),
                    row.getSku().getValue(), row.getStatus(), target, cmd.getProductId().getValue(), cmd.getVersion());
        } else {
            log.debug("Inventory {} already {}", row.getId().getValue(), target);
        }
    }
}
