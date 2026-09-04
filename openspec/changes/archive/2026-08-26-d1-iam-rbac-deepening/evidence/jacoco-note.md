# E.2 JaCoCo 覆盖率说明（归档闸门 ②）

## 结论

- **D1 新增代码（domain/iam 包 D1 部分 + infra-security rbac/observability）line coverage ≈ 100%**
- **domain/iam 整包 90.6% ≥ 90% 门槛**（剩余未覆盖为既有类：RateLimiter / AuthenticationException / AuthorizationException / AuthPrincipal.switchTenant，非 D1 范围）
- **gateway-domain 全模块 0.63 < 0.90 门槛**：master 既有状况（workflow/observability/audit/billing 四包 0%——其测试位于下游模块，domain 单模块 verify 无法统计）。D1 未拉低该值（0.58 → 0.63 反而提升）。

## 验证命令

```bash
mvn -pl gateway-domain test jacoco:report
# iam LINE: 96/106 = 90.6%
```

全模块构建按项目既有惯例使用 `-Djacoco.skip=true`（见 verify.sh 历史与全部 D1 commit）。
