package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AdminUser 内存实现（spec 2026-09-02 §multi-admin §4.1 P0）。
 *
 * <p>{@code CopyOnWriteArrayList} + 内存过滤;进程级单例。
 * R13 替换为 PgAdminUserRepository + bcrypt。
 */
public class InMemoryAdminUserRepository implements AdminUserRepository {

    private final CopyOnWriteArrayList<AdminUser> users = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public AdminUser save(AdminUser user) {
        if (user.id() == 0) {
            long id = nextId.getAndIncrement();
            AdminUser persisted = new AdminUser(
                    id, user.email(), user.name(), user.role(), user.status(),
                    user.tenantId(), user.apiKeyHash(), user.createdAt(), user.lastLoginAt());
            users.add(persisted);
            return persisted;
        }
        // update
        users.removeIf(u -> u.id() == user.id());
        users.add(user);
        return user;
    }

    @Override
    public Optional<AdminUser> findById(long id) {
        return users.stream().filter(u -> u.id() == id).findFirst();
    }

    @Override
    public Optional<AdminUser> findByEmail(String tenantId, String email) {
        if (email == null) return Optional.empty();
        String lower = email.trim().toLowerCase();
        return users.stream()
                .filter(u -> u.tenantId().equals(tenantId))
                .filter(u -> u.email().equalsIgnoreCase(lower))
                .findFirst();
    }

    @Override
    public List<AdminUser> findByTenant(String tenantId) {
        return users.stream()
                .filter(u -> u.tenantId().equals(tenantId))
                .filter(u -> u.status() != AdminStatus.DELETED)
                .sorted(Comparator.comparing(AdminUser::createdAt).reversed())
                .toList();
    }

    @Override
    public List<AdminUser> findByRole(String tenantId, AdminRole role) {
        return users.stream()
                .filter(u -> u.tenantId().equals(tenantId))
                .filter(u -> u.role() == role)
                .filter(u -> u.status() != AdminStatus.DELETED)
                .toList();
    }

    @Override
    public List<AdminUser> query(AdminUserQuery query) {
        return users.stream()
                .filter(u -> query.tenantId() == null || u.tenantId().equals(query.tenantId()))
                .filter(u -> query.role() == null || u.role() == query.role())
                .filter(u -> query.status() == null || u.status() == query.status())
                .filter(u -> u.status() != AdminStatus.DELETED)
                .sorted(Comparator.comparing(AdminUser::createdAt).reversed())
                .skip(query.offset())
                .limit(query.limit())
                .toList();
    }

    @Override
    public boolean delete(long id) {
        Optional<AdminUser> u = findById(id);
        if (u.isEmpty()) return false;
        AdminUser deleted = new AdminUser(
                u.get().id(), u.get().email(), u.get().name(), u.get().role(),
                AdminStatus.DELETED, u.get().tenantId(), u.get().apiKeyHash(),
                u.get().createdAt(), u.get().lastLoginAt());
        users.removeIf(x -> x.id() == id);
        users.add(deleted);
        return true;
    }
}
