package com.company.agentgateway.domain.iam;

/**
 * RBAC 评估入口点（spec §GW-RBAC-010）。
 *
 * <p>用于 OTel Counter {@code rbac.allowed}/{@code rbac.denied} 的
 * {@code check_point} attribute 维度（spec §GW-RBAC-008），便于运维区分
 * 策略偏离来源。
 *
 * <p><b>plan 评审 #2 修复</b>：本 enum 直接定义在 {@code gateway-domain/iam}，
 * 与 {@link AuthorizationService} 接口同模块同包，消除跨层耦合。
 *
 * @see AuthorizationService#canInvokeAgent(AuthPrincipal, String, RbacCheckPoint)
 * @see AuthorizationService#canUseModel(AuthPrincipal, com.company.agentgateway.domain.shared.ModelId, RbacCheckPoint)
 */
public enum RbacCheckPoint {
    /** 入口：{@code RbacFilter}（gateway-interfaces），拦截 /v1/chat/* 与 /v1/agents/* 请求入口 */
    RBAC_FILTER,
    /** 入口：A2A 远程调用前 {@code RbacInflightPolicy}（gateway-infra-security）二次校验 */
    A2A,
    /** 入口：{@code AdminRbacPreviewController}（管理后台 preview 端点，纯函数仿真，不入 OTel/审计） */
    PREVIEW
}
