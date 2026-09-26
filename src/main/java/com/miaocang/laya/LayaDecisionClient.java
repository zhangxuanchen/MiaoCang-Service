package com.miaocang.laya;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * laya 侧车的薄 HTTP 客户端（{@code /health}、{@code /v1/preload}、{@code /v1/predict}）。
 *
 * <p>复用主应用已有的 Jackson 与 JDK {@code HttpClient}，**零新增依赖**（因此不存在上游
 * SDK 那种 jackson-databind 2.17.1 与 Spring Boot BOM 的版本冲突）。线程安全，做成单例 Bean。
 *
 * <p>失败一律抛 {@link LayaUnavailableException}，**不重试**——重试只会把延迟叠上去，
 * 调用方（分类 / 意图）宁可立刻降级走原路径。
 */
public class LayaDecisionClient {

    /** 连接超时：同机 loopback，1 秒足够。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1000);

    private final LayaProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public LayaDecisionClient(LayaProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    // ---------------------------------------------------------------- 运维端点

    /** GET /health 原始载荷（status / loaded / models / version）。 */
    public Map<String, Object> health(long timeoutMs) {
        JsonNode n = send("GET", "/health", null, timeoutMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", n.path("status").asText(""));
        out.put("version", n.path("version").asText(""));
        List<String> loaded = new ArrayList<>();
        for (JsonNode x : n.path("loaded")) loaded.add(x.asText());
        out.put("loaded", loaded);
        return out;
    }

    /** 侧车已常驻的 checkpoint 列表；不可达时抛异常。 */
    @SuppressWarnings("unchecked")
    public List<String> loadedModels(long timeoutMs) {
        return (List<String>) health(timeoutMs).get("loaded");
    }

    /** 让侧车预热指定 checkpoint（补充预加载用）。 */
    public void preload(List<String> models) {
        Map<String, Object> body = Map.of("models", models);
        send("POST", "/v1/preload", body, 60_000L);
    }

    // ---------------------------------------------------------------- 推理端点

    /**
     * 跑一次判定。{@code model} 由配置决定：默认固定 multilingual；
     * 配成 {@code auto}（或留空）时不下发 model，交给侧车按脚本/语言自动路由。
     */
    public LayaPrediction predict(Object state, Map<String, Object> questions, long timeoutMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("questions", questions);
        String model = props.getModel();
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            body.put("model", model);
        }
        JsonNode root = send("POST", "/v1/predict", body, timeoutMs);
        return new LayaPrediction(root);
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode send(String method, String path, Object body, long timeoutMs) {
        String url = props.getBaseUrl().replaceAll("/+$", "") + path;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json");
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.method(method, HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8));
                b.header("Content-Type", "application/json; charset=utf-8");
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LayaUnavailableException("侧车返回 HTTP " + resp.statusCode()
                        + ": " + abbreviate(resp.body(), 300));
            }
            return mapper.readTree(resp.body());
        } catch (LayaUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LayaUnavailableException("侧车调用被中断", e);
        } catch (Exception e) {
            throw new LayaUnavailableException("侧车调用失败（" + url + "）: " + e, e);
        }
    }

    private static String abbreviate(String s, int len) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= len ? t : t.substring(0, len) + "…";
    }

    // ---------------------------------------------------------------- 结果视图

    /** 一次判定的结果视图（answers + routing + 用量）。 */
    public record LayaPrediction(JsonNode root) {

        public LayaAnswer answer(String qid) {
            return new LayaAnswer(qid, root.path("answers").path(qid));
        }

        /** 实际使用的 checkpoint（配置固定 multilingual 时即 multilingual）。 */
        public String routedModel() {
            return root.path("routing").path("model").asText("");
        }

        /** 侧车自己记录的耗时（毫秒）。 */
        public double latencyMs() {
            return root.path("latency_ms").asDouble(0);
        }

        public int inputTokens() {
            return root.path("usage").path("input_tokens").asInt(0);
        }
    }

    /** 单个问题的答案视图（三种原语共用）。 */
    public record LayaAnswer(String qid, JsonNode node) {

        public String type() {
            return node.path("type").asText("");
        }

        public String choice() {
            String c = node.path("choice").asText("");
            return c.isBlank() ? null : c;
        }

        public Double score() {
            return node.hasNonNull("score") ? node.path("score").asDouble() : null;
        }

        /** P(true)，仅 noul 原语有值。 */
        public Double noul() {
            return node.hasNonNull("noul") ? node.path("noul").asDouble() : null;
        }

        /** 1 − 归一化熵。注意：温度校准前**不可信**，不要拿它做门控。 */
        public double confidence() {
            return node.path("confidence").asDouble(0);
        }

        public Map<String, Double> probabilities() {
            Map<String, Double> out = new LinkedHashMap<>();
            JsonNode p = node.path("probabilities");
            if (p.isObject()) {
                java.util.Iterator<String> names = p.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    out.put(name, p.path(name).asDouble());
                }
            }
            return out;
        }

        /**
         * 概率最高的两个值 {p1, p2}（不足两个时缺位补 0）。
         * 归档/短路门控用它而不是 confidence —— 详见方案 §4.2。
         */
        public double[] topTwo() {
            List<Double> ps = new ArrayList<>(probabilities().values());
            ps.sort(Comparator.reverseOrder());
            return new double[]{
                    ps.size() > 0 ? ps.get(0) : 0,
                    ps.size() > 1 ? ps.get(1) : 0};
        }
    }
}