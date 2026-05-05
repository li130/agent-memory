package com.wuuees.li.memory.classifier;

import java.util.List;
import java.util.Map;

import com.wuuees.li.memory.model.MemoryManifest;
import com.wuuees.li.memory.model.MemoryType;
import com.wuuees.li.memory.model.SideQueryResult;
import com.wuuees.li.memory.recall.SideQueryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆分类器，通过 LLM 侧查询完成类型判定、命名、摘要和去重建议。
 * 网关层保证 LLM 可用，服务内部不做降级。
 */
public class MemoryClassifier {

    private static final Logger log = LoggerFactory.getLogger(MemoryClassifier.class);

    private final SideQueryClient llm;

    public MemoryClassifier(SideQueryClient llm) {
        this.llm = llm;
    }

    /**
     * 通过 LLM 对内容进行分类，返回类型、名称、描述和去重建议。
     *
     * @param content  待分类内容
     * @param context  元数据上下文（可选 type 提示）
     * @param existing 已有记忆清单（供去重判断）
     */
    public SideQueryResult classifyFull(String content, Map<String, Object> context,
                                        List<MemoryManifest> existing) {
        SideQueryResult result = llm.classify(content, existing);
        log.debug("LLM 分类: type={}, similarTo={}", result.getMemoryType(), result.getSimilarTo());
        return result;
    }

    /**
     * 仅返回类型（向后兼容）。
     */
    public MemoryType classify(String content, Map<String, Object> context) {
        SideQueryResult result = classifyFull(content, context, List.of());
        return result.getMemoryType() != null ? result.getMemoryType() : MemoryType.REFERENCE;
    }
}
