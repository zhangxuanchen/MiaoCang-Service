package com.miaocang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaocang.entity.AgentConfig;
import com.miaocang.entity.CatAgentConfig;
import com.miaocang.repository.AgentConfigRepository;
import com.miaocang.repository.CatAgentConfigRepository;
import com.miaocang.service.agent.MiaoMiaoAgent;
import com.miaocang.service.agent.MockMiaoMiaoAgent;
import com.miaocang.service.agent.OpenAiCompatAgent;
import org.springframework.stereotype.Service;

/**
 * 喵喵 Agent 配置服务：全局兜底配置 + 猫级专属配置（AK 维护），
 * 并按配置产出 {@link MiaoMiaoAgent} 实例（mock / OpenAI 兼容端点）。
 * 阅读流水线、内容提取、聊天会话都经由这里取模型能力。
 */
@Service
public class MiaomiaoService {

    private final AgentConfigRepository agentConfigRepo;
    private final CatAgentConfigRepository catConfigRepo;
    private final ObjectMapper objectMapper;
    private final MockMiaoMiaoAgent mockAgent;
    private final MiaomiaoTraceService trace;

    public MiaomiaoService(AgentConfigRepository agentConfigRepo, CatAgentConfigRepository catConfigRepo,
                           ObjectMapper objectMapper, MockMiaoMiaoAgent mockAgent, MiaomiaoTraceService trace) {
        this.agentConfigRepo = agentConfigRepo;
        this.catConfigRepo = catConfigRepo;
        this.objectMapper = objectMapper;
        this.mockAgent = mockAgent;
        this.trace = trace;
    }

    // ================= Agent 配置（AK 维护） =================

    /** 全局兜底配置（单行，id 恒为 1） */
    public AgentConfig config() {
        return agentConfigRepo.findById(1L).orElseGet(() -> {
            AgentConfig c = new AgentConfig();
            c.setId(1L);
            c.setProvider(AgentConfig.PROVIDER_MOCK);
            return c;
        });
    }

    /** 该猫的猫行配置（未配置过返回 null，前端展示全局默认值） */
    public CatAgentConfig catConfigRow(Long catId) {
        return catConfigRepo.findByCatId(catId).orElse(null);
    }

    /** 该猫生效的配置：猫行字段级优先，空字段（含未填 AK）逐项回退全局预设默认 */
    public AgentConfig config(Long catId) {
        AgentConfig view = new AgentConfig();
        view.setId(1L);
        CatAgentConfig cc = catId == null ? null : catConfigRepo.findByCatId(catId).orElse(null);
        if (cc == null) {
            AgentConfig g = config();
            view.setProvider(g.getProvider());
            view.setBaseUrl(g.getBaseUrl());
            view.setModel(g.getModel());
            view.setApiKey(g.getApiKey());
        } else {
            AgentConfig g = config();
            view.setProvider(blank(cc.getProvider()) ? g.getProvider() : cc.getProvider());
            view.setBaseUrl(blank(cc.getBaseUrl()) ? g.getBaseUrl() : cc.getBaseUrl());
            view.setModel(blank(cc.getModel()) ? g.getModel() : cc.getModel());
            /* 这只猫没填 AK 时直接用全局预设 AK，不落到模拟输出 */
            view.setApiKey(blank(cc.getApiKey()) ? g.getApiKey() : cc.getApiKey());
        }
        return view;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    public AgentConfig saveConfig(AgentConfig c) {
        return agentConfigRepo.save(c);
    }

    /** 保存该猫的专属配置 */
    public CatAgentConfig saveCatConfig(CatAgentConfig cc) {
        cc.setUpdatedAt(java.time.LocalDateTime.now());
        return catConfigRepo.save(cc);
    }

    /** 是否使用真实模型（配置了非 mock 的 provider 且有 AK），按猫判断 */
    public boolean liveMode(Long catId) {
        AgentConfig c = config(catId);
        return !AgentConfig.PROVIDER_MOCK.equals(c.getProvider())
                && c.getApiKey() != null && !c.getApiKey().isBlank();
    }

    public MiaoMiaoAgent agent(Long catId) {
        if (liveMode(catId)) {
            /* 模型 token 用量回调进喵喵足迹（按猫累计） */
            return new OpenAiCompatAgent(config(catId), objectMapper,
                    usage -> trace.addToken(catId, usage.total()));
        }
        return mockAgent;
    }
}
