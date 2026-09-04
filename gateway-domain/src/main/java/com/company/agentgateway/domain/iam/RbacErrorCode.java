package com.company.agentgateway.domain.iam;

/**
 * RBAC 错误码常量集中类（spec §13.4 + proposal.md §错误码段分配）。
 *
 * <p>所有 throw new AuthorizationException("GW-1010") 必须引用此处常量，
 * 避免字符串散落（spec §验收判定 ⑥）。
 *
 * <p>段位零冲突：D1 占 GW-1xxx（1010~1013 增量）+ GW-42xx（复用 4204）。
 */
public final class RbacErrorCode {

    /** 无权限（spec §13.4 既有）。AuthorizationException → 403。 */
    public static final String UNAUTHORIZED = "GW-1003";

    /** 角色不存在。AdminRolesController GET/PUT/DELETE /{id} → 404。 */
    public static final String ROLE_NOT_FOUND = "GW-1010";

    /** 角色绑定冲突。AdminUserRoleController POST 重复绑定 → 409。 */
    public static final String ROLE_BINDING_CONFLICT = "GW-1011";

    /** 角色权限非法。AdminRolesController POST/PUT JSON 解析失败 / sealed 不识别 → 400。 */
    public static final String ROLE_PERMISSION_INVALID = "GW-1012";

    /** 用户角色绑定不存在。AdminUserRoleController DELETE /{roleId} → 404。 */
    public static final String USER_ROLE_BINDING_NOT_FOUND = "GW-1013";

    /** 管理后台 RBAC 错误（spec §19.3 既有）。GlobalExceptionHandler 兜底 → 500。 */
    public static final String ADMIN_RBAC_FALLBACK = "GW-4204";

    private RbacErrorCode() {
        // no instances
    }
}
