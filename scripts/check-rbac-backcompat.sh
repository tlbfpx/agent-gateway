#!/usr/bin/env bash
# scripts/check-rbac-backcompat.sh
# spec §归档闸门 ④：D1 IAM/RBAC 深化 — 既有 AuthorizationServiceImplTest 零修改证据
#
# 用法：
#   ./scripts/check-rbac-backcompat.sh [BASE_REF]
#   BASE_REF 默认 master（本仓库主干分支名）。

set -euo pipefail

BASE_REF="${1:-master}"
TEST_FILE="gateway-infra-security/src/test/java/com/company/agentgateway/infra/security/AuthorizationServiceImplTest.java"

echo "==> Check 1: 既有测试文件相对 $BASE_REF 无删除（允许新增方法但已有方法体不能改）"
# 提取 BASE_REF 上既有测试方法标记，与当前 HEAD 比对
BASE_METHODS=$(git show "$BASE_REF":"$TEST_FILE" 2>/dev/null | grep -E "^\s+@Test|void [a-zA-Z_]+\(\)" | sort || true)
HEAD_METHODS=$(grep -E "^\s+@Test|void [a-zA-Z_]+\(\)" "$TEST_FILE" | sort || true)

if [ -z "$BASE_METHODS" ]; then
    echo "WARN: $BASE_REF 不存在或无既有测试方法（首次提交场景）"
else
    # 既有方法标记必须全部出现在 HEAD（不能删除既有方法）
    while IFS= read -r line; do
        if ! echo "$HEAD_METHODS" | grep -qF "$line"; then
            echo "FAIL: 既有测试行 '$line' 已不存在于 HEAD"
            exit 1
        fi
    done <<< "$BASE_METHODS"
    echo "OK: 所有既有测试方法在 HEAD 中存在"
fi

echo "==> Check 2: 既有方法体零修改（diff 仅含追加）"
DELETED_LINES=$(git diff "$BASE_REF"...HEAD -- "$TEST_FILE" | grep -c "^-[^-]" || true)
if [ "$DELETED_LINES" -ne 0 ]; then
    echo "FAIL: 检测到 $DELETED_LINES 行既有内容被删除/修改"
    exit 1
fi
echo "OK: 既有方法体零修改（0 删除行）"

echo "==> Check 3: 运行既有测试确认全绿"
if ! mvn -pl gateway-infra-security test -Dtest=AuthorizationServiceImplTest -q; then
    echo "FAIL: AuthorizationServiceImplTest 未全绿"
    exit 1
fi
echo "OK: AuthorizationServiceImplTest 全绿"

echo "==> All backcompat checks PASSED"
