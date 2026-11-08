package com.dotran.oms.inventory.domain.enums;

/** Why a whole reserve was rejected. */
public enum OrderRejectionReason {
    /** The order was closed before this reserve (release or confirm tombstone). */
    ORDER_CLOSED,
    /** The command is older than the hold TTL (BR-INV-11). */
    STALE_COMMAND
}
