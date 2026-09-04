#!/usr/bin/env python3
"""
seed-demo-data.py — 通过真实 API 灌演示数据

数据通道（全部走后端真实链路，非直改内存）：
  1. POST /v1/chat        — 多租户 × 多频次对话流（审计产生时间分布）
  2. POST /v1/admin/alerts/rules — 3 条告警规则
  3. POST /v1/admin/rbac/policies — 3 条策略规则

用法: python3 scripts/seed-demo-data.py [对话轮数，默认 30]
"""
import json
import random
import sys
import time
import urllib.request

BASE = 'http://localhost:8080'
KEYS = json.load(open('data/api-keys.json'))
ACTIVE = [k for k in KEYS if not k.get('revoked')]

PROMPTS = [
    '帮我总结这段文本的要点',
    '写一个 Python 快速排序',
    '翻译成英文：今天天气不错',
    '解释一下什么是向量数据库',
    '生成一个 SQL 查询：统计每日调用量',
    '用一句话介绍 A2A 协议',
    '帮我起草一封周报邮件',
    '这段代码有什么 bug？def add(a, b): return a - b',
    '列出三种常见的限流算法',
    '把下面的 JSON 转成 YAML',
]


def chat(key: str, tenant: str, model: str, prompt: str) -> bool:
    req = urllib.request.Request(
        f'{BASE}/v1/chat',
        data=json.dumps({'prompt': prompt, 'model': model}).encode(),
        headers={
            'Content-Type': 'application/json',
            'X-API-Key': key,
            'X-Tenant-Id': tenant,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status == 200
    except Exception as e:
        print(f'  chat fail ({tenant}): {e}')
        return False


def admin_post(key: str, tenant: str, path: str, body: dict):
    req = urllib.request.Request(
        f'{BASE}{path}',
        data=json.dumps(body).encode(),
        headers={
            'Content-Type': 'application/json',
            'X-API-Key': key,
            'X-Tenant-Id': tenant,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f'  admin fail {path}: {e}')
        return None


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    main_key = ACTIVE[0]['key']
    main_tenant = ACTIVE[0]['tenant']
    main_model = ACTIVE[0].get('allowedModels', ['minimax-abab6.5s-chat'])[0]

    print(f'== 灌对话流量（{rounds} 轮，多租户错频）==')
    ok = fail = 0
    for i in range(rounds):
        # 主租户 60% 流量，其余租户分摊（制造 Top 分布）
        k = random.choices(ACTIVE, weights=[60] + [10] * (len(ACTIVE) - 1))[0]
        model = k.get('allowedModels', [main_model])[0]
        if chat(k['key'], k['tenant'], model, random.choice(PROMPTS)):
            ok += 1
        else:
            fail += 1
        # 错开间隔让审计时间有分布（快速模式 0.3s，约 rounds*0.3s 总时长）
        time.sleep(0.3)
    print(f'  done: ok={ok} fail={fail}')

    print('== 灌告警规则（3 条）==')
    for rule in [
        {'name': '错误率超过 5%', 'metric': 'error_rate', 'threshold': 0.05, 'windowSec': 300,
         'severity': 'critical', 'channels': ['feishu', 'email'], 'enabled': True,
         'description': '5 分钟窗口错误率持续 > 5% 触发'},
        {'name': 'P95 延迟 > 3 秒', 'metric': 'p95_latency', 'threshold': 3000, 'windowSec': 600,
         'severity': 'warning', 'channels': ['feishu'], 'enabled': True,
         'description': 'P95 延迟持续超 3 秒影响体验'},
        {'name': '日成本激增 50%', 'metric': 'cost_spike', 'threshold': 1.5, 'windowSec': 0,
         'severity': 'warning', 'channels': ['email'], 'enabled': False,
         'description': '与昨日同期对比'},
    ]:
        r = admin_post(main_key, main_tenant, '/v1/admin/alerts/rules', rule)
        print(f'  {"✓" if r else "✗"} {rule["name"]}')

    print('== 灌策略规则（3 条）==')
    for pol in [
        {'name': 'admin 完整权限', 'priority': 1000,
         'subject': {'kind': 'role', 'value': 'admin'},
         'resource': {'kind': '*', 'pattern': '*'},
         'action': '*', 'effect': 'allow', 'enabled': True,
         'description': '管理员对所有资源的所有动作'},
        {'name': 'ops 读权限', 'priority': 500,
         'subject': {'kind': 'role', 'value': 'ops'},
         'resource': {'kind': '*', 'pattern': '*'},
         'action': 'read', 'effect': 'allow', 'enabled': True,
         'description': '运维只读'},
        {'name': 'developer 模型白名单', 'priority': 400,
         'subject': {'kind': 'role', 'value': 'developer'},
         'resource': {'kind': 'model', 'pattern': 'minimax-abab6.5s-chat'},
         'action': 'invoke', 'effect': 'allow', 'enabled': True,
         'description': '开发者仅可调用白名单模型'},
    ]:
        r = admin_post(main_key, main_tenant, '/v1/admin/rbac/policies', pol)
        print(f'  {"✓" if r else "✗"} {pol["name"]}')

    print('== 完成 ==')


if __name__ == '__main__':
    main()
