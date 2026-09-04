# gateway-replay-sdk

Agent Gateway 的 Replay Callback 验签 SDK(Sprint 2 P4.1)。

供**下游消费方**(运营平台、监控、第三方 BI)在收到 Agent Gateway 的 replay callback POST 时,验证其真伪。

## 协议

```
X-Replay-Timestamp: <unix epoch ms>
X-Replay-Signature: sha256=<hex>

signature = HMAC-SHA256(secret, timestamp + "\n" + method + "\n" + path + "\n" + body)
```

- 时间戳与本地时间偏差 **≤ 5 分钟**算有效(防重放)。
- `sha256=` 前缀**可选**(SDK 自动剥离)。

## Maven 依赖

```xml
<dependency>
    <groupId>com.company.agentgateway</groupId>
    <artifactId>gateway-replay-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 用法

```java
import com.company.agentgateway.replay.sdk.CallbackVerifier;

CallbackVerifier v = = new CallbackVerifier(System.getenv("GATEWAY_REPLAY_SECRET"));

// Spring Boot 端点
@PostMapping("/v1/cb")
public ResponseEntity<?> onCallback(
    @RequestHeader("X-Replay-Timestamp") String ts,
    @RequestHeader("X-Replay-Signature") String sig,
    @RequestBody String body,
    HttpServletRequest req
) {
    String err = v.verify(ts, sig, "POST", req.getRequestURI(), body);
    if (err != null) {
        return ResponseEntity.status(401).body(err);
    }
    // 处理 callback ...
    return ResponseEntity.ok().build();
}
```

## 错误码

| 返回值 | 含义 | 是否可重试 |
|---|---|---|
| `null` | 合法 | — |
| `"invalid timestamp"` | 非数字或缺失 | ❌ |
| `"timestamp out of window"` | 偏差 > 5 min | ❌(防重放) |
| `"missing signature"` | Header 缺失 | ❌ |
| `"signature mismatch"` | HMAC 不匹配 | ❌ |

## 发布

```bash
# Snapshot(开发版)
mvn deploy

# Release(生产版,需 GPG key)
mvn deploy -P release
```

## 依赖

- 仅 JDK 17+(用了 switch expressions、record patterns)
- 无 Spring、无 Jackson、无任何运行时依赖

## 许可证

Apache-2.0