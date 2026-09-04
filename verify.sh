#!/usr/bin/env bash
# 一键验证门禁：全量编译 + 全部模块测试。CI 与发布前必跑。
set -euo pipefail
cd "$(dirname "$0")"
echo "=== [1/3] 全量编译 ==="
mvn clean install -DskipTests -Djacoco.skip=true
echo "✓ 编译通过"
echo "=== [2/3] 全量测试 ==="
fail=0
for m in gateway-domain gateway-application gateway-interfaces gateway-infra-llm gateway-infra-a2a \
         gateway-infra-nacos gateway-infra-persistence gateway-infra-security gateway-infra-observability \
         gateway-bootstrap example-agent; do
  if ! mvn -pl "$m" surefire:test -q > /dev/null 2>&1; then
    echo "✗ $m 测试失败"; fail=1
  else
    echo "  ✓ $m"
  fi
done
[ "$fail" -eq 0 ] || { echo "=== 验证失败 ==="; exit 1; }
echo "=== [3/3] 依赖方向负向断言 ==="
bad=$(mvn -q -pl gateway-application dependency:tree 2>/dev/null | grep -E 'gateway-(infra|interfaces)' || true)
[ -z "$bad" ] || { echo "✗ application 依赖 infra/interfaces：$bad"; exit 1; }
echo "  ✓ 依赖方向合规"
echo ""
echo "═══ 全部验证通过 ═══"
