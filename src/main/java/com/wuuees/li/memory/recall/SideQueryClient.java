package com.wuuees.li.memory.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuuees.li.memory.config.MemoryProperties;
import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.model.SideQueryResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 侧查询客户端，基于 AgentScope SDK。
 * 一次轻量 API 调用同时完成：分类 + 去重 + 召回。
 * 支持 OpenAI / DashScope / Ollama 三种 provider。
 */
public class SideQueryClient {

    private static final Logger log = LoggerFactory.getLogger(SideQueryClient.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Object model; // OpenAIChatModel | DashScopeChatModel | OllamaChatModel
    private final GenerateOptions options;

    /** 侧查询超时 5 秒 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public SideQueryClient(MemoryProperties.Llm config) {
        this.model = buildModel(config);
        this.options = GenerateOptions.builder()
                .temperature(0.0)
                .maxTokens(256)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(TIMEOUT)
                        .maxAttempts(1)
                        .build())
                .build();
        log.info("SideQueryClient 初始化完成: provider={}, model={}", config.getProvider(), config.getModel());
    }

    // ── 模型构建 ────────────────────────────────────────────

    private static Object buildModel(MemoryProperties.Llm config) {
        String provider = config.getProvider() != null ? config.getProvider().toLowerCase() : "openai";
        String apiKey = config.getApiKey();
        String modelName = config.getModel();
        String baseUrl = config.getBaseUrl();

        return switch (provider) {
            case "dashscope" -> {
                var b = DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName);
                if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
                yield b.build();
            }
            case "ollama" -> {
                var b = OllamaChatModel.builder().modelName(modelName);
                if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
                yield b.build();
            }
            default -> {
                var b = OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName);
                if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
                yield b.build();
            }
        };
    }

    /**
     * 通用 LLM 调用，发送 system + user 消息，返回原始文本。
     * 供蒸馏器等需要自由格式输出的场景使用。
     */
    public String rawCall(String userMessage, String systemPrompt) throws Exception {
        return call(systemPrompt, userMessage);
    }

    /**
     * 调用底层 LLM，发送 system + user 消息，返回文本响应。
     */
    private String call(String systemPrompt, String userMessage) throws Exception {
        Msg msg = Msg.builder().textContent(systemPrompt + "\n\n---\n\n" + userMessage).build();

        Flux<ChatResponse> flux = invokeStream(model, List.of(msg));
        List<ChatResponse> responses = flux.collectList().block(Duration.ofSeconds(6));
        if (responses == null || responses.isEmpty()) {
            throw new RuntimeException("LLM 响应为空或超时");
        }

        // 从最后一个响应的内容中提取文本
        for (int i = responses.size() - 1; i >= 0; i--) {
            ChatResponse resp = responses.get(i);
            String text = extractText(resp);
            if (text != null && !text.isBlank()) return text;
        }
        throw new RuntimeException("LLM 响应中无文本内容");
    }

    @SuppressWarnings("unchecked")
    private Flux<ChatResponse> invokeStream(Object m, List<Msg> messages) {
        if (m instanceof OpenAIChatModel o) return o.stream(messages, List.of(), options);
        if (m instanceof DashScopeChatModel d) return d.stream(messages, List.of(), options);
        if (m instanceof OllamaChatModel o) return o.stream(messages, List.of(), options);
        throw new IllegalStateException("未知模型类型: " + m.getClass().getName());
    }

    private String extractText(ChatResponse response) {
        if (response.getContent() == null) return null;
        for (var block : response.getContent()) {
            if (block instanceof TextBlock tb) return tb.getText();
        }
        return null;
    }

    // ── 分类侧查询 ──────────────────────────────────────────

    /**
     * 对输入内容进行分类，返回 LLM 判定的类型、名称、描述和去重建议。
     */
    public SideQueryResult classify(String content, List<MemoryManifest> existing) {
        String manifestText = buildManifestText(existing);
        String sysPrompt = """
                你是记忆分类助手。根据以下规则对用户输入进行分类：

                ## 四种类型
                - USER：用户角色声明、技术偏好、工作习惯
                - FEEDBACK：对 AI 行为的纠正或确认
                - PROJECT：业务规则、截止日期、合规要求、无法从代码推导的决策
                - REFERENCE：外部系统链接、文档指针、第三方名称

                ## 规则
                1. FEEDBACK 优先级最高
                2. 如果与已有记忆高度相似，将 similarTo 设为已有记忆名
                3. 否则 similarTo 为 null
                4. name 取核心主题，≤60 字符
                5. description 取一句话摘要，≤120 字符

                只返回 JSON。""";

        try {
            String raw = call(sysPrompt, buildJsonInstruction(
                    "要保存的内容：\n" + content + "\n\n已有记忆清单：\n" + manifestText, 5));
            return parseClassification(raw);
        } catch (Exception e) {
            log.warn("LLM 分类失败: {}", e.getMessage());
            return null;
        }
    }

    // ── 召回侧查询 ──────────────────────────────────────────

    /**
     * 从候选清单中选出最相关的 Top-N 记忆路径。
     */
    public List<String> recall(String query, List<MemoryManifest> candidates,
                               List<String> recentTools, List<String> alreadySurfaced,
                               int maxResults) {
        String manifestText = buildManifestText(candidates);
        StringBuilder userText = new StringBuilder();
        userText.append("查询：").append(query).append("\n\n候选记忆：\n").append(manifestText);

        if (recentTools != null && !recentTools.isEmpty()) {
            userText.append("\n\n最近使用的工具：").append(String.join(", ", recentTools));
            userText.append(" —— 不要选这些工具的参考文档，但可以选警告/陷阱。");
        }
        if (alreadySurfaced != null && !alreadySurfaced.isEmpty()) {
            userText.append("\n\n已展示的路径（不重复选）：\n");
            for (String p : alreadySurfaced) userText.append("- ").append(p).append("\n");
        }

        String limitRule = maxResults <= 0
                ? "返回所有相关记忆，不限数量，按相关度降序"
                : "最多选 " + maxResults + " 条，按相关度降序";

        String sysPrompt = """
                你是记忆召回助手。从候选清单中选出与查询最相关的记忆文件。

                ## 规则
                1. %s
                2. 优先语义相关而非字面匹配
                3. 排除 REFERENCE 类型中匹配 recentTools 的条目
                4. 排除 alreadySurfaced 中已展示的路径

                只返回 JSON。""".formatted(limitRule);

        try {
            String raw = call(sysPrompt, buildJsonInstruction(userText.toString(), maxResults));
            return parseRecallPaths(raw);
        } catch (Exception e) {
            log.warn("LLM 召回失败: {}", e.getMessage());
            return null;
        }
    }

    // ── JSON Schema 指令（拼入 user message 末尾）───────────

    private String buildJsonInstruction(String userMessage, int maxResults) {
        try {
            ObjectNode schema = buildOutputSchema(maxResults);
            String schemaStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            return userMessage + "\n\n按以下 JSON Schema 返回，只返回 JSON：\n```json\n" + schemaStr + "\n```";
        } catch (JsonProcessingException e) {
            return userMessage;
        }
    }

    private ObjectNode buildOutputSchema(int maxResults) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode props = mapper.createObjectNode();

        ObjectNode actionProp = mapper.createObjectNode();
        actionProp.put("type", "string");
        ArrayNode actionEnum = mapper.createArrayNode();
        for (SideQueryResult.Action a : SideQueryResult.Action.values()) actionEnum.add(a.name());
        actionProp.set("enum", actionEnum);
        props.set("action", actionProp);

        ObjectNode typeProp = mapper.createObjectNode();
        typeProp.put("type", "string");
        ArrayNode typeEnum = mapper.createArrayNode();
        for (MemoryType t : MemoryType.values()) typeEnum.add(t.name());
        typeProp.set("enum", typeEnum);
        props.set("memoryType", typeProp);

        ObjectNode nameProp = mapper.createObjectNode();
        nameProp.put("type", "string");
        nameProp.put("description", "记忆标题，≤60 字符");
        props.set("name", nameProp);

        ObjectNode descProp = mapper.createObjectNode();
        descProp.put("type", "string");
        descProp.put("description", "一句话摘要，≤120 字符");
        props.set("description", descProp);

        ObjectNode simProp = mapper.createObjectNode();
        ArrayNode simTypes = mapper.createArrayNode();
        simTypes.add("string"); simTypes.add("null");
        simProp.set("type", simTypes);
        simProp.put("description", "应合并到的已有记忆名，没有则为 null");
        props.set("similarTo", simProp);

        ObjectNode pathsProp = mapper.createObjectNode();
        pathsProp.put("type", "array");
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        pathsProp.set("items", items);
        if (maxResults > 0) pathsProp.put("maxItems", maxResults);
        pathsProp.put("description", "相关路径列表，按相关度降序");
        props.set("relevantPaths", pathsProp);

        ObjectNode reasonProp = mapper.createObjectNode();
        reasonProp.put("type", "string");
        reasonProp.put("description", "一行原因说明");
        props.set("reason", reasonProp);

        root.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("action");
        root.set("required", required);
        return root;
    }

    // ── JSON 解析 ───────────────────────────────────────────

    private SideQueryResult parseClassification(String raw) throws JsonProcessingException {
        JsonNode json = extractJsonNode(raw);
        SideQueryResult result = new SideQueryResult();

        if (json.has("action"))
            result.setAction(SideQueryResult.Action.valueOf(json.get("action").asText().toUpperCase()));
        if (json.has("memoryType"))
            result.setMemoryType(MemoryType.valueOf(json.get("memoryType").asText().toUpperCase()));
        if (json.has("name")) result.setName(json.get("name").asText());
        if (json.has("description")) result.setDescription(json.get("description").asText());
        if (json.has("similarTo") && !json.get("similarTo").isNull())
            result.setSimilarTo(json.get("similarTo").asText());
        if (json.has("reason")) result.setReason(json.get("reason").asText());

        return result;
    }

    private List<String> parseRecallPaths(String raw) throws JsonProcessingException {
        JsonNode json = extractJsonNode(raw);
        List<String> paths = new ArrayList<>();
        if (json.has("relevantPaths")) {
            for (JsonNode p : json.get("relevantPaths")) paths.add(p.asText());
        }
        return paths;
    }

    private JsonNode extractJsonNode(String raw) throws JsonProcessingException {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("\n");
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) trimmed = trimmed.substring(start, end).trim();
        }
        return mapper.readTree(trimmed);
    }

    // ── 辅助 ────────────────────────────────────────────────

    private String buildManifestText(List<MemoryManifest> candidates) {
        if (candidates == null || candidates.isEmpty()) return "（空）";
        StringBuilder sb = new StringBuilder();
        for (MemoryManifest m : candidates) {
            sb.append("- [").append(m.getPath().getFileName()).append("] ")
                    .append(m.getName()).append(" (").append(m.getType()).append(")")
                    .append(" — ").append(m.getDescription() != null ? m.getDescription() : "");
            sb.append("\n");
        }
        return sb.toString();
    }
}
