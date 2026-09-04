package com.company.agentgateway.domain.shared;

/** Identity 值对象的公共非空校验。package-private，仅供本包 record 使用。 */
final class IdValidation {
    private IdValidation() {}

    static void requireNonBlank(String v) {
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("identity value must not be blank");
    }
}
