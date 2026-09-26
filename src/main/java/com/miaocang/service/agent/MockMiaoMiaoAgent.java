package com.miaocang.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模拟输出：不依赖任何模型，按"喵喵按 + 分章节摘录 + 收录清单"的结构拼出一篇
 * 有模有样的综述，用于在接入真实模型前跑通全流程。
 */
@Component
public class MockMiaoMiaoAgent implements MiaoMiaoAgent {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;

    public MockMiaoMiaoAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "模拟输出（未接入真实模型）";
    }

    /**
     * 自由对话（喵的整理等元任务用），按 system prompt 分流：
     * - 精读（system 含「理解卡片」）：单篇文章/对话 → 1~2 张模拟理解卡片
     * - 归纳（其余）：全部理解卡片行 → 按出处分组拼出几本二级主题书的大纲 JSON
     */
    @Override
    public String chat(String system, String user) throws Exception {
        if (system != null && system.contains("理解卡片")) return mockCards(user);
        return mockOutline(user);
    }

    /** 精读 mock：从标题/主人发言里抽词，产出 2 张模拟理解卡片 */
    private String mockCards(String user) throws Exception {
        String title = null;
        String owner = null;
        String bodyHead = null;
        for (String line : user.split("\n")) {
            String t = line.strip();
            if (t.startsWith("- 标题：《") && t.endsWith("》")) {
                title = t.substring(6, t.length() - 1);
            } else if (t.startsWith("- 主人：") && owner == null) {
                owner = t.substring(5).strip();
            } else if (t.startsWith("正文：")) {
                bodyHead = abbrev(user.substring(user.indexOf(t) + 3).strip(), 40);
            }
        }
        String seed = title != null ? title : (owner != null ? owner : "知识");
        String head = bodyHead != null ? bodyHead : (owner != null ? abbrev(owner, 40) : "（正文较短）");
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(card("「" + abbrev(seed, 10) + "」的要点", "读完后记住的核心：围绕「" + abbrev(seed, 16) + "」展开，" + head + "（模拟理解，未接入真实模型）。"));
        cards.add(card("可延伸的问题", "这篇文章引出的思考：如何把「" + abbrev(seed, 12) + "」用到实际整理中（模拟理解）。"));
        return objectMapper.writeValueAsString(cards);
    }

    private Map<String, Object> card(String topic, String point) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", topic);
        m.put("point", point);
        return m;
    }

    /** 归纳 mock：把卡片行按出处分组，每处一本书、每 2 张卡片一个二级主题 */
    private String mockOutline(String user) throws Exception {
        /* 卡片行格式：- C1「topic」point（出处：《src》） */
        record Card(String no, String topic, String point, String src) {}
        List<Card> cards = new ArrayList<>();
        for (String line : user.split("\n")) {
            String t = line.strip();
            if (!t.startsWith("- C") || !t.contains("「")) continue;
            try {
                String no = t.substring(2, t.indexOf('「'));
                int lq = t.indexOf('「') + 1, rq = t.indexOf('」');
                String topic = t.substring(lq, rq);
                String rest = t.substring(rq + 1);
                String src = t.contains("（出处：《") ? t.substring(t.indexOf("（出处：《") + 5, t.length() - 2) : "素材";
                String point = rest.contains("（出处：《") ? rest.substring(0, rest.indexOf("（出处：《")).strip() : rest.strip();
                cards.add(new Card(no, topic, point, src));
            } catch (Exception ignored) {
                // 解析不了的行直接跳过
            }
        }
        // 按出处分组，每组一本书
        Map<String, List<Card>> bySrc = new LinkedHashMap<>();
        for (Card c : cards) bySrc.computeIfAbsent(c.src(), k -> new ArrayList<>()).add(c);

        List<Map<String, Object>> books = new ArrayList<>();
        int g = 0;
        for (Map.Entry<String, List<Card>> e : bySrc.entrySet()) {
            List<Card> grp = e.getValue();
            if (books.size() >= 6 && !"与主人的会话".equals(e.getKey())) continue; // 最多 6 本
            List<Map<String, Object>> secs = new ArrayList<>();
            for (int i = 0; i < grp.size(); i += 2) {
                Card first = grp.get(i);
                List<String> cardNos = new ArrayList<>();
                for (int j = i; j < Math.min(i + 2, grp.size()); j++) cardNos.add(grp.get(j).no());
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", abbrev(first.topic(), 12));
                s.put("point", "收拢「" + abbrev(first.topic(), 10) + "」一带的理解（模拟分层）。");
                s.put("cards", cardNos);
                secs.add(s);
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("title", "与主人的会话".equals(e.getKey()) ? "与主人的对话拾遗" : mockTitle(grp.get(0).topic(), g++));
            b.put("description", "这本书主要记录「" + abbrev(e.getKey(), 14) + "」来源的 " + grp.size()
                    + " 张理解卡片（模拟大纲，未接入真实模型）。");
            b.put("sections", secs);
            books.add(b);
        }
        if (books.isEmpty()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("title", "空白之书");
            b.put("description", "这只喵还没有任何素材（模拟大纲）。");
            b.put("sections", new ArrayList<>());
            books.add(b);
        }
        return objectMapper.writeValueAsString(books);
    }

    /** 从种子条目里抽最长的中文/字母数字连续段当书名 */
    private String mockTitle(String seed, int g) {
        String best = "";
        for (String w : seed.split("[^\\u4e00-\\u9fa5A-Za-z0-9]+")) {
            if (w.length() > best.length()) best = w;
        }
        if (best.isEmpty()) return "知识卷 " + (g + 1);
        return best.length() > 8 ? best.substring(0, 8) : best;
    }

    private String abbrev(String s, int len) {
        if (s == null) return "";
        return s.length() <= len ? s : s.substring(0, len) + "…";
    }

    // ==================== 内容提取（模拟规则版） ====================

    @Override
    public ExtractResult extract(ExtractTask task) {
        String text = task.rawText() == null ? "" : task.rawText().strip();
        List<String> sentences = splitSentences(text);

        // 摘要：优先用采集时已有的 summary，否则取正文开头的整句
        String summary = task.summary() != null && !task.summary().isBlank()
                ? task.summary()
                : (sentences.isEmpty() ? "（正文较短，暂无可提炼的摘要）" : joinUpTo(sentences, 3, 120));

        // 要点：挑信息量最大的几句话（更长、含数字或冒号的优先）
        List<String> keyPoints = new ArrayList<>();
        sentences.stream()
                .filter(s -> s.length() >= 8 && s.length() <= 120)
                .sorted((a, b) -> score(b) - score(a))
                .limit(4)
                .forEach(keyPoints::add);
        if (keyPoints.isEmpty() && !sentences.isEmpty()) keyPoints.add(sentences.get(0));

        // 金句：长度适中、带点文气的句子，最多 1 条，没有就不硬凑
        List<String> quotes = new ArrayList<>();
        sentences.stream()
                .filter(s -> s.length() >= 15 && s.length() <= 60)
                .filter(s -> s.contains("，") || s.contains("——"))
                .findFirst()
                .ifPresent(quotes::add);

        // 标签：优先沿用命中关键词，再从标题/正文抽高频词
        Set<String> tags = new LinkedHashSet<>();
        if (task.existingTags() != null) tags.addAll(task.existingTags());
        extractKeywords(task.title(), tags);
        extractKeywords(text, tags);
        if (tags.size() > 6) tags = new LinkedHashSet<>(new ArrayList<>(tags).subList(0, 6));

        // 归类建议：模拟版默认保持现状，说明理由
        Suggestion suggest = new Suggestion(null, task.currentBook(),
                "模拟输出：维持现状。接入真实模型后，喵喵会根据内容语义给出更合适的落点。");

        // 关联：从候选里挑标题或标签有重叠的，最多 2 条
        List<RelatedItem> related = new ArrayList<>();
        for (RelatedCandidate c : task.candidates()) {
            if (related.size() >= 2) break;
            String reason = matchReason(c, tags, task.title());
            if (reason != null) related.add(new RelatedItem(c.contentId(), c.title(), reason));
        }

        return new ExtractResult(summary, keyPoints, quotes, new ArrayList<>(tags), suggest, related);
    }

    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String chunk : text.split("[。！？；\\n]+")) {
            String s = chunk.strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String joinUpTo(List<String> sentences, int n, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size() && i < n && sb.length() < maxLen; i++) {
            if (sb.length() > 0) sb.append("。");
            sb.append(sentences.get(i));
        }
        String s = sb.toString();
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private int score(String s) {
        int n = Math.min(s.length(), 60);
        if (s.contains("：") || s.contains(":")) n += 10;
        if (s.matches(".*\\d+.*")) n += 6;
        return n;
    }

    private void extractKeywords(String text, Set<String> tags) {
        if (text == null || text.isBlank()) return;
        // 2~4 字中文词的粗略抽取：取标题和正文前 200 字里出现的重复片段
        String sample = text.length() > 200 ? text.substring(0, 200) : text;
        for (String kw : sample.split("[^\\u4e00-\\u9fa5A-Za-z0-9]+")) {
            if (kw.length() >= 2 && kw.length() <= 6 && tags.size() < 6) tags.add(kw);
        }
    }

    private String matchReason(RelatedCandidate c, Set<String> tags, String title) {
        for (String t : tags) {
            if ((c.title() != null && c.title().contains(t)) || (c.summary() != null && c.summary().contains(t))) {
                return "同讲「" + t + "」，可与本条互相印证。";
            }
        }
        if (title != null && c.title() != null) {
            for (String w : title.split("[\\s\\p{Punct}]+")) {
                if (w.length() >= 2 && c.title().contains(w)) return "标题都提到「" + w + "」。";
            }
        }
        return null;
    }
}
