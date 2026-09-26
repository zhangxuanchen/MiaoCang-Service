# 喵藏客户端对接文档（喵文件同步 API）

> 服务端：MiaoCang-Service（Spring Boot，默认 http://localhost:8080）
> 客户端：MiaoCang-Client（macOS 桌面猫），本地书库目录格式与服务端一致
> 更新日期：2026-09-21

## 1. 能力边界（先读这个）

- **同步范围 = 仅内容文章 md**（且只住 `origin/` 下）。`INDEX.md`、`.catalog.json`、知识点 `points/`、`reading/`、`study/` 等均为服务端内部产物，**不参与同步**，接口会直接拒绝这些路径。
- **类目（知识分类）客户端只读**：只能拉取（`/types`），不能创建/修改/删除。类目由服务端维护（共享预设 + 每只喵自有）。
- **多用户隔离**：所有接口走登录态，只能操作自己养的喵；动别人的喵一律 403。
- 服务端书库是 git 仓库，每次 `apply`/`push` 自动 commit（可配远端推送备份），客户端无需关心 git。

## 2. 快速开始

```
1. POST /api/auth/login            → { token }
2. GET  /api/client/hello          → 握手：协议版本 + 当前用户 + 喵列表（带待裁决数）
3. POST /api/client/cats/{catId}/sync/pull     → 全量内容 + 与基准的 diff + 类目
4. 客户端本地 diff → 冲突交给用户裁决 / 其余直接落盘
5. POST /api/client/cats/{catId}/sync/push     → 把本地新增/修改/删除提交上来
6. 记录每个文件的 sha256 作为下次同步基准
```

## 3. 认证

### 登录

```
POST /api/auth/login
Content-Type: application/json

{"username": "admin", "password": "admin123"}
```

响应：

```json
{
  "token": "<64位UUID双拼>",
  "expiresAt": "2026-09-28T09:00:00",
  "user": { "id": 1, "username": "admin", "displayName": "admin" }
}
```

- token 有效期 **7 天**（响应带精确 `expiresAt`），服务端重启不掉线；过期/无效返回 `401 {"error":"..."}`。

### 注册（客户端可内置，无需打开网页）

```
POST /api/auth/register
Content-Type: application/json

{"username": "alice", "password": "至少6位"}
```

- 成功：`{"ok": true, "message": "注册成功，请登录"}` → 引导用户走登录。
- 失败（400）：用户名 2~30 个字符；只允许中文、字母、数字、下划线、连字符；密码至少 6 位；用户名已被占用。
- 用户名即该用户的专属工作区根目录名（注册后自动创建空间，首只喵的工作区形如 `alice/cat-5`）。

### 启动时校验 token（免重复登录）

```
GET /api/auth/me
Authorization: Bearer <token>
```

- token 有效 → `200 { "id": 1, "username": "admin", "displayName": "admin" }`，客户端直接进入已登录状态；
- 失效 → `401`，清掉本地 token 走登录流程。

### 登出（可选）

```
POST /api/auth/logout
Authorization: Bearer <token>
```

服务端删除该 token（之后即使 token 泄露也已失效），返回 `{"ok": true}`。客户端同时清掉本地 token 与所有喵的 base 基准。

### 携带方式

所有后续请求加头：

```
Authorization: Bearer <token>
```

**客户端任何地方都不要用 `<a href>`/`window.open` 直接访问需要鉴权的接口**——浏览器导航不带 Authorization 头，必 401。统一用 HTTP 客户端（fetch/URLSession/requests）带上头。

收到 401 → 清掉本地 token 回登录页。

## 4. 文件格式规范（与服务端 fetcher 完全一致）

### 目录结构（每只喵一个工作区，原始文档统一住 `origin/` 下）

```
origin/                                      ← 原始文档根目录（固定名，所有内容 md 都在这里）
  <分类名>/                                   ← 二级 = 分类名（即类目）
    2026-09-20_互通验证文章A.md                ← 文件名 = yyyy-MM-dd_<标题>.md
    2026-09-20_红烧肉的家常做法.md
points/  reading/  study/  .context/ …        ← 服务端内部产物/记忆，不参与同步
```

> **路径口径：一律三级 `origin/<分类>/<文件>.md`。**
> 这是 `manifest` / `pull` / `contents` 返回的 path 形式，也是客户端 push 时应发送的形式。
> 为兼容旧客户端，服务端也接受两级 `<分类>/<文件>.md`，并会**自动归位**成 `origin/<分类>/<文件>.md`（响应里也统一返回归位后的三级形式）——但新客户端请直接发三级，避免 base 里的 key 与服务端对不上。

### front-matter 格式

每个内容 md 的头部：

```markdown
---
title: "标题的 JSON 字符串"
url: "https://example.com/article"        # 网页采集才有；纯文本/文件可为空
saved: "2026-09-20 15:04:05"              # 保存时间 yyyy-MM-dd HH:mm:ss
category: "技术研发"                       # 分类名（= origin/ 下的那一级目录名）
kind: "web"                               # web/text/file
summary: "摘要"
source: "原始文件名"                       # 仅 file 类有
---
（正文）
```

- 文件名安全化规则：去 `\ / : * ? " < > | \r \n \t`，去首尾 `.` 与空白，截断 50 字符，空则 `untitled`；同名冲突加 `_2`、`_3` 后缀。
- front-matter 缺失不会导致收编失败：`title` 从文件名兜底、`category` 从目录名兜底。

### 合法路径规则（服务端校验，违反即 400/rejected）

- **推荐三级**：`origin/<分类>/<文件>.md`；兼容两级 `<分类>/<文件>.md`（服务端自动归位到 `origin/` 下）。其他层级一律非法（四层以上，或 `origin/` 开头的两层）
- 后缀必须 `.md`（大小写不敏感）
- 拒绝：`..`、反斜杠、以 `.` 或 `_` 开头的任何路径段（隐藏/内部目录）、`INDEX.md`
- `origin` 是内容根目录的固定名，不可当作分类名（否则与三级形式歧义）
- 服务端内部目录不可用作分类：`points`、`reading`、`study`

## 5. 接口详述

统一前缀：`/api/client/cats/{catId}/sync`（`catId` = 喵的 id）。鉴权失败 401；`catId` 不存在 404；不是自己的喵 403，body 为 `{"error":"..."}`。

### 5.1 内容拉取（推荐入口）：`POST /pull`

全量内容（含正文）+ 与客户端基准的 diff + 冲突标记 + 类目清单，**一次请求拿齐**。

```
POST /api/client/cats/{catId}/sync/pull
Authorization: Bearer <token>
Content-Type: application/json

（body 可空 = 全量模式；带 base = 增量模式）
{"base": {"origin/技术研发/2026-09-20_旧文章.md": "a1b2c3..."}}
```

响应：

```json
{
  "cat": { "id": 1, "name": "小肥", "workspacePath": "admin/cat-1" },
  "contents": [
    { "path": "origin/技术研发/2026-09-20_文章.md",
      "sha256": "…64位…", "bytes": 1234, "modifiedAt": "2026-09-21T01:00:00Z",
      "content": "---\ntitle: ...\n---\n正文" }
  ],
  "added":    ["origin/收集箱/新文章.md"],
  "changed":  ["origin/技术研发/被改过的文章.md"],
  "removed":  ["origin/美食烹饪/服务端已删.md"],
  "conflicts": [
    { "path": "origin/技术研发/被改过的文章.md",
      "clientBaseSha": "你上次记的sha",
      "serverSha256": "服务端现在的sha",
      "serverContent": "服务端当前全文" }
  ],
  "types": [ … 类目清单，同 5.4 … ],
  "note": "三向冲突判定说明…"
}
```

| 字段 | 含义 |
|---|---|
| `contents` | 全部内容文件（含 `content` 正文），无论有无 base 都返回；`modifiedAt` 为 **UTC 时间（ISO-8601 带 `Z`）**，客户端展示时自行转本地时区 |
| `added` | 带 base 时：服务端有、你的 base 没有 → 服务端新增 |
| `changed` | 带 base 时：服务端版本 ≠ 你的 base → 服务端在窗口外改过，**是潜在冲突** |
| `removed` | 带 base 时：你的 base 有、服务端没有 → 服务端已删除（客户端可同步清理本地） |
| `conflicts` | `changed` 的明细：服务端当前 sha + 服务端全文，供比对/合并 |

**三向冲突判定**（客户端拿到 pull 结果后对每个 `changed` 文件做判断）：

| 本地文件 vs base | 服务端 vs base | 结论 |
|---|---|---|
| 本地 == base | 服务端 != base | 只有服务端改了 → **直接用服务端版本覆盖本地**（无需打扰用户） |
| 本地 != base | 服务端 == base | 只有本地改了 → **直接 push**（带本地原 baseSha，必过冲突闸） |
| 本地 != base | 服务端 != base | **双边都改过 = 真冲突** → 列清单让用户选：用本地覆盖远程（push）/ 用远程覆盖本地（落盘服务端版本）/ 人工合并后 push 合并稿 |
| 本地 == base | 服务端 == base | 无变化 |

> 判定需要本地文件 sha：客户端对本地同名文件算 SHA-256 与 base 比较。不带 base（首次全量）时 `added` 即全部文件。

### 5.2 内容提交：`POST /push`

```
POST /api/client/cats/{catId}/sync/push
Content-Type: application/json

{ "files": [
    { "path": "origin/技术研发/2026-09-21_新文章.md", "content": "…全文…", "baseSha": null },
    { "path": "origin/技术研发/2026-09-20_我改过的.md", "content": "…修改稿…", "baseSha": "<本地记录的原sha>" },
    { "path": "origin/美食烹饪/2026-09-16_本地删掉的.md", "delete": true, "baseSha": "<本地记录的原sha>" }
] }
```

响应：

```json
{
  "accepted": [ { "path": "…", "accepted": true, "sha256": "<落盘后新基准>" }
              | { "path": "…", "accepted": true, "deleted": true } ],
  "conflicts": [ { "path": "…", "conflict": true, "serverSha256": "…", "serverContent": "服务端全文" } ],
  "rejected": [ { "path": "…", "rejected": true, "reason": "非法路径…/超限…" } ],
  "apply": { "total": 18, "addedCount": 1, "removedCount": 0, "added": [...], "removed": [...] },
  "committed": true,
  "note": "存在冲突：…已保留服务端内容未覆盖…"
}
```

行为细则：

- **冲突闸**：服务端已有该文件且 `baseSha` 对不上服务端当前版本（**包括不带 baseSha**）→ 不覆盖，返回 `serverSha256 + serverContent`。客户端修正（保留本地改/采纳服务端/人工合并）后，把修正稿连同 `baseSha = serverSha256` 重新 push。
- **删除语义**：`{"path": "...", "delete": true, "baseSha": "<原sha>"}`。删除同样受冲突闸——服务端文件比你记录的新时不删（防基于旧认知误删）；服务端本无此文件则幂等接受。
- **部分成功**：一批里接受/冲突/拒绝互不阻塞；只要有文件被接受就自动收编入库 + git 提交（`apply`、`committed` 字段）。
- **限制**：单文件 ≤ 2MB（UTF-8 字节数）、单批 ≤ 100 个文件。
- push 全程在服务端事务内完成：**路径归一化**（两级 `<分类>/<文件>.md` → `origin/<分类>/<文件>.md`）→ 落盘 → 收编（新文件按类目归属：`收件箱/` 或未匹配类目 → 收集箱 PENDING；命中共享/自有分类 → 直接归档进书）→ commit。响应中的 path 一律为归一化后的三级形式。

### 5.3 握手：`GET /api/client/hello`

客户端启动/配置服务器地址后**第一个调用的接口**——一次请求完成连通性、token 有效性、身份与喵列表确认。

```
GET /api/client/hello
Authorization: Bearer <token>
```

响应：

```json
{
  "protocol": 1,
  "user": { "id": 1, "username": "admin", "displayName": "admin" },
  "cats": [
    { "id": 1, "name": "小肥", "icon": "🐱", "workspacePath": "admin/cat-1", "pendingCount": 2 },
    { "id": 2, "name": "喵喵", "icon": "😸", "workspacePath": "admin/cat-2", "pendingCount": 0 }
  ]
}
```

| 字段 | 含义 |
|---|---|
| `protocol` | 同步协议版本号。服务端对 manifest/pull/push 字段结构做**破坏性变更**时递增——客户端启动时比对自己支持的版本，不兼容则提示升级，避免字段错位 |
| `cats[].pendingCount` | 该喵收集箱待裁决条数（客户端可拿来做红点/角标） |
| `cats[].workspacePath` | 仅展示用（该喵在服务端的工作区位置），客户端不做本地路径拼接 |

### 5.4 轻量内容列表：`GET /contents`

客户端文件列表/管理页用——**不含正文**，比 `pull`（带全部正文）轻。

```
GET /api/client/cats/{catId}/sync/contents
```

响应（数组）：

```json
[
  { "id": 1, "title": "React Hooks 最佳实践", "status": "CLASSIFIED", "contentType": "TEXT",
    "bookTitle": "《技术研发》",
    "path": "origin/技术研发/2026-09-16_React Hooks 最佳实践.md",
    "sha256": "5bf0…", "bytes": 4847, "modifiedAt": "2026-09-21T09:00:00Z",
    "createdAt": "2026-09-16T10:00:00" },
  { "id": 256, "title": "没落盘的条目", "status": "PENDING", "contentType": "URL", "createdAt": "…" }
]
```

| 字段 | 说明 |
|---|---|
| `status` | `PENDING` = 收集箱待裁决；`CLASSIFIED` = 已归档进书 |
| `bookTitle` | 归档到的书名（PENDING 时无此字段） |
| `path` / `sha256` / `bytes` / `modifiedAt` | 与 manifest 口径一致（path 为工作区相对路径、三级 `origin/<分类>/<文件>.md`，可直接用于 `/file` 与 `/push`）；仅已落盘条目有 |
| `missing` | true = 库里有记录但文件缺失（异常状态，客户端可忽略或提示） |

> 想拿某条全文：`GET /file?path=<path>` 或直接 `pull`。

### 5.5 类目拉取：`GET /types`

```
GET /api/client/cats/{catId}/sync/types
```

响应（数组，客户端**只读**）：

```json
[
  { "id": 12, "name": "技术研发", "shared": false, "attached": false,
    "description": "…", "icon": "🛠", "keywords": ["编程","架构"] },
  { "id": 5,  "name": "商业财经", "shared": true, "attached": true, "keywords": [...] }
]
```

| 字段 | 含义 |
|---|---|
| `shared` | true = 全局共享预设分类（22 个）；false = 该喵专属 |
| `attached` | 共享分类是否已被该喵挂接进图谱（专属分类恒 false） |
| `keywords` | 引力关键词数组（客户端可用作本地分类词表） |

`/pull` 的响应里也带一份 `types`，客户端可不单独调。

### 5.6 逐文件原语（可选，一般用 pull/push 就够）

| 接口 | 说明 |
|---|---|
| `GET /manifest` | 内容 md 清单 `[{path, sha256, bytes, modifiedAt}]`（无正文；path 为三级 `origin/<分类>/<文件>.md`），客户端自行 diff |
| `GET /file?path=<url编码的路径>` | 拉单个文件原文（`text/plain; charset=utf-8`）。**path 必须 URL 编码**（中文目录名），否则 400 |
| `PUT /file` | 覆盖写单个文件 `{"path":"origin/<分类>/<文件>.md","content":"…"}`（发两级也会自动归位），响应 `{"ok": true}`（无冲突闸，仅路径校验——优先用 push） |
| `POST /apply` | 单喵收编（磁盘有库无 → 补录；库有磁盘无 → 剔除）+ 重建索引 + git 提交，返回 `{total, addedCount, removedCount, added[], removed[], committed}` |

## 6. 推荐的客户端同步流程（伪代码）

```
state.base: dict<path, sha256>   # 上次同步成功后的基准，持久化保存
state.local: dict<path, 本地文件内容>

def sync():
    # 1. 拉取
    r = POST /pull  body={"base": state.base}
    for f in r.contents: state.server = {f.path: f.content, f.sha256}

    # 2. 本地 diff（本地清单 vs state.base）
    localAdded   = 本地有 而 base 无
    localChanged = 本地有 base 有 但本地sha != base
    localDeleted = base 有 而本地无

    # 3. 处理服务端侧变化（下载方向）
    for path in r.added:                落盘到本地; base[path] = serverSha
    for path in r.removed:              删本地文件; base.pop(path)
    for c in r.conflicts:
        if sha256(local[path]) == base[path]:
            覆盖本地 = c.serverContent; base[path] = c.serverSha256   # 只有服务端改
        elif c.serverSha256 == base[path]:
            待push.append({path, content=local[path], baseSha=base[path]})  # 只有本地改
        else:
            UI 冲突清单 → 用户选：用本地 / 用服务端 / 合并
            用本地 → 待push.append({path, local, baseSha: base})
            用服务端 → 覆盖本地; base[path] = serverSha256
            合并 → 待push.append({path, merged, baseSha: c.serverSha256})

    # 4. 提交本地侧变化（上传方向）
    for path in localAdded:   待push.append({path, content=local, baseSha: null→首个push后记录})
    # ↑ 本地新文件首次 push 不带 baseSha 即可（服务端无此文件不触发冲突）
    for path in localChanged: 待push.append({path, content=local, baseSha: base[path]})
    for path in localDeleted: 待push.append({path, delete: true, baseSha: base[path]})

    # 5. push（冲突循环）
    resp = POST /push {"files": 待push}
    for c in resp.conflicts:  → 再进 UI 冲突清单（服务端又变了）
    for a in resp.accepted:   base[a.path] = a.sha256（deleted 则 base.pop）
    循环直到 conflicts 空

    # 6. 持久化 state.base
```

要点：
- **path 一律用三级 `origin/<分类>/<文件>.md`**：本地也按 `origin/` 目录组织，`state.base` 的 key 用同一形式，与服务端 manifest/pull 的 path 严格对齐。发两级虽会被服务端自动归位，但 base 里的 key 对不上，会导致每次同步都把文件当成「新增」。
- **base 必须持久化**（每个喵一份），它是冲突判定的锚。
- push 被拒（conflict）后**以响应里的 `serverSha256` 为新 base** 重提修正稿，形成乐观锁闭环。
- 文件名用服务端同款安全化规则生成，避免 push 回来后服务端又生成 `_2` 后缀导致双份。

## 7. 错误码与限制汇总

| 场景 | 返回 |
|---|---|
| 未登录 / token 失效 | `401 {"error": "..."}` |
| 喵不存在 | `404 {"error": "猫不存在"}` |
| 操作别人的喵 | `403 {"error": "这不是你养的喵"}` |
| 非法路径（层级不对/隐藏/内部目录/非 md） | `400 {"error": "非法路径: …"}` 或 push rejected |
| 文件不存在（GET /file） | `404 {"error": "文件不存在: …"}` |
| 缺参数（PUT /file 无 path/content） | `400 {"error": "需要 path 与 content"}` |
| 单文件 > 2MB | push rejected：`单文件超过 2MB 上限` |
| 单批 > 100 个 | push rejected：`单批超过 100 个文件，请分批提交` |

其他注意：

- GET 类接口的中文 query 参数（`/file?path=`）**必须 URL 编码**。
- 所有接口都是幂等友好的：重复 pull 无变化返回空 diff；重复 push 同内容（带最新 baseSha）幂等接受。
- 服务端每次 apply/push 自动 git commit（message 前缀「客户端同步/客户端提交」），客户端无需调用任何 git 命令。
- 不要直连 git 仓库（旧方案已废弃）；以本 HTTP 协议为准。

## 8. 类目只读约定

客户端**不得**提供任何「新建分类/改分类名/删分类」能力，类目变化以下两种方式进入服务端：

1. 服务端 Web 端用户操作（新建专属分类 / 挂接共享分类）；
2. 收编时按文件所在的**分类目录名**（三级 `origin/<分类>/…` 里的 `<分类>`，两级形式里就是第一级）自动匹配（共享或该喵自有内按名匹配，匹配不上不新建、内容进收集箱）。

客户端如需引导用户调整类目，打开服务端 Web 页操作即可。
