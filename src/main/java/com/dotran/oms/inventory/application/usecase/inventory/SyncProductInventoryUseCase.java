package com.dotran.oms.inventory.application.usecase.inventory;

import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd;

public interface SyncProductInventoryUseCase {

    void sync(SyncProductInventoryCmd cmd);
}
