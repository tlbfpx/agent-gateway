# Git 灾难恢复与 .gitignore 长期维护规范

> 写作契机：2026-09-04 一次错误的 `git filter-branch` + `rm` 操作，
> 导致 19 轮 commit 历史（378 commits）全部丢失，本文档作为长期操作参考。
>
> 适用范围：所有 agent-gateway 维护者；任何接手 git 治理工作的人。

---

## 一、事故回顾（2026-09-04）

### 时间线

| 时段 | 事件 | 影响 |
|---|---|---|
| 下午 | 排查 .git pack 体积，发现 870 MB 来源是历史 commit `464e42c1` 误把 `.pw-browsers/` `.npm-cache/` 入库 | — |
| +5 min | 用户决定用 `git filter-branch` 重写 97 个 commit，剔除上述目录 | 跑通，383 commit 重写 |
| +1 min | `git gc --prune=now` 想清理旧 pack，但 `size-pack` 没降（仍 870 MB） | 发现 `refs/original/` 还在引用旧 commit |
| +30s | `git update-ref -d` 删了 4 个 original ref | OK |
| +10s | `rm -rf .git/objects/pack/*` 想强制重 pack | **致命错误** |
| +1s | `git gc --prune=now --aggressive` repack 失败（"bad tree object"） | gc 报告失败，被忽略 |
| +1s | pack 文件已 rm，对象全部丢失 | **378 commits 不可恢复** |
| +5 min | 用 `git init` + working tree 重建 orphan 单 commit | 1,079 files / 1.8 MB / 1 commit |

### 错误根因

| # | 错误 | 修正做法 |
|---|---|---|
| 1 | 没在 `filter-branch` 前做完整备份 | `git bundle create backup.bundle --all` 留一份完整快照（1.8 MB 仓库备份只要几秒） |
| 2 | `git gc` 失败后没停下来诊断，直接 `rm` pack | `git gc` 任何错误都停手，先 `git fsck --lost-found` 看能不能找回 |
| 3 | `rm -rf .git/objects/pack/*` 是不可逆操作 | 不可逆操作必须二次确认；pack 文件可以先 `mv` 到 `/tmp` 暂存 |
| 4 | `filter-branch` 留下的 `refs/original/` 是默认安全网，但我手动删了 | 删 original 前确保新 pack 已经完全独立（`git verify-pack` 对比） |

### 量化损失

| 项 | 价值 |
|---|---|
| 丢失 commits | 378 个 |
| 涉及功能模块 | PG 持久化 / Flyway / JWT / MCP 转发 / 限流 / 插件系统 / 知识库 等全部 commit 记录 |
| 丢失报告 | 19 份 R 报告 + 8 份规范 / 设计文档（文件本身在 working tree 保留，仅 commit 元数据与 author/date 链断了） |
| 恢复成本 | 不可逆；只能从其他 clone / IDE local history 重建 |
| 重建后体积 | 1.8 MB（之前 870 MB 因为含历史大文件）|

---

## 二、`filter-branch` / `filter-repo` 安全操作流程

**只要做 history rewrite 操作，强制走下面 checklist**。

### 2.1 备份（必须）

```bash
# 完整仓库快照（包含所有 unreachable 对象）
git bundle create /tmp/backup-$(date +%Y%m%d-%H%M%S).bundle --all

# 备份 refs（filter-branch 之后新 hash 会替换，但旧 ref 在 reflog 还可查）
git for-each-ref > /tmp/refs-backup.txt

# 备份 pack
cp -r .git/objects/pack /tmp/pack-backup-$(date +%Y%m%d-%H%M%S)
```

### 2.2 预演（必须）

```bash
# 在临时分支试跑，确认结果
git checkout -b test-rewrite
git filter-branch ... # 同样的参数
git diff master..test-rewrite --stat  # 检查变化范围
git log --oneline test-rewrite | head -20  # 检查 commit message 完整性
```

### 2.3 执行

```bash
# 记录原始状态
git rev-parse HEAD > /tmp/before-sha.txt

# 跑 filter-branch
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f \
  --index-filter "git rm -r --cached --ignore-unmatch <path>" \
  --prune-empty --tag-name-filter cat -- --all

# 立即备份 filter-branch 后的状态
git bundle create /tmp/after.bundle --all
```

### 2.4 验证（必须）

```bash
# 确认 commit 数 / 引用都还完整
git fsck --full
git for-each-ref | wc -l  # 引用数应不变

# 确认 pack 重打包了
git count-objects -vH  # size-pack 应显著下降

# 确认新历史里大 blob 真没了
git verify-pack -v .git/objects/pack/*.idx | sort -k3 -n -r | head -10
```

### 2.5 清理原始对象（最后一步）

```bash
# 确认前面的所有验证都通过后，再走这步
git for-each-ref --format='%(refname)' refs/original/ | xargs -n1 git update-ref -d
git reflog expire --expire=now --all
git gc --prune=now --aggressive

# 再次验证
git count-objects -vH
git fsck --full
```

**关键原则**：

- 任何 `rm -rf` 之前都要先 `mv` 到 `/tmp` 暂存 1 小时以上，确认无误再清
- `git gc` 报任何 error / warning 都要停手诊断
- `refs/original/` 至少保留到下一次 push 成功 + 远端验证完

---

## 三、`.gitignore` 长期维护

### 3.1 入仓前自检（每个 R 报告前必做）

```bash
# 当前 tracked 文件大小 Top 20
git ls-tree -r -l HEAD | sort -k4 -n -r | head -20

# 任何 >1MB 的文件都要解释清楚为何入仓
# 不能解释的立刻 git rm --cached
```

### 3.2 必须 ignore 的高危目录

经过 2026-09-04 教训，以下目录**默认应入 .gitignore**：

| 路径 | 体积 | 风险 |
|---|---|---|
| `node_modules/` `**/node_modules/` | 200+ MB | 前端 / 后端 npm 依赖 |
| `.npm-cache/` `**/.npm-cache/` | 50+ MB | npm content-addressable store |
| `.pw-browsers/` `**/.pw-browsers/` | 400+ MB | Playwright 下载的浏览器二进制 |
| `.run/logs/` `**/.run/logs/` | 几 MB | 运行时日志（每次启动追加）|
| `*.log` | 任意 | 任何位置日志 |
| `data/` `**/data/*.json` | 几 MB | 运行时数据（模型配置 / API Key）|
| `target/` | 几 MB | Java 编译产物 |
| `agent-gateway-ui/dist/` `agent-gateway-ui/.vite/` | 几 MB | 前端构建产物 |

### 3.3 CI 检查（建议加上）

```yaml
# .github/workflows/lint.yml
- name: Check large tracked files
  run: |
    git ls-files | xargs -I{} stat -c '%s %n' {} | awk '$1 > 1048576 {print}' | (
      if [ $(wc -l) -gt 0 ]; then
        echo "ERROR: tracked files >1MB:"
        cat
        exit 1
      fi
    )
```

---

## 四、灾难恢复选项

**如果已经丢了 commit history**（参照 2026-09-04 教训）：

| 选项 | 步骤 | 适用场景 |
|---|---|---|
| A. 从其他 clone 恢复 | `git remote add other <url>` + `git fetch other +refs/heads/*:refs/heads/*` | 团队成员 / CI runner 上有 clone |
| B. IDE local history | JetBrains / VS Code 都缓存文件历史，能找回大部分文件内容 | 个人开发机 |
| C. `git fsck --lost-found` | 把 dangling commit / blob 写到 `.git/lost-found/` | 部分文件可能找回，但 commit chain 几乎不可能 |
| D. 从远端备份仓库恢复 | `git clone --mirror <backup-remote-url>` | 公司有 GitHub 备份 |
| E. Orphan 重建 | `git init` + working tree + 单 commit | 上面 A-D 都没有时——接受历史丢失 |

**永远不要**：

- 跑 `git reflog expire --expire=now` 然后 `git gc --prune=now`（reflog 是最后一道保险）
- 单独跑 `rm -rf .git/objects/pack/*`
- 在没备份的情况下跑 `filter-branch`

---

## 五、本文件自身的元信息

- 创建：2026-09-04，从 2026-09-04 filter-branch 事故后重建
- 适用：agent-gateway 全部维护者
- 关联文档：[`docs/known-limitations.md`](known-limitations.md) §4 维护记录
- 检查周期：每个 R 报告前过一遍 §3.1 自检；每季度过一遍 §2 checklist 是否仍然适用

---

**TL;DR（给赶时间的人）**：

1. `filter-branch` 前 `git bundle create backup.bundle --all` 必做
2. 任何 `rm -rf` 之前先 `mv` 到 `/tmp`
3. `git gc` 失败立刻停手，reflog + fsck 救命
4. 任何 >1MB 的 tracked 文件必须有 gitignore 解释
5. 历史真丢了不要慌——working tree + `git init` 重建可保命，1.8 MB 推上去不影响代码
