#!/usr/bin/env python3
"""
seed-all-pages.py — 全页面测试数据种子（幂等，可重复执行）

通道：
  1. REST（网关 8080）：角色/绑定/预算/API Key/Webhook/告警规则
  2. PG 直插（TimescaleDB 5433）：计费明细/审计/调用链/指标/告警/工作流运行

用法：python3 scripts/seed-all-pages.py
环境变量：GW_BASE / GW_API_KEY / GW_TENANT / PG_CONTAINER
"""
import json
import os
import random
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("GW_BASE", "http://localhost:8080")
API_KEY = os.environ.get("GW_API_KEY", "sk-demo-primary-0001")
TENANT = os.environ.get("GW_TENANT", "primary")
PG = os.environ.get("PG_CONTAINER", "agentgateway-observability-db")

random.seed(42)
FAILURES = []
NOW = datetime.now(timezone.utc)


def call(method, path, body=None, expect=(200, 201, 204, 409)):  # 409=幂等重复(已存在)
    """REST 调用（失败不中断，收集报告）。"""
    req = urllib.request.Request(
        BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 "X-API-Key": API_KEY, "X-Tenant-Id": TENANT})
    try:
        with urllib.request.urlopen(req) as r:
            status, payload = r.status, r.read()
        print(f"  [{'OK' if status in expect else 'BAD'}] {method} {path} -> {status}")
        return status in expect, (json.loads(payload) if payload else None)
    except urllib.error.HTTPError as e:
        code = e.code
        e.read()
        if code in expect:  # 幂等重复（如绑定已存在 409）视为成功
            print(f"  [OK] {method} {path} -> {code} (idempotent)")
            return True, None
        print(f"  [ERR] {method} {path} -> {code}")
        FAILURES.append(f"{method} {path} -> {code}")
    except Exception as e:
        print(f"  [ERR] {method} {path} -> {e}")
        FAILURES.append(f"{method} {path} -> {e}")
    return False, None


def psql(sql, label=""):
    """docker exec psql 执行（observability PG）。"""
    r = subprocess.run(
        ["docker", "exec", PG, "psql", "-U", "agentgateway", "-d", "agentgateway",
         "-v", "ON_ERROR_STOP=1", "-c", sql], capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  [ERR] psql {label}: {r.stderr.strip()[:200]}")
        FAILURES.append(f"psql {label}")
    else:
        print(f"  [OK] psql {label}")
    return r.returncode == 0


MODELS = [("minimax-abab6.5s-chat", 0.001, 0.002),
          ("deepseek-chat", 0.0005, 0.001),
          ("qwen-max", 0.008, 0.024)]
USERS = ["alice", "bob", "carol", "dave", "seed-alice"]
AGENTS = ["echo-agent", "hr-agent", "search-agent"]

print("== 1. RBAC：角色（Agent/Model/Skill 三型权限） ==")
call("POST", "/v1/admin/roles", {
    "id": "role-platform-admin", "name": "平台管理员", "description": "全量权限（种子数据）",
    "permissions": [
        {"agentName": "echo-agent", "allowedSkills": ["run", "debug"]},
        {"agentName": "hr-agent", "allowedSkills": ["query"]},
        {"models": ["minimax-abab6.5s-chat", "deepseek-chat"]}]})
call("POST", "/v1/admin/roles", {
    "id": "role-developer", "name": "开发者", "description": "开发调试权限（种子数据）",
    "permissions": [
        {"agentName": "echo-agent", "allowedSkills": ["run"]},
        {"models": ["deepseek-chat"]}]})
call("POST", "/v1/admin/roles", {
    "id": "role-viewer", "name": "只读访客", "description": "只读（种子数据）",
    "permissions": [{"models": ["minimax-abab6.5s-chat"]}]})
call("POST", "/v1/admin/roles", {
    "id": "role-skill-ops", "name": "技能运维", "description": "Skill 级权限示例（种子数据）",
    "permissions": [{"agentName": "echo-agent", "skillName": "search"}]})

print("== 2. RBAC：用户绑定 ==")
for user, role in [("alice", "role-platform-admin"), ("bob", "role-developer"),
                   ("carol", "role-viewer"), ("dave", "role-skill-ops")]:
    call("POST", f"/v1/admin/users/{user}/roles", {"roleId": role})

print("== 3. 预算（成本/预算页） ==")
call("POST", "/v1/admin/billing/budgets", {
    "type": "MONEY", "dailyLimit": 500, "monthlyLimit": 2000,
    "alertThresholdPct": 80, "suspendAction": "ALERT"})

print("== 4. API Key（key 管理页） ==")
for u in ["seed-alice", "seed-bob", "seed-carol"]:
    call("POST", "/v1/admin/api-keys", {
        "tenant": TENANT, "user": u, "agentGrants": ["echo-agent"],
        "allowedModels": ["minimax-abab6.5s-chat", "deepseek-chat"]})

print("== 5. Webhook 订阅（Webhook 页） ==")
call("POST", "/v1/admin/webhooks", {
    "url": "https://hooks.example.com/agent-gateway/main",
    "secret": "whsec-seed-001",
    "events": ["role.changed", "budget.exceeded", "alert.fired"]})
call("POST", "/v1/admin/webhooks", {
    "url": "https://hooks.example.com/agent-gateway/backup",
    "secret": "whsec-seed-002", "events": ["audit.appended"]})

print("== 6. 告警规则（告警中心页） ==")
call("POST", "/v1/admin/alerts/rules", {
    "name": "P95 延迟过高", "metricName": "gateway.latency.p95",
    "operator": "GT", "threshold": 2000, "windowSeconds": 300, "severity": "critical"})
call("POST", "/v1/admin/alerts/rules", {
    "name": "错误率飙升", "metricName": "gateway.error.rate",
    "operator": "GT", "threshold": 0.05, "windowSeconds": 300, "severity": "warning"})

print("== 7. PG：计费明细（30 天 × 3 模型，成本中心/预算/Dashboard） ==")
rows = []
for d in range(30):
    day = NOW - timedelta(days=d)
    for m, pin, pout in MODELS:
        for k in range(random.randint(2, 6)):
            tin, tout = random.randint(200, 4000), random.randint(100, 2000)
            cost = round(tin * pin + tout * pout, 6)
            rows.append("('seed-b-%d-%s-%d','%s','%s','%s','%s','%s',%d,%d,%s,%s,%s)" % (
                d, m[:6], k, TENANT, random.choice(USERS), m,
                random.choice(AGENTS), day.isoformat(), tin, tout, pin, pout, cost))
psql("DELETE FROM billing_records WHERE record_id LIKE 'seed-b-%'; "
     "INSERT INTO billing_records (record_id,tenant_id,user_id,model_id,agent_name,"
     "ts,tokens_in,tokens_out,unit_price_in,unit_price_out,cost) VALUES "
     + ",".join(rows) + ";", "billing_records x%d" % len(rows))

print("== 8. PG：审计事件（7 天多类型，审计页/Dashboard） ==")
ETYPES = ["LOGIN", "API_KEY_CREATE", "API_KEY_DELETE", "SESSION_CHAT",
          "RATE_LIMIT_EXCEEDED", "RBAC_DENIED", "GRANT_CREATE", "MODEL_CONFIG_UPDATE"]
RES = ["model", "session", "rbac-role", "api-key"]
arows = []
for i in range(80):
    t = NOW - timedelta(minutes=random.randint(0, 7 * 24 * 60))
    failed = random.random() < 0.15
    result = "FAILURE" if failed else "SUCCESS"
    action = "denied" if failed else "chat"
    err = "'seed denied sample'" if failed else "NULL"
    arows.append("('seed-audit-%d','%s','%s','HUMAN','%s','%s','%s','%s','%s','%s',%s,'%s')" % (
        i, TENANT, random.choice(USERS + ["admin"]), random.choice(ETYPES),
        t.isoformat(), random.choice(RES), random.choice(MODELS)[0],
        action, result, err, t.isoformat()))
psql("DELETE FROM audit_events WHERE event_id LIKE 'seed-audit-%'; "
     "INSERT INTO audit_events (event_id,tenant,actor,actor_type,event_type,ts,"
     "resource_type,resource_id,action,result,error_message,start_time) VALUES "
     + ",".join(arows) + ";", "audit_events x%d" % len(arows))

print("== 9. PG：调用链 spans（追踪页） ==")
srows = []
for i in range(24):
    t = NOW - timedelta(minutes=random.randint(0, 50))  # 默认查询窗 range=1h
    dur = random.randint(20, 8000)
    ok = random.random() < 0.85
    name = "POST /v1/chat/stream" if i % 2 == 0 else "chat.generate"
    status = "UNSET" if ok else "ERROR"
    attrs = json.dumps({"tenant_id": TENANT, "model": random.choice(MODELS)[0],
                        "agent_name": random.choice(AGENTS)})
    srows.append("('seed-trace-%04x','sp-%06x',NULL,'%s','SERVER','%s','%s',%d,'%s','%s'::jsonb,'[]'::jsonb)" % (
        i, i, name, t.isoformat(), (t + timedelta(milliseconds=dur)).isoformat(),
        dur, status, attrs))
psql("DELETE FROM spans WHERE trace_id LIKE 'seed-trace-%'; "
     "INSERT INTO spans (trace_id,span_id,parent_span_id,name,kind,start_time,"
     "end_time,duration_ms,status,attributes,events) VALUES "
     + ",".join(srows) + ";", "spans x%d" % len(srows))

print("== 10. PG：指标序列（Dashboard/限流页） ==")
mrows = []
for i in range(48):  # 24h，每 30min 一点
    t = NOW - timedelta(minutes=30 * i)
    tags = json.dumps({"tenant_id": TENANT, "model": random.choice(MODELS)[0]})
    mrows.append("('llm.tokens','%s'::jsonb,'%s',%d)" % (
        tags, t.isoformat(), random.randint(500, 20000)))
    mrows.append("('gateway.requests','%s'::jsonb,'%s',%d)" % (
        tags, t.isoformat(), random.randint(10, 300)))
psql("DELETE FROM metrics_samples WHERE ts > now() - interval '2 days' "
     "AND tags ->> 'tenant_id' = '%s'; "
     "INSERT INTO metrics_samples (metric_name,tags,ts,value) VALUES %s;"
     % (TENANT, ",".join(mrows)), "metrics_samples x%d" % len(mrows))

print("== 11. PG：告警实例（告警中心：firing + resolved） ==")
alert_rows = []
for i, (sev, state) in enumerate([("critical", "firing"), ("warning", "firing"),
                                  ("warning", "resolved"), ("info", "resolved")]):
    t = NOW - timedelta(hours=i + 1)
    labels = json.dumps({"tenant": TENANT, "metric": "gateway.latency.p95"})
    resolved_at = "NULL" if state == "firing" else "'%s'" % t.isoformat()
    alert_rows.append(
        "('seed-alert-%d','seed-rule-%d','%s','%s','seed:rule:metric:%d','%s'::jsonb,"
        "'%s','%s',%d,%.1f,2000.0,NULL,NULL,%s,'%s')" % (
            i, i, sev, state, i, labels, t.isoformat(), t.isoformat(),
            random.randint(1, 9), random.uniform(1000, 5000), resolved_at,
            t.isoformat()))
psql("DELETE FROM alerts WHERE id LIKE 'seed-alert-%'; "
     "INSERT INTO alerts (id,rule_id,severity,state,dedup_key,labels,first_fired_at,"
     "recently_triggered_at,trigger_count,observed_value,threshold,claimed_by,note,"
     "resolved_at,start_time) VALUES " + ",".join(alert_rows) + ";",
     "alerts x%d" % len(alert_rows))

print("== 12. PG：工作流运行（Workflow 页） ==")
wrows = []
for i, status in enumerate(["COMPLETED"] * 6 + ["RUNNING"] * 2 + ["FAILED"] * 2):
    t = NOW - timedelta(hours=i * 3)
    finished = ("'%s'" % (t + timedelta(minutes=random.randint(1, 9))).isoformat()) \
        if status != "RUNNING" else "NULL"
    wrows.append("('seed-run-%03d','seed-flow-%d','%s','%s',%s,'%s')" % (
        i, i % 3, status, t.isoformat(), finished, t.isoformat()))
psql("DELETE FROM workflow_runs WHERE run_id LIKE 'seed-run-%'; "
     "INSERT INTO workflow_runs (run_id,workflow_name,status,started_at,finished_at,"
     "start_time) VALUES " + ",".join(wrows) + ";", "workflow_runs x%d" % len(wrows))

print()
if FAILURES:
    print("种子完成，但 %d 项失败：" % len(FAILURES))
    for f in FAILURES:
        print("  -", f)
    sys.exit(1)
print("✓ 全部种子数据写入完成")
