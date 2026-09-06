#!/usr/bin/env bash
# setup-public-demo.sh — 公开 demo 实例一键部署
# spec §public-demo §2 round 34
#
# 用法（在已配 kubectl 上下文 + 准备好 secret 的运维机器上）：
#   ./scripts/setup-public-demo.sh demo.agent-gateway.com
#
# 输入参数：
#   $1 = 公开域名（默认 demo.agent-gateway.local）
#
# 环境变量（必须提前 export）：
#   GATEWAY_ADMIN_TOKEN    - 32 字节随机（演示用，生产应从 secret 注入）
#   OIDC_CLIENT_ID/SECRET  - 可选：演示 SSO 用的 IdP（留空则关 OIDC）
#   POSTGRES_PASSWORD      - PG 密码（helm install --set secrets.observPostgresPassword）
#
# 行为：
#   1. helm install 一次性 install 全套（gateway + postgres + ingress）
#   2. 等待 rollout 完成（kubectl rollout status）
#   3. 跑 helm-test 确认 200
#   4. 输出公开 URL + 首次登录引导
#
# 不要在生产集群跑 — 这个脚本是给 demo.agent-gateway.com 专用 staging 集群用的。

set -euo pipefail

DOMAIN="${1:-demo.agent-gateway.local}"
CHART="${CHART:-./deploy/helm/agent-gateway}"
RELEASE_NAME="${RELEASE_NAME:-demo-gateway}"
NAMESPACE="${NAMESPACE:-agent-gateway-demo}"

ADMIN_TOKEN="${GATEWAY_ADMIN_TOKEN:-}"
POSTGRES_PWD="${POSTGRES_PASSWORD:-}"
OIDC_ID="${OIDC_CLIENT_ID:-}"
OIDC_SECRET="${OIDC_CLIENT_SECRET:-}"
OIDC_ISSUER="${OIDC_ISSUER:-}"

if [[ -z "$ADMIN_TOKEN" ]]; then
    echo "❌ GATEWAY_ADMIN_TOKEN env var is required"
    echo "   export GATEWAY_ADMIN_TOKEN=\$(openssl rand -hex 32)"
    exit 1
fi
if [[ -z "$POSTGRES_PWD" ]]; then
    echo "❌ POSTGRES_PASSWORD env var is required"
    echo "   export POSTGRES_PASSWORD=\$(openssl rand -hex 16)"
    exit 1
fi

echo "▶ 1. Create namespace $NAMESPACE"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

echo "▶ 2. Create secrets"
kubectl -n "$NAMESPACE" create secret generic agent-gateway-secrets \
    --from-literal=observ-postgres-password="$POSTGRES_PWD" \
    --from-literal=gateway-admin-token="$ADMIN_TOKEN" \
    --from-literal=openai-api-key="${OPENAI_API_KEY:-sk-placeholder}" \
    --from-literal=dashscope-api-key="${DASHSCOPE_API_KEY:-sk-placeholder}" \
    --from-literal=deepseek-api-key="${DEEPSEEK_API_KEY:-sk-placeholder}" \
    --from-literal=zhipu-api-key="${ZHIPU_API_KEY:-sk-placeholder}" \
    --from-literal=minimax-api-key="${MINIMAX_API_KEY:-sk-placeholder}" \
    --dry-run=client -o yaml | kubectl apply -f -

echo "▶ 3. Helm install agent-gateway $RELEASE_NAME"
OIDC_ARGS=""
if [[ -n "$OIDC_ID" && -n "$OIDC_SECRET" && -n "$OIDC_ISSUER" ]]; then
    OIDC_ARGS="--set oidc.enabled=true \
        --set oidc.issuer=$OIDC_ISSUER \
        --set oidc.clientId=$OIDC_ID \
        --set oidc.clientSecret=$OIDC_SECRET"
else
    OIDC_ARGS="--set oidc.enabled=false"
fi

helm upgrade --install "$RELEASE_NAME" "$CHART" \
    --namespace "$NAMESPACE" \
    --set image.repository=ghcr.io/tlbfpx/agent-gateway \
    --set image.tag="${IMAGE_TAG:-v0.3.0}" \
    --set secrets.existingSecret=agent-gateway-secrets \
    --set demo.enabled=true \
    --set ingress.enabled=true \
    --set ingress.hosts[0].host=$DOMAIN \
    --set ingress.hosts[0].paths[0].path=/ \
    --set ingress.tls[0].hosts[0]=$DOMAIN \
    --set ingress.tls[0].secretName=demo-tls \
    $OIDC_ARGS

echo "▶ 4. Wait for rollout"
kubectl -n "$NAMESPACE" rollout status deployment/"$RELEASE_NAME" --timeout=180s

echo "▶ 5. Smoke check"
sleep 5
HTTP_CODE=$(curl -sS -o /dev/null -w "%{http_code}" "https://$DOMAIN/status.json" || true)
if [[ "$HTTP_CODE" != "200" ]]; then
    echo "⚠️  /status.json returned $HTTP_CODE — check ingress + cert-manager"
    exit 1
fi

echo ""
echo "✅ Public demo is live!"
echo ""
echo "   URL:     https://$DOMAIN"
echo "   Status:  https://$DOMAIN/status.json"
echo "   Demo:    https://$DOMAIN/demo  (24h TTL auto-clean)"
echo "   Login:   https://$DOMAIN/login  (admin token in env GATEWAY_ADMIN_TOKEN)"
echo ""
echo "   First-action: tail logs"
echo "   kubectl -n $NAMESPACE logs -l app.kubernetes.io/name=agent-gateway -f"