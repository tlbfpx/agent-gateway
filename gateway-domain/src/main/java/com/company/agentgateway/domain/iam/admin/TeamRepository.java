package com.company.agentgateway.domain.iam.admin;

import java.util.List;
import java.util.Optional;

/**
 * Team 持久化端口（spec 2026-09-02 §multi-admin §5.2）。
 */
public interface TeamRepository {

    /** 保存(id==0 → insert) */
    Team save(Team team);

    /** 按主键查 */
    Optional<Team> findById(long id);

    /** 按租户列 */
    List<Team> findByTenant(String tenantId);

    /** 按 ownerId 查（Owner 视角） */
    List<Team> findByOwner(long ownerId);

    /** 某用户所属的所有团队(含作为 owner 或 member) */
    List<Team> findByMember(long adminUserId);

    /** 按租户 + 名称查重 */
    Optional<Team> findByName(String tenantId, String name);

    /** 删除(硬删,审计可从 audit 追溯) */
    boolean delete(long id);

    /** 全条件查询 */
    List<Team> query(TeamQuery query);

    record TeamQuery(
            String tenantId,
            Long ownerId,
            int limit,
            int offset) {
        public TeamQuery {
            limit = limit <= 0 ? 50 : Math.min(limit, 500);
            offset = Math.max(offset, 0);
        }
    }
}
