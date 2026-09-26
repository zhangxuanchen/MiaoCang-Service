# laya × 喵藏 接入与生产部署方案

> 目标：把 laya（非自回归 System 1 决策引擎，单次前向 33ms@GPU / 200–500ms@CPU）接进 MiaoCang-Service，
> 优先落地两块：**内容自动分类打分**、**会话意图快路由**。
> 部署形态：**Linux 服务器纯 CPU，无 GPU**。
>
> 结论先行：**技术上可行，且接入点非常干净**。但方案成立的前提是三条硬约束，必须照做：
> ① 候选类型必须先收窄到 ≤12 个（否则模型侧直接报错）；
> ② `laya-multilingual` 出厂**没有配温度校准**，置信度在拟合之前不可用于自动决策；
> ③ 侧车进程**无任何鉴权**，只能绑 loopback，绝不能对公网暴露。

---

## 1. laya 是什么：原理与能力边界

### 1.1 一句话原理

laya 是 **非自回归的判别式决策模型**，不是生成模型。它把「一段状态（文本/邮件/工单/JSON）+ 一组带类型的提问」编码进一次前向，直接输出每个提问的**概率分布**，没有逐 token 解码，因此没有解析、没有幻觉、耗时恒定。

| checkpoint | encoder | 参数量 | 上下文 | 适用 |
|---|---|---|---|---|
| `laya` | ModernBERT-large | 421M | 512 | 英文 |
| `laya-multilingual` | mmBERT-base | 322M | 1024 | **100+ 语言（含中文）** |
| `laya-typed-decisions` | ModernBERT-large | 421M | 1024 | 四个特定工作流，微调后才有用 |

### 1.2 三种提问原语

| 原语 | 输出 | 喵藏里的典型用法 |
|---|---|---|
| `choice` | 最优标签 + 每标签概率 + 置信度 | 归到哪个分类节点 |
| `score` | 有序档位上的期望值 + 分布 | 内容质量分、复杂度分 |
| `noul` | 校准过的 P(true) ∈ [0,1] | 是否值得收录、是否是确定性指令 |

### 1.3 返回载荷（精确结构）

`POST /v1/predict` 的响应就是 `laya.Agent.system_one` 的载荷，外加 `latency_ms`：

```json
{
  "model": "laya-rl-agent",
  "answers": {
    "category": {
      "type": "choice",
      "choice": "技术研发",
      "probabilities": { "技术研发": 0.71, "产品设计": 0.18, "other": 0.11 },
      "confidence": 0.71,
      "action": { "act_probability": 0.93 }
    },
    "worthy": { "type": "noul", "noul": 0.88, "confidence": 0.88, "action": {...} },
    "quality": { "type": "score", "score": 2.41, "legend": {"0":"…","1":"…","2":"…","3":"…"},
                 "probabilities": {"0":0.08,"1":0.19,"2":0.41,"3":0.32}, "confidence": 0.41, "action": {...} }
  },
  "usage": { "input_tokens": 612, "output_tokens": 0 },
  "routing": { "model": "multilingual", "reason": "non-Latin script (han, 100% of letters)…" },
  "latency_ms": 412.7
}
```

> `choice` 的概率分布、`score` 的分布、`noul` 的 P(true) 都是**同一次前向**产出的 —— 三个问题不额外耗时。

### 1.4 中文怎么走

`laya/lang.py` 的脚本检测覆盖 CJK（[lang.py:42](../../laya/laya/lang.py#L42) `("han", ((0x3400,0x4DBF),(0x4E00,0x9FFF),(0xF900,0xFAFF)))`）。中文文本 → 脚本判定 `han` → 非拉丁 → 自动路由到 `multilingual`。

**喵藏是中文为主的库，因此方案里我们直接固定 `model: "multilingual"`，不用自动路由** —— 少一跳、少一份不确定性；只有出现纯英文文档时才需要路由（见 §4.5）。

### 1.5 必须知道的边界（别踩）

1. **不能生成文本**。它的产出只有标签/分数/概率。所以整体架构是「laya 判、LLM 写」——laya 永远做**闸与路由**，不做内容生产。
2. **选项数量受 token 预算硬约束**。序列被切成 `head_max_len`（选项区）与 `max_len - head_max_len`（状态区）。`multilingual` 默认 `head_max_len=256`、`max_len=1024`。选项过多时每个标签只剩 3–4 个 token 而彼此不可分（官方实测 77 选项退化到 0.425）；**更硬的是它会直接抛错**：
   [agent.py:288](../../laya/laya/agent.py#L288) `raise ValueError("question %r options exceed head_max_len=%d")`，HTTP 层变成 **400**。
   → 按官方预算，`(256-16)/选项数` ≥ 12 token 才稳妥，即**选项 ≤ 12 个**。这条决定了 §3.1 的「先预筛后决策」设计。
3. **`laya-multilingual` 出厂不带温度校准**（`temperature`/`temperature_by_options` 缺失或为 1.0）。官方在共享基准上把它从 ECE 0.314 拟合到 0.106，但**权重里没带这些温度**。而我们恰恰要用中文 → 必须自己拟合（§4.6）。`laya` 英文 checkpoint 带校准，但读数仍是 over-confident 的。
4. **`score` 是最弱原语**（SST-5 仅 0.372）。质量分这类用法只能当**软信号**，不能单独决定生死。
5. **零样本不要指望 `typed-decisions`**：官方自己的数据是该 checkpoint 零样本 0.362（随机 0.318、多数类 0.461），0.766 是**在基准训练集上微调后**的成绩。要用它做 Agent 痕迹评审，得先在我们自己的数据上微调。
6. **英文 checkpoint 在非拉丁脚本上是崩溃式失效**：高棉语 0.000 准确率却有 0.952 置信度。所以路由判断必须在**前向之前**做，不能靠置信度兜底。

---

## 2. 与喵藏的结合点总览

| 优先级 | 场景 | 喵藏现有实现 | 替换方式 | 收益 |
|---|---|---|---|---|
| **P0** | 内容自动分类打分 | `ClassifyService.autoAssign` 关键词打分 + 固定阈值 2.0 | 关键词**预筛** → laya **语义决策** + 置信度门控 | 分类准确率提升、阈值语义化、可量化 |
| **P0** | 会话意图快路由 | 全部交给 LLM（ReAct 多轮） | laya 前置判意图：高置信确定性指令**跳过 LLM 直接执行工具** | 省一轮 LLM、工具误调减少 |
| P1 | 注入防护 / 内容安全 | 无 | 入库与拼提示词前跑 `guard_questions` | 堵住间接注入面 |
| P2 | 模型分层路由 | 每猫固定一个模型 | `router_questions` 判难度 → 选便宜/前沿模型 | 成本下降 |
| P2 | 流水线产物复核 | 无 | `typed-decisions` 的 `agent_trace_observability` 工作流（字段 `action/needs_review/outcome/risk/urgency`）**需要先微调** | 自动挑出该人工复核的步骤 |

---

## 3. 场景一（P0）：内容自动分类打分

### 3.1 现状与病灶

[ClassifyService.java:68-87](../../MiaoCang-Service/src/main/java/com/miaocang/service/ClassifyService.java#L68-L87)：

```java
List<BookType> types = catId == null ? typeRepo.findAll() : availableTypes(catId);
List<GravityEngine.TypeScore> scores = engine.scoreAll(types, item.getTitle(), item.getRawText());
item.setTypeScores(toJson(top(scores, 5)));
if (scores.isEmpty() || scores.get(0).score() < minScore) {      // minScore = ${miaocang.gravity.min-score:2.0}
    item.setStatus(ContentItem.STATUS_PENDING);                  // → 收集箱
    return;
}
assignToType(item, type, best);                                  // → 已归档 + 两级目录
```

问题很明确：
- 判据是 `matchedKeywords` 的**加权关键词计数**，对同义表达、跨语言、改写完全无感；
- 阈值 `2.0` 是个**没有量纲的魔数**，靠调参；[LibrarySyncService.java:70](../../MiaoCang-Service/src/main/java/com/miaocang/service/LibrarySyncService.java#L70) 还复用了同一个阈值，改一处要动两处；
- 分数不可解释（"为什么是 3.0 而不是 2.0"），前端只能展示原样数字。

### 3.2 设计：两段式（预筛 → 语义决策）

```
ContentItem(rawText)
   │
   ├─① 预筛：复用现有 GravityEngine.scoreAll(types, title, rawText)   ← 零成本、已在跑
   │     取 top-K（K = 12）作为候选；顺带保留 matchedKeywords 供标签用
   │
   ├─② 决策：一次 /v1/predict（model=multilingual），3 个提问一起问
   │     category : choice  ← 候选 K 个分类，每类写成 "名称：关键词/描述"
   │     worthy   : noul    ← 这篇内容是否值得收录（区别于"能不能归类"）
   │     quality  : score   ← 内容完整度/信息量 4 档
   │
   └─③ 门控：confidence(category) ≥ 阈值 且 P1-P2 ≥ margin  → 归档
             否则 → 收集箱（STATUS_PENDING），并把置信度与分布落库供人工复核
```

**为什么必须预筛**：`multilingual` 默认 `head_max_len=256`，选项 >12 个就会踩 §1.5 的第 2 条，HTTP 400。预筛把「语义分类」和「候选收窄」解耦，还**完全不用改 cfg**，保持与官方默认配置一致的数值行为。

**为什么这是低风险改造**：`GravityEngine.TypeScore` 是 record（typeId/name/score/matchedKeywords）。把 laya 的概率映射成 `TypeScore(typeId, name, prob*10, 原有matchedKeywords)` 写回 `item.setTypeScores(...)`，**前端与既有「判定阈值」展示一行都不用改**，只是数字从关键词计数变成量纲统一的语义分。

### 3.3 落地清单（已实施）

| 步骤 | 文件 | 动作 |
|---|---|---|
| 1 | `laya/LayaDecisionClient`（新） | 薄 HTTP 客户端（JDK `HttpClient` + 已有 `ObjectMapper`，**零新增依赖**），`POST /v1/predict`，超时 1.5s，失败抛 `LayaUnavailableException`，**调用方一律 fail-open** |
| 2 | `laya/LayaClassifyService`（新） | 关键词预筛 top-K → 组装 state/questions → 解析 answers → 映射回 `TypeScore`（概率 × 10）；门控用 p1 与 p1−p2 |
| 3 | `ClassifyService.autoAssign` / `scoreOnly` | 收敛到私有 `score(types, item)`：先关键词打分，再让 laya 判定；`modelGate == null`（侧车不可用/超时/禁用）时回退原 `min-score` 阈值 |
| 4 | `application.yml` | 新增 `miaocang.laya.*`（含 `classify.*` / `intent.*`），每项带中文注释 |
| 5 | `ClassifyService.scoreOnly` | 同步受益（书库导入二次分类） |
| 6 | 门控量纲 | laya 生效时**由概率门控取代**关键词 `min-score`（两个量纲不同，叠加会互相打架）；日志同时打印两边分数 |

> 与初版方案的差异：**砍掉了 `worthy` / `quality` 两问**（没有任何消费方）；**不新增只读回填接口**（先用真实归档链路观察，回填是后续独立动作）。

**幂等与回填**：新增一个只读接口导出「`status=PENDING` 的历史内容」，跑一次离线回填（见 §4.7），让收集箱里的存量内容重新过一遍新判据 —— 这是最能直观体感到收益的一步。

### 3.4 期一验收标准

- **离线基准**：从库里取 `status=CLASSIFIED` 且有明确分类的历史内容做留出集（**这些天然是标注数据，不需要人工标注**），对比三条曲线：现有关键词打分 / laya 的 `choice` argmax / laya + 预筛。要求 laya 在留出集上**准确率不低于关键词基线**，且给出 ECE。
- **回填效果**：收集箱存量内容中，被新判据正确收编的比例（抽检 50 条人工确认）。
- **延迟**：单篇端到端（预筛 + 前向 + 落库）p95 < 1.5s（CPU）。
- **降级可用**：把侧车 kill 掉，归档链路必须**照常按关键词路径工作**，不报错、不阻塞。

---

## 4. 场景二（P0）：会话意图快路由

### 4.1 现状

[ChatController.java:39-44](../../MiaoCang-Service/src/main/java/com/miaocang/controller/ChatController.java#L39-L44) → `ChatSessionService.stream(catId, sessionId, message, pageContext)`，直接进 ReAct/HarnessAgent。也就是说「打开某某文件」这类**完全确定**的指令，也要走一遍 LLM 的多轮思考与工具调用，才有概率调对 `open_file`（本轮对话里我们刚修好的，正是这条链路）。

### 4.2 设计：System 1 前置判意图

在 `ChatSessionService.stream` 里、调 LLM **之前**插入一步：

```
message + pageContext
   │
   ├─ 与「领地检索」并发发起（overlap，不额外占墙钟时间）
   │
   └─ laya 一次前向，1 个提问：
        intent     : choice  ← open_file / edit_file / search / chat / unsafe
   │
   ├─ p1 ≥ sure-threshold(0.90) 且 p1−p2 ≥ 0.20 且 intent = open_file
   │     → 【短路】直接执行对应工具，SSE 推 {"type":"file","action":"read",...} 协议串
   │       （复用现有前端 parseWsAction → handleWsAction 链路，前端零改动）
   │
   └─ 否则 → 【软提示】把预判结果拼成一段块注入提示词：
             「【System 1 预判】这一步我快速判断主人的意图是：…（确定度 92%）」
             让 LLM 自己决定 —— 与现有「领地检索命中」块同一形态，模型已被训练过如何处理这类提示
```

> **为什么不用 `noul` 做「确定度」**（实测踩坑，已改）：`noul` 要的是**命题**（上游例子：
> "Does the user threaten to cancel?"），喂非命题问法（如「你对上面的判断有多确定？」）
> 模型会当假命题答，最明确的「帮我打开 x.md」也只得 `noul=0.13` —— 门控直接变成死代码。
> 而 `multilingual` 出厂**没做温度校准**，`confidence` 同样不可信。故确定度改取 `choice` 的
> `p1`、间隔取 `p1−p2`：两者出自同一分布、彼此自洽，与 §3.5 归档门控同一套判据。
> 实测区分度：`帮我打开 会话笔记.md` → `open_file` p1=1.00/p2=0.00（放行）；
> `把 会话笔记.md 的内容总结一下` → p1=0.42/p2=0.40（不放行，交给 LLM 总结）。

### 4.3 为什么这样切分

- **短路路径**的收益是**秒级**：省掉一整轮 LLM + 1~3 次工具往返，且行为 100% 确定（不会出现「说打开了其实没调」这种幻觉，本轮对话里已经踩过）。
- **软提示路径**的成本只有 ~0.4s（CPU），且与检索并发——墙钟几乎不增。它把「模型该不该调工具」这个判断从纯 LLM 猜，变成有先验的决策。
- **`unsafe` 意图**顺手接上护栏（P1）：判到 `unsafe` 直接拒绝或降权，不进 LLM。

### 4.4 落地清单（已实施）

| 步骤 | 文件 | 动作 |
|---|---|---|
| 1 | 复用 §3.3 的 `LayaDecisionClient` | 同一个客户端、同一个侧车 |
| 2 | `laya/LayaIntentRouter`（新） | `classifyAsync` / `join`（有界等待）与 `selfTest`；state 只放 `{message, page}`；文件定位用 `matchFile` 在 Java 侧做字符串比对 |
| 3 | `ChatSessionService.stream` | 与领地检索**并发**发起判定，`join` 有界等待 800ms，超时**放弃并走原路径** |
| 4 | `ChatSessionService.openFileShortCircuit` | 短路命中时发 `tool`/`toolresult` 协议串 + 落记忆 + `done`，**不构建 Agent** |
| 5 | 配置 | `miaocang.laya.intent.*`：`timeout-ms`、`sure-threshold`、`hint-enabled`（总开关在 `miaocang.laya.enabled`）；另有类内常量 `SURE_MARGIN=0.20`（p1−p2 下限） |
| 6 | `SingleTurnExecutor.TurnSpec` | 新增 `intentHint` 分量，拼装位置在 `ragContext`/`pageContext` 之后、`【主人的消息】` 之前 |

> 与初版方案的差异：**只有 `open_file` 短路**（`edit_file`/`rename_doc` 是有副作用的动作，误判代价远大于慢几秒，一律只给软提示）；意图标签去掉 `rename_doc`；去掉 `certain`(noul) 与 `complexity`(score) 两问 —— 前者见 §4.2 的实测说明（改用 `p1`/`p1−p2`），后者 P2 才用得上，先不做死代码。

### 4.5 state 预算（中文场景必须算）

`multilingual`：`max_len=1024`、`head_max_len=256` → **状态区只剩 768 token**。mmBERT 对中文约 1 token/字，所以：

- **分类场景**：state = 标题 + 正文**前 ~600 字**（超长截断，截断点落库备查）。
- **意图场景**：state 很短（一句话 + 页面名 + 工作区路径），天然安全；但**不要把整个文件清单塞进去**——文件多时只放文件名列表前 30 个。

> 这条不是优化，是**硬约束**：不截断就会静默丢内容（超出部分被 tokenizer 截掉），表现为「分类总是偏向前半段」。截断策略要显式写进代码并落日志。

### 4.6 温度校准（**必须做，否则置信度不可用**）

因为固定用 `multilingual`，而它**出厂无温度**，`confidence` 字段在拟合前是 over-confident 的，任何基于它的门控都是假门控。做法：

1. 用 §3.4 的留出集（历史已归档内容 + 人工抽检 300~500 条）得到 `(qtype, 选项数桶)` → 标签；
2. 按官方口径，**每个桶拟合一个温度**，最小化 ECE（参考仓库 `research/scripts/laya_benchmark_colab.ipynb` 的 calibration repair 段落，逻辑与 `laya/common.py` 的 `temp_bucket` / `clamp_temperature` 一致）；
3. 把拟合结果写进 checkpoint 目录的 `rl_agent_config.json` 的 `temperature` / `temperature_by_options`，并**用本地模型目录加载**（见 §5.2 的启动器），避免污染 HF 缓存、避免被重新下载覆盖；
4. 门控上线前后各测一次 ECE，落成对比表。

> 在温度拟合完成前，门控只能用 **概率间隔（P1−P2）** 而非绝对置信度；阶段一先按这个口径上线，拟合完再切到置信度。

### 4.7 期二验收标准

- **意图准确率**：用会话 jsonl 里已有的 `tool_call` 作为弱标注（真实调过 `open_file` 的轮次 = 正样本），在留出集上测 `intent` 的准确率与 `certain` 的 AUC。
- **短路正确率**：短路路径必须**零错误执行**（宁可漏判也不误判）——误判会把「闲聊」当「打开文件」执行，比慢几秒严重得多。
- **墙钟**：与「领地检索」并发后，一轮对话的 p50/p95 墙钟**不高于**改造前 +150ms。
- **降级**：侧车不可用时，会话**完全按原路径**工作，只少一个软提示。

---

## 5. 部署形态（**仓库内嵌自启动**）

> 本节已按最终落地形态改写：**不依赖任何外部工程**（`/Users/zxc/Documents/ai/laya` 只在拷贝时用了一次），
> 侧车随 Spring Boot 一起拉起、一起回收。原「独立部署 + systemd 托管」保留在 §5.3 作为可选形态。

### 5.1 架构拓扑

```
┌──────────────────────── 一个仓库（无外部依赖） ────────────────────────┐
│                                                                       │
│  ┌─────────────────────────────┐     HTTP /v1/predict                  │
│  │  喵藏 Spring Boot 3.5.5      │ ─── 127.0.0.1:8777（可自动挑端口）──┐  │
│  │  LayaSidecarManager          │                                  │  │
│  │   ├ 检测环境 → 必要时跑 setup │      超时 1.5s（分类）            │  │
│  │   ├ 拉起子进程 → 探活 → 自检  │      超时 800ms（意图）            │  │
│  │   └ @PreDestroy 回收子进程    │                                  │  │
│  └──────────┬──────────────────┘                                  ▼  │
│             │                                ┌────────────────────────┐│
│  ┌──────────▼──────────┐                    │ sidecar/laya_server.py ││
│  │ H2 + 书库目录        │                    │ 内嵌 laya 0.3.6 包      ││
│  │ ./data/miaocang      │                    │ 常驻 multilingual(322M)││
│  └─────────────────────┘                    │ ~1.5–2 GB RSS          ││
│                                             └───────────┬────────────┘│
│  ┌────────────────────────────┐            ┌────────────▼────────────┐│
│  │ sidecar/.venv（python3.11） │            │ data/laya/models/       ││
│  │ CPU-only torch + transformers│           │   multilingual/（权重）  ││
│  └────────────────────────────┘            └─────────────────────────┘│
└───────────────────────────────────────────────────────────────────────┘
      只监听 loopback，不暴露公网 ── 侧车无鉴权，这是硬性要求
```

**为什么是侧车而不是进程内**：`laya/java` SDK 里没有任何本地推理实现——它对 [README](../../laya/java/README.md) 明说「模型前向传递仍放在 Python HTTP 后端中」，`pom.xml` 只依赖 Jackson。仓库**不提供** ONNX 导出脚本，Java 侧要自跑推理就得自己重写 tokenizer、`build_sequence`、marker 对齐与温度缩放，工作量远超收益。**结论：侧车是唯一现实的形态**；进程内推理列在 §7 作为远期选项。

**为什么内嵌而不是独立部署**：Python 包只有 228 KB（`sidecar/laya/`，7 个文件，Apache-2.0，见 `sidecar/UPSTREAM.txt`），
内嵌后侧车与主应用共享同一个仓库根，路径、版本、生命周期天然一致，`git clone` + `bin/mc.sh start` 就是全部部署动作。

### 5.2 侧车启动（自启动链路）

`sidecar/laya_server.py` 是内嵌的裁剪版入口（**不 fork 上游 `java/backend/server.py`**，只是不复用它的测试台 UI）：

| 变化 | 原因 |
|---|---|
| 只保留 `GET /health`、`POST /v1/predict`、`POST /v1/preload` | 喵藏不做调试面板，Java 契约只需这三端 |
| `/v1/predict` 的 `model` 缺省走**自动路由**（上游缺省是 `english`） | 中文内容掉进 english checkpoint 会「崩到接近随机却仍报高置信」 |
| 请求体上限 2 MB | 虽只绑 loopback，也不该让异常大请求打爆进程 |
| `sys.path.insert(0, HERE)` + `import laya` | 命中**仓库内**的 `sidecar/laya/`，机器上装不装 laya 都无关 |

启动时序（`LayaSidecarManager.boot()`，跑在独立的 `laya-sidecar-boot` 守护线程上，**不阻塞应用启动**）：

```
ApplicationReadyEvent
  → probeExisting()   已有侧车在跑？→ ATTACHED（不 spawn、退出时也不杀）
  → prepareEnvironment()  venv / 权重缺失且 auto-prepare=true → /bin/bash bin/laya-setup.sh
  → spawn()           ProcessBuilder(python, sidecar/laya_server.py, --host/--port/--device/--preload/--models-dir)
                      环境注入 PYTHONPATH / OMP_NUM_THREADS / HF_HOME，权重就位时 HF_HUB_OFFLINE=1
  → awaitReady()      轮询 /health 直到 loaded ⊇ preload（进程中途退出直接判失败）
  → selfTest()        分类 + 意图各跑一次真实判定，把「侧车可用」写进日志
```

任一步失败 → `state=DOWN` 并只打一条 warn：**应用照常服务**，内容分类与会话意图完全走原有路径。
应用关闭时 `@PreDestroy` 先 `destroy()`、3 秒不退再 `destroyForcibly()`；`bin/mc.sh stop` 额外 `pkill -f sidecar/laya_server.py` 兜住 `kill -9` 留下的孤儿。

### 5.3 systemd 单元（可选：改为外部托管时）

只在**想让侧车脱离主应用独立存活**时才需要（然后把 `miaocang.laya.auto-start` 置 `false`，主应用启动时
`probeExisting()` 会直接 `ATTACHED` 接管，不会重复拉起，也不会在退出时杀掉它）：

```ini
[Unit]
Description=laya inference sidecar (MiaoCang，外部托管形态)
After=network.target

[Service]
Type=simple
User=miaocang
# <REPO> = 喵藏仓库根
WorkingDirectory=<REPO>
Environment=PYTHONPATH=<REPO>/sidecar
Environment=TOKENIZERS_PARALLELISM=false
Environment=USE_TF=0
Environment=HF_HUB_OFFLINE=1
Environment=OMP_NUM_THREADS=2
ExecStart=<REPO>/sidecar/.venv/bin/python <REPO>/sidecar/laya_server.py \
    --host 127.0.0.1 --port 8777 --device cpu --preload multilingual --models-dir <REPO>/data/laya/models
Restart=always
RestartSec=3
# CPU 侧车内存封顶，超限即重启（避免占满服务器）
MemoryMax=3G
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=<REPO>/data/laya

[Install]
WantedBy=multi-user.target
```

> `OMP_NUM_THREADS` 要与机器核数匹配。torch 默认按物理核起满线程，与 Spring Boot 抢 CPU 时会互相拖慢；**建议给侧车固定 2–4 个线程**（对应 `miaocang.laya.cpu-threads`），因为我们的调用量本来就不高。

### 5.4 Python 环境（一条命令，幂等）

```bash
bin/laya-setup.sh        # = bin/mc.sh laya-setup
```

脚本四步，已就绪的步骤自动跳过，可反复执行：

| 步骤 | 动作 |
|---|---|
| 1 | 建 `sidecar/.venv`（依次尝试 python3.11 → 3.12 → 3.13 → 3.10 → python3；laya 要求 ≥3.10） |
| 2 | 装 **CPU-only torch**（`--index-url https://download.pytorch.org/whl/cpu`，省掉 GB 级 CUDA wheels）+ `sidecar/requirements.txt` |
| 3 | `snapshot_download(repo_id="convaiinnovations/laya", allow_patterns=["multilingual/*"], local_dir=data/laya/models)` |
| 4 | 冒烟：`sys.path.insert(0, sidecar)` 后 `import laya` 并列出权重文件，确认「不依赖外部工程」成立 |

应用启动时若发现 venv 或权重缺失，`LayaSidecarManager.prepareEnvironment()` 会**自动调它**（`auto-prepare=true`），
输出追加到 `data/logs/laya-setup.log`，最长等 30 分钟。

### 5.5 权重落地（联网一次，之后离线）

`bin/laya-setup.sh` 的步骤 3 会把 `multilingual` 子目录下到 `data/laya/models/multilingual/`；
此后 `laya_server.py` 命中本地目录即按本地加载，`LayaSidecarManager` 还会给子进程注入 `HF_HUB_OFFLINE=1`，
**运行期不再联网**（这台机器上从未下载过 multilingual，首次必须联网一次）。

只下 `multilingual` 子目录（上游 `Agent.__init__` 的 `allow_patterns` 本来就是按子目录过滤的）。

> **网络**：国内网络直连 `huggingface.co` 会 `ConnectTimeout (Errno 60)`。脚本先试直连，
> 失败即自动改用镜像 `https://hf-mirror.com`（`HF_ENDPOINT` 必须在 Python 进程启动前设好，
> `huggingface_hub` 在 import 时就把常量读死了）；要换别的镜像或代理用 `MIAOCANG_HF_ENDPOINT` / `MIAOCANG_HF_MIRROR`。

> **注意**：`laya/agent.py:_fix_tokenizer_config` 会**写** checkpoint 目录里的 `tokenizer/tokenizer_config.json`（修 mmBERT 的 `extra_special_tokens` 列表/映射不兼容）。所以模型目录**必须可写** —— 这也是 §5.3 里 `ReadWritePaths` 指向 `data/laya` 的原因。放进只读镜像层会启动失败。

**本机实测（2026-09-23，Mac / CPU）**：

| 环节 | 实测 | 备注 |
|---|---|---|
| 权重下载 | **11 s**（647 MB） | 走 `MIAOCANG_HF_ENDPOINT=https://hf-mirror.com`，约 22 MB/s |
| torch / transformers | 已就绪（2.14.0 / 5.17.0） | venv 为 Python 3.11（laya 仓库那份是 3.14，wheel 不通用、不能互相拷） |
| 侧车冷加载 | **25 s** | 明显高于官方 7–10 s（本机 CPU 更弱），卷 `startup-timeout-seconds` 要留足 |
| 单次判定 | **78–133 ms** | 比官方 193–464 ms 更快，因每组问题集只有 4–5 个选项 |
| 启动自检 | 两条都通过 | `category=1 p1=0.775 p2=0.225 门控=通过`；`intent=open_file p1=1.00 p2=0.00 短路=放行` |

> **端口**：喵藏侧车默认 **8777**，**刻意避开上游 `laya/java/backend/server.py` 的默认 8770**。
> 若两边都用 8770，`LayaSidecarManager.probeExisting()` 探测到那个健康进程会直接挂上去（ATTACHED）
> —— 看着正常，实际「启动带起」没生效、还静默依赖了外部进程，且它挂掉后不会自愈。
> `port: 0` 则让侧车自己挑一个空闲端口，连本地冲突都不怕。

### 5.6 资源预算（纯 CPU，单实例）

| 项 | 估算 | 依据 |
|---|---|---|
| 常驻内存 | **~1.5–2 GB RSS**（322M fp32 ≈ 1.3 GB + tokenizer/torch 开销） | CPU 强制 fp32（[agent.py:225-227](../../laya/laya/agent.py#L225-L227)） |
| 单次前向延迟 | **200–500 ms**（1 组提问） | 官方 CPU 实测 193–464 ms |
| 冷加载 | **7–10 s** | 官方实测 |
| 并行度 | 3–4 并发请求可接受；再高收益衰减 | GIL + 单模型共享，推理不串行但会争 CPU |
| 磁盘 | 权重 ~1.3 GB + venv ~1.5 GB（CPU torch） | — |

**结论**：给侧车 `MemoryMax=3G`、2–4 线程、`Restart=always` 即可，对 4C8G 的机器无压力。

> 若后续要提升吞吐：官方建议**横向多开几个侧车进程**（各占一份内存）+ 前置负载均衡。喵藏的调用量远达不到这个量级，**不建议现在做**。

### 5.7 安全（**这一节不能省**）

上游后端的安全姿态是「仅供内网/可信环境」——原话见 [OPERATIONS.md:408](../../laya/java/OPERATIONS.md#L408)：

- **无任何鉴权**：没有 API key、没有 token 校验；
- 默认绑 `127.0.0.1`，只有显式 `--host 0.0.0.0` 才对外；**我们不要加这个参数**；
- 无 CORS 头、**无请求体大小限制**、无速率限制；
- `ThreadingHTTPServer` 是标准库实现，**每连接一线程、无上限**：公网暴露等于送一个 DoS 面。

**必须遵守的三条**：

1. 侧车**只绑 127.0.0.1**，与 Spring Boot 同机部署。要跨机就套 SSH 隧道或内网 mTLS，不要裸奔 `0.0.0.0`。
2. 若将来确需对外，前置 nginx：加 `auth_request`/内网 ACL、`limit_req`、`client_max_body_size 1m`，并且**只反代 `/v1/predict` 与 `/health`**——`/`、`/api/*` 那个测试台界面不要暴露（它能任意构造 questions、能触发 `preload`）。
3. Spring Boot 侧对 state 做**长度截断**（§4.5），这也是防止异常大请求打爆侧车的一道闸。

### 5.8 健康检查、降级与可观测

| 机制 | 做法 |
|---|---|
| 健康检查 | `GET /health` → `{"status","loaded","models","version"}`。**必须校验 `loaded` 含 `multilingual`**，只看 200 不够（懒加载模式下 200 也可能没装模型） |
| 启动顺序 | Spring Boot 启动时**不阻塞等待**；脉冲式探测 `/health`，就绪后才把 `miaocang.laya.enabled` 置真 |
| 降级 | 任何异常/超时 → 返回 empty → **走原有 LLM / 关键词路径**。降级必须是静默的、不影响主流程正确性（fail-open） |
| 熔断 | 连续 N 次失败或超时 → 打开熔断，T 秒内不再调用（避免每个请求都白等 1.5s）。可用 Resilience4j，也可手写一个计数器（推荐后者，依赖更少） |
| 可观测 | 每次调用记录：`latency_ms`（侧车已返回）、`routing.model`、`usage.input_tokens`、top1 概率、是否降级。**接进喵藏已有的 `CatEventService` 问题分析口径**（它已有 `modelSlowMs` 等阈值项），侧车的慢请求可以直接成为一个新问题项 |
| 日志 | 侧车 `log_message` 是静音的，**主动日志要在启动器里加**；否则出问题只有 `journ` 里一片安静 |

### 5.9 Java 侧客户端：建议自己写薄的，不引入上游 SDK

上游 `com.convai:laya-java:0.3.6` 是个纯 HTTP 客户端（只依赖 Jackson），但**没有发布到 Maven Central**，用它就得 `mvn install` 一个 git clone → 把构建绑到外部仓库；而且它把 `jackson-databind` 钉在 2.17.1，与 Spring Boot 3.5.5 BOM 管理的版本不一致，得手动 exclude 才不出乱子。它的价值（本地路由 + 语言检测 + Presets）对我们**用不上**：我们固定 `model=multilingual`，路由不需要；提问模板我们会按中文重写。

所以：**用 Spring 的 `RestClient` 写一个 ~60 行的 `LayaDecisionClient`**，只依赖主应用已有的 Jackson。要点：

```java
POST http://127.0.0.1:8777/v1/predict
Content-Type: application/json
{ "model": "multilingual", "state": {...}, "questions": {...} }   // 注意 model 是必填，缺省是 english
```

- 超时：分类 1500ms / 意图 800ms（`RestClient` 的 `requestFactory` 分别配）；
- 400 要按**可预期错误**处理（选项超 `head_max_len` 就返回 400 并带 `ValueError: ...`），日志要打全，因为这是我们自己的 questions 构造出错；
- 不做重试（重试只会把延迟叠上去，降级更快）。

### 5.10 容量与背压

分类是后台链路，天然有背压：`ExtractPipeline.extractAsync` 用的是**单线程 executor**（[ExtractPipeline.java:124-137](../../MiaoCang-Service/src/main/java/com/miaocang/service/ExtractPipeline.java#L124-L137)）。laya 那一步加进去后，单篇 +0.4s，**串行吞吐约 2–2.5 篇/秒**。若要回填几千篇：给回填单独开一个有界线程池（4 线程）走 laya，**不要**直接放大主流水线的线程数（那会同时放大 LLM 调用）。

---

## 6. 分期实施路线

| 阶段 | 内容 | 交付物 | 出口条件 |
|---|---|---|---|
| **P0-a 基建** | 侧车部署（systemd + 预置权重 + 启动器）；`LayaDecisionClient`；`/health` 探活与熔断降级；`miaocang.laya.*` 配置 | 侧车跑起来，喵藏可连通、可降级 | `curl /health` 的 `loaded` 含 `multilingual`；kill 侧车后归档链路无异常 |
| **P0-b 评测** | 从历史已归档内容构建留出集；**温度拟合**；关键词基线 vs laya 对比表 | 评测脚本 + 结论表（准确率/ECE/延迟） | laya 不劣于关键词基线，且 ECE 可接受 |
| **P0-c 分类上线** | `LayaClassifyService` + `ClassifyService.autoAssign` 接入（预筛 ≤12 + 置信度门控） | 归档链路切换，关键词路径保留为降级 | 期一验收标准（§3.4）全过 |
| **P0-d 存量回填** | 导出 `STATUS_PENDING` 存量 → 回填 → 抽检 | 回填报告 | 抽检 50 条正确率达标 |
| **P1 意图路由** | `IntentRouter` + `ChatSessionService` 前置；短路路径走现有 SSE 协议串 | 会话快路由上线 | 短路**零错误**；墙钟 +150ms 以内（§4.7） |
| **P1.5 护栏** | `guard_questions` 接入库与拼提示词前 | 注入闸 | 注入样本全部拦截，正常内容零误伤 |
| **P2 模型分层** | `router_questions` 的 `difficulty` 对接 `AgentConfig` 选模型 | 成本报表 | 成本下降且质量不降 |
| **P2.5 产物复核** | 在自有数据上微调 `typed-decisions`（2×T4 约 4–5 小时）→ 接 Agent 痕迹评审 | 微调 checkpoint + 复核入口 | 复核准确率达标注一致率水平 |

> P2.5 单独列在后面是有原因的：`typed-decisions` **零样本近随机**（§1.5 第 5 条），不微调不能用。它恰好有 `action/needs_review/outcome/risk/urgency` 这套题面，和喵藏的 Agent 痕迹场景高度吻合，是长期最值得投入的一块。

---

## 7. 风险台账

| 风险 | 影响 | 对策 |
|---|---|---|
| 侧车无鉴权 | 被扫到即可任意调用/触发 preload | 只绑 loopback（§5.7）；跨机走隧道 |
| 置信度未校准 | 门控失效、误归档 | **先拟合温度**；拟合前只用概率间隔（§4.6） |
| 选项超 `head_max_len` | HTTP 400，归档失败 | 预筛 ≤12（§3.2）；400 当可预期错误处理并告警 |
| state 被静默截断 | 分类偏向文本前半段 | 显式截断 + 落日志（§4.5） |
| `score` 原语偏弱 | 质量分不可靠 | 只作软信号，不单独决定归档 |
| CPU 延迟 200–500ms | 交互链路变慢 | 与检索并发、800ms 超时、熔断、fail-open |
| 冷加载 7–10 s | 首个请求卡死 | 启动即 `preload`；`/health` 校验 `loaded` |
| GPU OOM 自动降级 CPU | 延迟静默劣化 10–15× | 纯 CPU 部署无此路径；仍监控 `routing` 与延迟 |
| 权重目录不可写 | 启动失败（tokenizer 修复写回） | `ReadWritePaths` + 目录可写（§5.5） |
| 上游版本漂移 | 行为变化 | 钉 `laya==0.3.6`；升级前重跑 P0-b 评测 |
| 中文实测缺位 | 效果未知 | P0-b 就是在补这件事，**先用自有数据验证再上线** |

### 诚实的限制（不要对外过度承诺）

- laya 在中文/多语言上的公开数据是 **MASSIVE intent（20 选项）0.451、XNLI 0.731** 一类指标，**没有针对中文分类任务的官方基准**。所以本方案把「在喵藏自己的数据上评测」放在上线**之前**，而不是之后。
- 它不是生成模型，无法替代喵的写作与整理能力；它只是把**「判」这件事**从 LLM 手里拿走，做得更快、更便宜、更可解释。
- 默认 `laya`（英文）checkpoint 对中文**完全不可用且不会报错**（脚本检测是唯一的护栏）—— 这就是我们把 `model` 钉死成 `multilingual` 而不用自动路由的原因。

---

## 8. 回滚

三层开关，任意一层都能独立回滚：

1. **配置级**：`miaocang.laya.enabled=false` → 立刻回到纯关键词/纯 LLM 路径，无需重启侧车，无需发版（配合 Spring Boot 的配置刷新或仅重启主应用）。
2. **进程级**：`systemctl stop laya-sidecar` → 自动降级路径接管，主流程不受影响。
3. **数据级**：门控阈值调到极端（置信度阈值 1.01）→ 等价于「永不自动归档」，行为退回改造前，但打分与分布仍然落库，可继续观察。

**上线前必须演过一遍**这三条回滚，否则不算可投产。

---

## 附：事实核对清单

本方案中所有关于 laya 的行为描述均来自上游源码，可逐条核对：

| 结论 | 出处 |
|---|---|
| 无鉴权、仅供内网 | [OPERATIONS.md:408](../../laya/java/OPERATIONS.md#L408) |
| 选项超预算直接抛错 | [agent.py:286-288](../../laya/laya/agent.py#L286-L288) |
| 中文走 `han` 脚本 → multilingual | [lang.py:42](../../laya/laya/lang.py#L42)、[router.py:295-298](../../laya/laya/router.py#L295-L298) |
| 隔离锁只护模型生命周期，推理不串行 | [router.py:167-170](../../laya/laya/router.py#L167-L170) |
| CPU 强制 fp32 | [agent.py:225-227](../../laya/laya/agent.py#L225-L227) |
| 会写 checkpoint 的 tokenizer 配置 | [agent.py:25-50](../../laya/laya/agent.py#L25-L50) |
| 返回结构（choice/score/noul） | [agent.py:337-368](../../laya/laya/agent.py#L337-L368) |
| Java 侧无本地推理 | [java/README.md](../../laya/java/README.md)、[java/pom.xml:22-36](../../laya/java/pom.xml#L22-L36) |
| CPU 延迟 / 冷加载 | 上游 `README.md`（Preload & Memory 表） |
| 温度与 ECE | 上游 `README.md`（Calibration）、`research/README.md` |
| 喵藏侧接入点 | [ClassifyService.java:68-87](../../MiaoCang-Service/src/main/java/com/miaocang/service/ClassifyService.java#L68-L87)、[ChatController.java:39-44](../../MiaoCang-Service/src/main/java/com/miaocang/controller/ChatController.java#L39-L44)、[ExtractPipeline.java:124-137](../../MiaoCang-Service/src/main/java/com/miaocang/service/ExtractPipeline.java#L124-L137) |