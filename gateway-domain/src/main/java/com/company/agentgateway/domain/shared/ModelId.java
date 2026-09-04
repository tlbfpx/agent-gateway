package com.company.agentgateway.domain.shared;

import java.math.BigDecimal;

public record ModelId(String value) {
    public ModelId {
        IdValidation.requireNonBlank(value);
    }

    /**
     * 模型单价（spec §5.5.2 · D2 BillingEngine 单价快照源）。
     *
     * <p>priceIn/priceOut：每 token 单价（非每 1k token；由 ModelDef 换算后注入）。
     */
    public record Price(BigDecimal priceIn, BigDecimal priceOut) {
        public Price {
            if (priceIn == null || priceIn.signum() < 0) {
                throw new IllegalArgumentException("priceIn must be ≥ 0");
            }
            if (priceOut == null || priceOut.signum() < 0) {
                throw new IllegalArgumentException("priceOut must be ≥ 0");
            }
        }
    }
}
