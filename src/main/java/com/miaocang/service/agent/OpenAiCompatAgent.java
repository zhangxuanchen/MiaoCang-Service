package com.miaocang.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.AgentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容端点实现（/chat/completions）。
 * 适用于 DeepSeek / 智谱 / Kimi / 通义兼容模式 / OpenAI，
 * 以及任何暴露 OpenAI 兼容接口的 Agent 服务（如 agentscope-harness 起的服务）。
 */
public class OpenAiCompatAgent implements MiaoMiaoAgent {

    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是"喵喵"，一只住在个人知识库里、帮主人整理知识体系的猫。
            你会收到主人刚收入库的一条内容（标题、类型、正文），请对它做一次内容提取，严格输出如下 JSON（不要输出 JSON 以外的任何文字，不要用 markdown 代码块包裹）：
            {
              "summary": "一段 100 字以内、说人话的摘要",
              "keyPoints": ["要点1", "要点2", "要点3"],
              "quotes": ["值得原文摘录的金句，最多 1 条，没有就给空数组"],
              "tags": ["3~6 个标签词"],
              "suggest": {"bookId": 书库中已有的书 id 或 null, "bookTitle": "书名", "reason": "归类理由，一两句"},
              "related": [{"contentId": 候选内容 id, "title": "候选标题", "reason": "关联理由"}]
            }
            规则：
            - keyPoints 3~4 条，每条一句话，提炼观点而非复述；
            - suggest 从给定的可选书目里选最合适的（bookId 必须来自候选列表），都不合适才给 null 并在 reason 里说明；
            - related 从候选内容里挑 0~2 条真正相关的，不相关就给空数组；
            - 严格遵守主人喜好（若给出）。
            """;

    private final AgentConfig cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** token 用量回调（喵喵足迹累计用），可为 null */
    private final java.util.function.Consumer<MiaoMiaoAgent.TokenUsage> onUsage;

    /** 最近一次模型调用消耗的 token（extract/chat 后更新）；volatile 保证多线程可见 */
    private volatile MiaoMiaoAgent.TokenUsage lastUsage;

    public OpenAiCompatAgent(AgentConfig cfg, ObjectMapper mapper) {
        this(cfg, mapper, null);
    }

    public OpenAiCompatAgent(AgentConfig cfg, ObjectMapper mapper,
                             java.util.function.Consumer<MiaoMiaoAgent.TokenUsage> onUsage) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.onUsage = onUsage;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String name() {
        return (cfg.getModel() == null ? "openai-compat" : cfg.getModel()) + " @ " + cfg.getBaseUrl();
    }

    @Override
    public MiaoMiaoAgent.TokenUsage lastUsage() { return lastUsage; }

    @Override
    public ExtractResult extract(ExtractTask task) throws Exception {
        String raw = chat(EXTRACT_SYSTEM_PROMPT, buildExtractPrompt(task));
        // 容错：剥掉可能存在的 markdown 代码块围栏
        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        int b = json.indexOf('{'), e = json.lastIndexOf('}');
        if (b >= 0 && e > b) json = json.substring(b, e + 1);

        JsonNode n = mapper.readTree(json);
        Suggestion suggest = null;
        JsonNode sg = n.path("suggest");
        if (sg.isObject()) {
            Long bookId = sg.path("bookId").isNumber() ? sg.path("bookId").asLong() : null;
            suggest = new Suggestion(bookId,
                    sg.path("bookTitle").asText(null),
                    sg.path("reason").asText(""));
        }
        List<RelatedItem> related = new ArrayList<>();
        for (JsonNode r : n.path("related")) {
            if (r.isObject() && r.path("contentId").isNumber()) {
                related.add(new RelatedItem(r.path("contentId").asLong(),
                        r.path("title").asText(""), r.path("reason").asText("")));
            }
        }
        return new ExtractResult(
                n.path("summary").asText(""),
                toList(n.path("keyPoints")),
                toList(n.path("quotes")),
                toList(n.path("tags")),
                suggest,
                related);
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) for (JsonNode x : arr) if (!x.asText("").isBlank()) out.add(x.asText());
        return out;
    }

    /** 统一的对话调用 */
    @Override
    public String chat(String system, String user) throws Exception {
        Map<String, Object> body = Map.of(
                "model", cfg.getModel() == null || cfg.getModel().isBlank() ? "deepseek-chat" : cfg.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "temperature", 0.4,
                "stream", false);

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("模型端点返回 " + resp.statusCode() + ": " + abbreviate(resp.body(), 300));
        }
        JsonNode node = mapper.readTree(resp.body());
        String content = node.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) throw new IllegalStateException("模型返回了空内容");
        // 解析 token 用量（OpenAI 兼容端点的 usage 字段）
        JsonNode usage = node.path("usage");
        if (usage.isObject()) {
            long p = usage.path("prompt_tokens").asLong(0);
            long c = usage.path("completion_tokens").asLong(0);
            long t = usage.path("total_tokens").asLong(p + c);
            lastUsage = new MiaoMiaoAgent.TokenUsage(p, c, t);
            if (onUsage != null) onUsage.accept(lastUsage);
        }
        return content;
    }

    private String buildExtractPrompt(ExtractTask task) {
        StringBuilder sb = new StringBuilder();
        if (task.skillDirectives() != null && !task.skillDirectives().isEmpty()) {
            sb.append("【主人定下的规矩与喜好】\n");
            for (String d : task.skillDirectives()) sb.append("- ").append(d).append("\n");
            sb.append("\n");
        }
        sb.append("【待提取内容】\n标题：").append(task.title()).append("\n");
        sb.append("类型：").append(task.contentType()).append("\n");
        if (task.currentBook() != null && !task.currentBook().isBlank()) {
            sb.append("当前归类：").append(task.currentBook()).append("\n");
        } else {
            sb.append("当前归类：收集箱（尚未归入任何书）\n");
        }
        if (task.summary() != null && !task.summary().isBlank()) {
            sb.append("采集摘要：").append(task.summary()).append("\n");
        }
        sb.append("\n【可选书目】\n");
        if (task.bookOptions() == null || task.bookOptions().isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            for (BookOption o : task.bookOptions()) {
                sb.append("- id=").append(o.bookId()).append(" 《").append(o.bookTitle()).append("》");
                if (o.bookDescription() != null && !o.bookDescription().isBlank()) {
                    sb.append("：").append(abbreviate(o.bookDescription(), 60));
                }
                sb.append("\n");
            }
        }
        sb.append("\n【关联候选（书库其它内容）】\n");
        if (task.candidates() == null || task.candidates().isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            for (RelatedCandidate c : task.candidates()) {
                sb.append("- id=").append(c.contentId()).append(" 《").append(c.title()).append("》");
                if (c.summary() != null && !c.summary().isBlank()) sb.append("：").append(abbreviate(c.summary(), 60));
                sb.append("\n");
            }
        }
        sb.append("\n【正文】\n").append(abbreviate(task.rawText(), 6000));
        sb.append("\n\n请输出提取结果 JSON。");
        return sb.toString();
    }

    private String abbreviate(String s, int len) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= len ? t : t.substring(0, len) + "…";
    }
}
