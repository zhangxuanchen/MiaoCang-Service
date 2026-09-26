package com.miaocang.laya;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * laya 包装配：把 {@link LayaProperties}、{@link LayaDecisionClient} 与意图预判线程池串起来。
 *
 * <p>{@link LayaSidecarManager} 是 {@code @Component}，由 Spring 自动扫描注册。
 */
@Configuration
@EnableConfigurationProperties(LayaProperties.class)
public class LayaConfig {

    /**
     * 意图预判与「领地检索」并发的专用小线程池（守护线程）。
     * 用独立池而非 Tomcat 工作线程：判定是阻塞调用，占住请求线程会拖垮整个 SSE 通道。
     */
    @Bean(name = "layaExecutor", destroyMethod = "shutdown")
    public ExecutorService layaExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "laya-intent");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public LayaDecisionClient layaDecisionClient(LayaProperties props, ObjectMapper mapper) {
        return new LayaDecisionClient(props, mapper);
    }
}