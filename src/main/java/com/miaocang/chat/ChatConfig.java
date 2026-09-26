package com.miaocang.chat;

import com.miaocang.service.MiaomiaoService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * chat 包装配：把 {@link ChatProperties}、{@link CatWorkspaceService}、
 * {@link ConversationMemory}、{@link ContextSummarizer}、{@link MiaoMiaoAgentFactory}、
 * {@link SingleTurnExecutor}、{@link ChatSessionService} 串成一条会话链。
 *
 * <p>{@link CatWorkspaceService}、{@link ChatSessionService} 是 {@code @Service}，
 * 由 Spring 自动扫描注册，这里只显式声明需要构造参数注入的 Bean。
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    public ConversationMemory conversationMemory(CatWorkspaceService catWorkspace, ChatProperties props) {
        return new ConversationMemory(catWorkspace, props);
    }

    @Bean
    public ContextSummarizer contextSummarizer(MiaomiaoService miaomiao) {
        return new ContextSummarizer(miaomiao);
    }

    @Bean
    public MiaoMiaoAgentFactory miaoMiaoAgentFactory(MiaomiaoService miaomiao,
                                                     CatWorkspaceService catWorkspace,
                                                     com.miaocang.repository.CatRepository catRepo,
                                                     ConversationMemory conversationMemory,
                                                     ChatProperties props,
                                                     com.miaocang.repository.ContentItemRepository contents) {
        return new MiaoMiaoAgentFactory(miaomiao, catWorkspace, catRepo, conversationMemory, props, contents);
    }

    @Bean
    public SingleTurnExecutor singleTurnExecutor(MiaoMiaoAgentFactory agentFactory,
                                                 ConversationMemory conversationMemory,
                                                 ContextSummarizer contextSummarizer,
                                                 ChatProperties props,
                                                 com.miaocang.service.CatEventService eventService) {
        return new SingleTurnExecutor(agentFactory, conversationMemory, contextSummarizer, props, eventService);
    }
}
