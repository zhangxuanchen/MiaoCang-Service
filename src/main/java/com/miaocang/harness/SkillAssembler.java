package com.miaocang.harness;

import com.miaocang.entity.AgentSkill;
import com.miaocang.repository.AgentSkillRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill 装配：阶段运行前按猫拉 enabled 且适用本阶段的技能——
 * EXTRACT 规则拼成规则区、PREFERENCE 拼成主人偏好段；装配即记 usageCount（归因价值）。
 */
@Component
public class SkillAssembler {

    private final AgentSkillRepository skillRepo;

    public SkillAssembler(AgentSkillRepository skillRepo) {
        this.skillRepo = skillRepo;
    }

    /** 拼装本阶段规则区（EXTRACT 规则 + PREFERENCE 偏好）；装配即记 usageCount（归因价值） */
    public String assemble(Long catId, String stage) {
        List<AgentSkill> skills = skillRepo.findByCatIdOrderByKindAscIdAsc(catId);
        StringBuilder rules = new StringBuilder();
        StringBuilder prefs = new StringBuilder();
        for (AgentSkill s : skills) {
            if (!s.isEnabled()) continue;
            String st = s.getStage() == null || s.getStage().isBlank() ? "ALL" : s.getStage();
            if (!"ALL".equals(st) && !st.equals(stage)) continue;
            String content = s.getContent() == null ? "" : s.getContent().strip();
            if (content.isEmpty()) continue;
            if (AgentSkill.KIND_PREFERENCE.equals(s.getKind())) {
                prefs.append("- ").append(content).append('\n');
            } else {
                rules.append("- ").append(content).append('\n');
            }
            s.setUsageCount(s.getUsageCount() + 1);
            skillRepo.save(s);
        }
        StringBuilder sb = new StringBuilder();
        if (!rules.isEmpty()) sb.append("提炼规则：\n").append(rules);
        if (!prefs.isEmpty()) sb.append("主人偏好（必须遵守）：\n").append(prefs);
        return sb.toString();
    }
}
