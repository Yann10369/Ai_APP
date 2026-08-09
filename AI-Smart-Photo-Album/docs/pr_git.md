# Skill: 自动提交代码并申请 PR（按模块归属）

> 本仓库采用模块化分工开发：每个模块由不同成员负责。智能体读到本文档后，应按下面的工作流，**只对当前用户归属模块下的文件**执行 `commit / push / PR`。

| 模块        | 路径前缀    | 典型负责人    |
|-------------|-------------|---------------|
| frontend    | `frontend/` | frontend-lead |
| backend     | `backend/`  | backend-lead  |
| model       | `model/`    | model-lead    |
| docs        | `docs/`     | docs-lead     |
| root        | 仓库根级    | repo-maintainer |

---

## 1. 模块归属校验（必须）

按以下顺序识别本会话归属模块，**确认前禁止任何 `git add` / `git commit`**：

1. **用户消息显式声明**（如"提交我的 frontend 代码"）
2. **仓库根 `CODEOWNERS` / `OWNERS`** 文件映射
3. **环境变量 `AGENT_OWNED_MODULES`**（逗号分隔，如 `frontend,docs`）
4. **询问用户**：以上都没有时停下提问

   > "本次改动属于哪个模块？可选：frontend / backend / model / docs / root。"

---

## 2. 模块白名单

```
frontend   →   frontend/**
backend    →   backend/**
model      →   model/**
docs       →   docs/**
root       →   README.md, .gitignore, LICENSE, CODEOWNERS, OWNERS
```

白名单外文件 → **跳过**本次提交，列出路径让用户决定（拆分 / 移交 / 撤回）。

---

## 3. 工作流

### Step 1. 状态检查

```bash
git status
git branch --show-current
git log --oneline -5
```

向用户简要汇报：当前分支、改动列表、最近提交。

### Step 2. 改动过滤

```bash
git diff --stat
git diff

# 按归属模块筛选
git status --porcelain | awk '{print $2}' | grep -E '^<module>/'
```

越界文件 → 报告用户并跳过，**不进入本次提交**。

### Step 3. 按模块暂存 & 提交

```bash
# 只 add 归属模块内的文件，禁止 git add . / -A
git add <module>/<file1> <module>/<file2> ...

git status   # 再确认 staged 全部位于 <module>/ 下

git commit -m "<type>(<module>): <subject>

<body: 改动目的 / 影响范围 / 关联 issue>

模块: <module>
负责人: <user-name>"
```

> ⚠️ **不要**在 commit message 中添加 `Co-Authored-By: Claude ...` 之类的 AI 署名 trailer。  
> 所有提交以当前 git 用户（`<user-name>`）单独作者身份提交。

提交信息格式（Conventional Commits + 模块前缀）：

| 前缀                | 用途                           |
|---------------------|--------------------------------|
| `feat(<module>):`   | 新功能                         |
| `fix(<module>):`    | 修复 bug                       |
| `docs(<module>):`   | 仅文档                         |
| `refactor(<module>):` | 重构（不改行为）             |
| `test(<module>):`   | 测试                           |
| `chore(root):`      | 构建/工具/依赖（仅 root 模块） |

### Step 4. 推送

```bash
git push origin <current-branch>
```

失败处理：

| 错误                       | 处理                                    |
|----------------------------|-----------------------------------------|
| non-fast-forward           | `git fetch` 后与用户确认 rebase / merge |
| 鉴权失败                   | 提示执行 `gh auth login`                |
| 分支受保护                 | 走 PR 流程                              |

### Step 5. 创建 Pull Request

仅在非默认分支（或用户明确要求）执行。

**默认使用 GitHub REST API**（不依赖 `gh` CLI，适用于任何干净环境）：

1. **获取 token**：从 git credential manager 读取已保存的 GitHub 凭证：

   ```bash
   git credential fill <<< "url=https://github.com/<owner>/<repo>.git"
   ```

   输出中 `password=` 后面的值即为 token（`gho_` / `ghp_` / `ghs_` 开头）。

2. **构造 JSON payload**（用 Python 安全转义 body 中的换行与引号）：

   ```bash
   BODY='<按下方模板填写>'
   PAYLOAD=$(python -c "import json,sys; print(json.dumps({
     'title': '<type>(<module>): <short title>',
     'head': '<current-branch>',
     'base': 'main',
     'body': sys.argv[1]
   }))" "$BODY")
   ```

3. **POST 创建 PR**：

   ```bash
   curl -s -X POST \
     -H "Authorization: token <TOKEN>" \
     -H "Accept: application/vnd.github+json" \
     -H "X-GitHub-Api-Version: 2022-11-28" \
     -H "Content-Type: application/json" \
     -d "$PAYLOAD" \
     https://api.github.com/repos/<owner>/<repo>/pulls
   ```

4. **解析响应**：返回 JSON 中 `html_url` 字段即为 PR 链接，例如：

   ```
   "html_url": "https://github.com/<owner>/<repo>/pull/1"
   ```

5. **指定 reviewer**（可选）：创建后追加调用：

   ```bash
   curl -s -X POST \
     -H "Authorization: token <TOKEN>" \
     -H "Accept: application/vnd.github+json" \
     -d '{"reviewers":["<module-owner-login>"]}' \
     https://api.github.com/repos/<owner>/<repo>/pulls/<pr-number>/requested_reviewers
   ```

**可选快捷方式**：若环境已安装 `gh` CLI 且 `gh auth status` 已登录，可直接使用：

```bash
gh pr create \
  --base main \
  --head <current-branch> \
  --title "<type>(<module>): <short title>" \
  --body "<PR body>" \
  --reviewer <module-owner-login>
```

PR body 模板：

```markdown
## 改动模块
- 模块: `<module>`
- 负责人: <user-name>

## 改动说明
- <改动点 1>
- <改动点 2>

## 改动文件
- <module>/<file1>
- <module>/<file2>

## 跨模块影响
- [ ] 无
- [ ] 有：___________

## 测试
- [ ] 已通过本模块 lint / 测试
- [ ] 已人工验证关键路径

## 关联 Issue
Closes #<id>  （如有）
```

> ⚠️ PR body 中**不要**添加 AI 工具署名（如 `🤖 Generated with Claude Code`）。  
> 所有 PR 以提交人本人身份创建。

### Step 6. 回报

向用户输出：

1. commit SHA
2. 远端分支
3. 模块归属（再确认）
4. PR 链接
5. 跳过的越界文件（如有）

---

## 4. 红线（必须得到用户明确授权）

- `git push --force` / `git push -f`
- `git reset --hard`
- `git commit --amend`（已推送过的提交）
- 删除远端分支
- 跳过 PR review（仓库有分支保护时）
- 提交任何含密钥 / token / 密码 / 个人信息 的文件

---

## 5. 跨模块改动

`git status` 同时含多个模块改动时，**默认拒绝**一次提交：

- 建议每个模块各开一个分支、各提一个 PR
- 仅当用户证明是 `repo-maintainer`（`AGENT_OWNED_MODULES` 含全部模块）才允许例外，且必须在 PR body "跨模块影响" 中**逐项说明**