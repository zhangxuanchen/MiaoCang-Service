package com.miaocang.service;

import com.miaocang.entity.BookType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 引力波分类引擎。
 * 每个书籍类型是一个"引力场"，场强由其引力关键词决定；
 * 内容像一颗星星，被命中关键词越多、越靠标题的场吸引得越强。
 *
 * 打分公式：
 *   score(type) = Σ ( 标题命中次数 × titleWeight + min(正文命中次数, cap) )
 * 正文命中次数封顶防止个别高频词刷分；标题权重更高。
 */
@Component
public class GravityEngine {

    @Value("${miaocang.gravity.title-weight:3.0}")
    private double titleWeight;

    @Value("${miaocang.gravity.max-occurrences:8}")
    private int maxOccurrences;

    public record TypeScore(Long typeId, String name, String icon, String color,
                            double score, List<String> matchedKeywords) {}

    /** 对所有类型打分，按得分降序返回（只返回 score>0 的场） */
    public List<TypeScore> scoreAll(List<BookType> types, String title, String body) {
        String t = title == null ? "" : title;
        String b = body == null ? "" : body;
        List<TypeScore> result = new ArrayList<>();
        for (BookType type : types) {
            String[] keywords = parseKeywords(type.getGravityKeywords());
            if (keywords.length == 0) continue;
            double score = 0;
            List<String> matched = new ArrayList<>();
            for (String kw : keywords) {
                if (kw.isBlank()) continue;
                int titleHits = countOccurrences(t, kw);
                int bodyHits = countOccurrences(b, kw);
                int hits = titleHits * 1 + Math.min(bodyHits, maxOccurrences) * 1;
                if (hits > 0) {
                    score += titleHits * titleWeight + Math.min(bodyHits, maxOccurrences);
                    matched.add(kw);
                }
            }
            if (score > 0) {
                result.add(new TypeScore(type.getId(), type.getName(), type.getIcon(),
                        type.getColor(), score, matched));
            }
        }
        result.sort(Comparator.comparingDouble(TypeScore::score).reversed());
        return result;
    }

    /** 关键词按逗号/空白切分 */
    public static String[] parseKeywords(String gravityKeywords) {
        if (gravityKeywords == null || gravityKeywords.isBlank()) return new String[0];
        return gravityKeywords.split("[,，;；\\s]+");
    }

    /** 非重叠出现次数（大小写不敏感） */
    static int countOccurrences(String text, String keyword) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) return 0;
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerKw = keyword.toLowerCase(Locale.ROOT);
        int count = 0;
        int idx = 0;
        while ((idx = lowerText.indexOf(lowerKw, idx)) != -1) {
            count++;
            idx += lowerKw.length();
            if (count > 500) break; // 极端保护
        }
        return count;
    }
}
