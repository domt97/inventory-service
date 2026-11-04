package com.dotran.oms.inventory.support;

import com.dotran.oms.core.logging.MdcProperties;
import com.dotran.oms.core.logging.MdcPropagation;

import java.nio.charset.StandardCharsets;

/** Real (not mocked) MDC helpers for tests: a mocked MdcPropagation would return a null map. */
public final class TestMdc {

    private TestMdc() {
    }

    /** MdcPropagation with oms-core's default configuration (propagates requestId only). */
    public static MdcPropagation propagation() {
        MdcProperties properties = new MdcProperties();
        properties.validate();
        return new MdcPropagation(properties);
    }

    public static byte[] header(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
