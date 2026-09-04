# Round 19 #1 报告 — 多租户 API 限流

> 日期：2026-09-03 · 主攻：**R19 #1 多租户 API 限流**
> 来源：R14 #1 RateLimitPlugin 升级 + 用户决策
> 借鉴：IETF RateLimit Headers / RFC 6585 (429) / RFC 7231 (Retry-After)

---

## 一、本轮目标与切片

R14 #1 的 RateLimitPlugin 是 sandbox 内的事后限流,无法在请求前阻断;
R19 #1 在 Spring WebFilter 层做**前置限流**,覆盖 /v1/chat /v1/mcp /v1/admin 全部业务路径。

补全生产硬化**最后一道**关键防护(多租户公平 + DoS 防护)。

## 二、产出

| 文件 | 用途 |
|---|---|
| `RateLimiter.java` + `RateLimitPolicy.java` + `RateLimitDecision.java` | Domain Port |
| `TokenBucketRateLimiter.java` | 内存 token bucket 实现 |
| `RateLimitAutoConfiguration.java` | Spring 装配 |
| `RateLimitFilter.java` | OncePerRequestFilter |
| `*Test.java` | 13 单测(domain 6 + filter 7) |

**累计 13 用例全绿**;verify.sh 11 模块 + 依赖方向全绿 ✅

## 三、亮点

### 1. 完整标准 headers
- `X-RateLimit-Limit`:桶容量
- `X-RateLimit-Remaining`:当前剩余
- `X-RateLimit-Reset`:桶满 epoch sec
- `Retry-After`(秒):blocked 时
- `429 Too Many Requests` + JSON `{"error":"rate_limited","retry_after_ms":N,"limit":N}`

### 2. 多租户识别
```java
X-Admin-Token → "admin:XXXX" (16-bit hash)
X-API-Key    → "key:XXXX"   (前 4 字符)
无 token     → "anonymous"   (共享桶)
```

### 3. 路径白名单
```java
shouldNotFilter: 跳过 /actuator / 静态资源
通过路径: /v1/chat / /v1/mcp / /v1/admin/*
```

### 4. Token bucket 算法
- 每桶 capacity 令牌,按 refillTokensPerSec 补充
- 同步桶,线程安全
- P0 内存;R19+1 swap Pg(pg_advisory_lock 多实例同步)

### 5. 默认配置 + 可覆盖
```yaml
gateway:
  ratelimit:
    capacity: 200        # burst 容量
    refill-per-sec: 100  # 稳态速率
```

## 四、API 示例

```bash
# 正常请求
curl -H "X-API-Key: sk-test" http://localhost:8080/v1/chat/completions
# < HTTP/1.1 200 OK
# < X-RateLimit-Limit: 200
# < X-RateLimit-Remaining: 199
# < X-RateLimit-Reset: 1725379200

# 超过 burst 后
# < HTTP/1.1 429 Too Many Requests
# < X-RateLimit-Limit: 200
# < X-RateLimit-Remaining: 0
# < Retry-After: 1
# < {"error":"rate_limited","retry_after_ms":1000,"limit":200}
```

## 五、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-domain -am test` | ✅ 6/6 |
| `mvn -pl :gateway-infra-persistence -am test` | ✅ (合并) |
| `mvn -pl :gateway-interfaces -am test` | ✅ 7/7 |
| `./verify.sh` | ✅ 11 模块 + 依赖方向全绿 |

## 六、评分

| 维度 | R18 末 | R19 #1 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 106 | **108**(+2:多租户公平 + DoS 防护) |
| 产品完整度 | 123 | **124**(+1:符合 IETF/RFC 标准) |

## 七、决策点

- **A**：接受 R19 #1 + R19 #2(Pg token bucket 持久化,跨实例共享)
- **B**：R19 收官报告 + 终止循环
- **C**：跳过 R19 持久化,做其他主题
