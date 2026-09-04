package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;

/**
 * 出站端口：RBAC 授权决策（spec §6.3）。由 gateway-infra-security 实现。
 *
 * <p>纵深防御：与 AuthPrincipal.canInvoke/canUse（领域判定）配合，本服务提供「检查并抛异常」语义，
 * 供编排层在关键点（Agent 调用前、模型选择前）强制校验。一期 Agent 级 + 模型级；Skill/数据级二期。
 *
 * <p><b>D1 新增（plan 评审 #2 修复）</b>：新增 2 个带 {@link RbacCheckPoint} 的重载方法（共 6 方法），
 * 用于 RbacFilter / RbacInflightPolicy / AdminRbacPreviewController 传入明确的 checkPoint，
 * 由实现层打 OTel attribute {@code rbac.check_point=...}。
 * 既有 4 方法签名零变化；既有 {@code AuthorizationServiceImplTest} 6 条用例零修改。
 */
public interface AuthorizationService {

    /** 能否调用 Agent。 */
    boolean canInvokeAgent(AuthPrincipal principal, String agentName);

    /** 能否使用模型。 */
    boolean canUseModel(AuthPrincipal principal, ModelId model);

    /** 检查并抛 {@link AuthorizationException}（无权时）。Agent 调用前强制校验。 */
    void checkInvokeAgent(AuthPrincipal principal, String agentName);

    /** 检查并抛 {@link AuthorizationException}（无权时）。模型选择前强制校验。 */
    void checkUseModel(AuthPrincipal principal, ModelId model);

    /**
     * 🆕 D1 新增：带 checkPoint 的 canInvokeAgent 重载（spec §GW-RBAC-010）。
     *
     * <p>由 RbacFilter（{@code RBAC_FILTER}）/ RbacInflightPolicy（{@code A2A}）/
     * AdminRbacPreviewController（{@code PREVIEW}）调用。
     */
    boolean canInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint);

    /**
     * 🆕 D1 新增：带 checkPoint 的 canUseModel 重载（spec §GW-RBAC-010）。
     */
    boolean canUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint);

    /**
     * 🆕 D1 新增：带 checkPoint 的 checkInvokeAgent 重载（spec §GW-RBAC-006 纵深防御 A2A 二次校验）。
     */
    void checkInvokeAgent(AuthPrincipal principal, String agentName, RbacCheckPoint checkPoint);

    /**
     * 🆕 D1 新增：带 checkPoint 的 checkUseModel 重载。
     */
    void checkUseModel(AuthPrincipal principal, ModelId model, RbacCheckPoint checkPoint);
}
