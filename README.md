<p align="center">
  <h1 align="center">🧠 agent-memory</h1>
  <p align="center"><strong>AI Agent 文件级跨对话记忆系统</strong></p>
  <p align="center">纯 Markdown · YAML Frontmatter · LLM 侧查询 · 自愈生命周期</p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.13-green" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Tests-47%2F47-brightgreen" alt="Tests">
  <img src="https://img.shields.io/badge/storage-pure_files-black" alt="Storage">
  <img src="https://img.shields.io/badge/AI-AgentScope-purple" alt="AI">
</p>

---

## 📖 这是什么

和 AI 对话时，AI 默认不会记住上次你说了什么。agent-memory 给你的 AI Agent 装上一个**会成长、会遗忘、会自我修正的记忆系统**——像人一样，重要的反复强化，过期的自然归档，矛盾的主动提醒。

> 输入原始对话 `→` LLM 自动分类/命名/摘要/去重 `→` 写入 .md 文件 `→` 凌晨蒸馏 + 衰减 + 归档 `→` 第二天生成摘要和待处理建议

## 🚀 5 分钟上手

```bash
git clone <this-repo>
cd agent-memory

# 配置 LLM
cp src/main/resources/application.yaml.example application.yaml
# 编辑: 填入你的 API key

# 跑测试
./mvnw test          # 47 个测试应该全绿

# 启动
./mvnw spring-boot:run
```

```yaml
# application.yaml
agent-memory:
  base-path: ./data           # 记忆存哪
  project-name: 我的记忆库     # 可读的目录名
  llm:
    provider: openai          # openai | dashscope | ollama
    api-key: ${API_KEY}
    model: gpt-4o-mini
    base-url: https://api.deepseek.com  # DeepSeek 等兼容 API
  kairos:
    enabled: true             # 开夜间蒸馏
    distill-cron: "0 0 2 * * ?"
```

## 🧩 架构

```
data/projects/我的记忆库/
├── memory/
│   ├── MEMORY.md                 ← 索引 (按 relevanceScore 降序)
│   ├── xyz.md                    ← 活跃记忆
│   ├── DAILY_BRIEF.md           ← 每日摘要
│   ├── PENDING_SUGGESTIONS.md   ← 待处理建议
│   └── .archive/                ← 归档区
│       ├── dormant.md           ← 沉睡记忆 (weight < 0.2)
│       └── versions/            ← 合并/更新时的旧版本快照
└── logs/                        ← KAIROS 日志模式
    └── 2026/05/2026-05-05.md
```

## 🏗️ 模块

| 模块 | 职责 |
|------|------|
| `MemoryStorage` | 文件 I/O、原子写入 (.tmp→rename)、路径沙箱、Frontmatter 解析 |
| `MemoryIndex` | 扫描记忆、生成 MEMORY.md、双重截断 (200行/25KB) |
| `MemoryClassifier` | LLM 侧查询 → 自动分类/命名/摘要/去重建议 |
| `MemoryRecall` | LLM 侧查询召回 + related 链展开 + 去噪 |
| `MemoryInjector` | Prompt 组装、忽略指令拦截 |
| `SideQueryClient` | AgentScope SDK 封装 (OpenAI/DashScope/Ollama) |
| `MemorySystem` | **中心门面** — 47 个 API 统一入口 |
| `KairosLogger` | 白天追加对话到日期文件 |
| `NightlyDistiller` | 凌晨全自动: 蒸馏/衰减/归档/建议/卫士/摘要 |

## 📝 记忆类型

| 类型 | 存什么 | 触发示例 |
|------|-------|---------|
| `USER` | 角色、偏好、背景 | "我是资深 Java 开发者，偏好 WebFlux" |
| `FEEDBACK` | 行为纠正和确认 | "不要在测试里 mock 数据库" |
| `PROJECT` | 代码外上下文 | "周五起合并冻结，Q3 合规审计" |
| `REFERENCE` | 外部系统指针 | "Pipeline 缺陷在 Linear INGEST 跟踪" |

## 💻 API 速查

```java
@Autowired MemorySystem m;

// 📥 写入 — LLM 自动分类+命名+摘要
m.save("我偏好响应式编程", Map.of("type", "USER"));

// 📤 召回
m.recall("Java 测试");           // LLM 从全部记忆中挑最相关的 5 条（默认）
m.recallWithRelated("均线湿吻");  // 召回 + 沿 related 链追到底 (默认 -1)

// 自定义
var opts = new MemoryRecall.RecallOptions()
        .maxResults(-1)           // -1 = LLM 可返回所有相关记忆，不限制条数
        .recentTools(Set.of("git"))
        .alreadySurfaced(Set.of("已展示过的路径"));
m.recall("Java 测试", opts, Set.of(), Set.of());
m.recallWithRelated("缠论", opts, Set.of(), Set.of(), 2);  // maxDepth=2 沿链追两层

// 📊 导览 & 统计
m.summary("Java 测试");       // "你可能想回顾: ① 测试规范 ② Java 偏好..."
m.stats().healthSummary();    // "活跃45条 归档8条 | 平均权重0.72 | 濒危3条"

// 🌱 生命周期
m.reinforce("测试规范.md");   // 强化 +0.02
m.decayAll();                 // 全局衰减 (今天召回过的跳过)
m.archiveDormant();           // weight < 0.2 → .archive/
m.awaken("减脂计划");          // 复活 weight=0.5

// 🏷️ 标签
m.listByTag("java");
m.tagStats();                 // 标签云统计

// 📦 归档 & 版本
m.listArchived();             // .archive/ 下所有
m.listVersions("测试规范.md"); // 历史快照

// 🛠️ 维护
m.readIndex();                // MEMORY.md
m.listManifest();             // 所有清单
m.delete("临时笔记");          // 删 + 自动清理死链
m.rebuildIndex();             // 重建索引
```

## 🌱 记忆怎么成长

一条记忆的生命：

```
创建 (weight 0.70)
  │
  ├─ 用户确认"对" → reinforce() → weight ↑
  │
  ├─ 长期没用到 → decayAll() → weight ↓
  │
  ├─ weight > 0.5 → 活跃，正常召回
  ├─ 0.3~0.5     → 衰退，降低优先级
  ├─ 0.20~0.25   → 濒危，主动提醒
  └─ < 0.2       → 沉睡，移到 .archive/
                       │
                  awaken() → 复活 (weight 0.5)
```

### 重要性分级

| Level | 初值 | 衰减 | 适用 |
|-------|------|------|------|
| `CRITICAL` | 0.95 | 极慢 | 根本原则 |
| `HIGH` | 0.80 | 较慢 | 长期偏好 |
| `NORMAL` | 0.70 | 正常 | 默认 |
| `TEMPORAL` | 0.50 | 较快 | 临时事件 |

### 评分公式

```
relevanceScore = weight × 时效因子 × (1 + reinforced × 0.05)
```

## 🌙 睡觉 (KAIROS)

凌晨 02:00 自动执行：

```
distill 昨日日志 → decayAll → archiveDormant
  → detectIssues → checkDeadReferences → writeDailyBrief
```

第二天你会看到：
```markdown
# 2026-05-05 记忆摘要

## 蒸馏
- 新建: 3 条
- 更新: 1 条
- 合并: 0 条
- 归档: 2 条

## 健康
记忆健康度: 活跃45条 归档8条 | 平均权重0.72 | 濒危3条(7%)
```

## 📄 记忆文件长这样

```markdown
---
name: 不要 mock 数据库
description: 集成测试必须连接真实数据库
type: feedback
importance: CRITICAL
weight: 0.95
reinforced: 5
lastRecalled: 2026-05-04T20:00:00Z
decayRate: 0.01
tags: [java testing database]
related: [user_资深java开发者.md]
---

不要在集成测试中 mock 数据库。

**Why:** 上次 mock 与真实数据库差异导致生产事故。
**How to apply:** 集成测试用 Testcontainers 或 H2 内嵌数据库。
```

## 🧪 测试

```bash
./mvnw test                      # 全部 47 个
./mvnw test -Dtest=MemorySystemTest
./mvnw spring-boot:run
```

## 📂 项目文件

```
src/main/java/com/wuuees/li/memory/
├── model/
│   ├── MemoryEntry.java         # 实体 (14 字段 + 生命周期方法)
│   ├── MemoryManifest.java      # 清单 (不含正文)
│   ├── MemoryStats.java         # 统计 record
│   ├── MemoryType.java          # 枚举
│   └── SideQueryResult.java     # LLM 返回
├── storage/MemoryStorage.java
├── index/MemoryIndex.java
├── classifier/MemoryClassifier.java
├── recall/
│   ├── MemoryRecall.java
│   └── SideQueryClient.java
├── injector/MemoryInjector.java
├── config/
│   ├── MemoryProperties.java
│   └── MemoryAutoConfiguration.java
├── kairos/
│   ├── KairosLogger.java
│   └── NightlyDistiller.java
└── service/MemorySystem.java    # 中心门面
```

## 🗺️ V2 计划

- [ ] **Agent 集成** — 接到 AgentScope ReActAgent，对话中自动记忆
- [ ] **记忆对话** — Agent 主动提问："这两条冲突了要合并吗？"
- [ ] **多层级目录** — 按 domain/type 分物理子目录，不破坏索引
- [ ] **知识图谱可视化** — Mermaid/Graphviz 导出所有节点和关联
- [ ] **外部知识注入** — URL 摘要、PDF 解析 → 自动记忆化
- [ ] **Web 面板** — 交互式浏览、搜索、手动管理记忆

## 📚 参考

- 设计规范: `memory/Memory-design.md`
- AgentScope 文档: https://java.agentscope.io/llms-full.txt

## 📄 许可证

MIT
