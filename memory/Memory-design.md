# 记忆系统设计参考

## 灵感来源

本项目的核心设计来源于 Claude Code 内置的 Memory 子系统。Claude Code 使用纯文件存储 + MEMORY.md 索引 + 四类型分类 + Sonnet 侧查询召回。以下是从其源码分析中提取的关键设计决策。

## 存储架构

纯文件，零数据库依赖：

```
~/.claude/projects/<sanitized-git-root>/memory/
├── MEMORY.md              ← 入口索引（每次对话加载）
├── user_role.md
├── feedback_testing.md
├── project_release.md
├── reference_linear.md
└── logs/                  ← KAIROS 模式
    └── YYYY/MM/DD.md
```

同一 Git 仓库的所有 worktree 共享一个记忆目录。

## MEMORY.md 索引

- 纯文本链接列表，无 frontmatter
- 格式：`- [Title](file.md) — one-line hook`，每条 ≤ 150 字符
- 双重截断：200 行 **且** 25 KB，任一超限即截断追加警告

## 四类型分类

| 类型 | 存储 | 触发 |
|------|------|------|
| user | 角色、偏好、技术背景 | "我是数据科学家" |
| feedback | 纠正和确认 | "别 mock 数据库" / "对就是这样" |
| project | 非代码可推导的上下文 | "合并冻结周四开始" |
| reference | 外部系统指针 | "pipeline bugs 在 Linear INGEST" |

**关键约束**：只存无法从当前项目状态推导的信息。代码架构、文件路径、Git 历史都可在运行时获取。

**反馈双通道**：既记录纠正（用户说"不要X"），也记录确认（用户说"对就这样"），防止行为漂移。

## 智能召回

轻量 LLM 侧查询 → JSON Schema 输出，从所有记忆中选出 ≤ 5 条最相关的。

去噪策略：
- `recentTools`：排除当前正在使用的工具的参考文档（但保留警告/陷阱）
- `alreadySurfaced`：排除已在对话中展示过的路径
- 时间衰减：`0.9^(days/7)`

## Prompt 注入

记忆内容作为 user 角色消息注入（非 system），利用 Prefix Cache 共享。注入顺序：MEMORY.md → 召回记忆正文 → 用户实际输入。

## KAIROS 日志模式

长期运行的 Agent 不直接写记忆文件，而是追加日志到 `logs/YYYY/MM/DD.md`。夜间由 `/dream` 技能蒸馏为结构化记忆。

## 防御机制

| 机制 | 说明 |
|------|------|
| 漂移防御 | 引用文件/函数前必须先验证存在性 |
| 严格忽略 | 用户说"忽略记忆" → 全链路屏蔽，不提及 |
| 路径沙箱 | 所有路径 realpath + 前缀校验，禁止 `../` |
| 原子写入 | `.tmp → rename`，杜绝写一半崩溃 |

## 实施阶段

| 阶段 | 内容 | V1 状态 |
|------|------|---------|
| Phase 1 | 目录初始化、原子写入、MEMORY.md、关键词召回、Prompt 注入 | ✅ |
| Phase 2 | LLM 侧查询、recentTools/alreadySurfaced 去噪、结构化日志 | ✅ |
| Phase 3 | 并发锁、漂移防御、严格忽略拦截、缓存热更新 | ✅ 核心已实现 |
| Phase 4 | KAIROS 日志、夜间蒸馏、CLI 调试 | ✅ |

## 与原始设计的差异

V1 在 Claude Code 原始设计基础上做了以下增强：

- **生命周期**：weight 动态权重、强化/衰减/归档/苏醒
- **双向关联**：related 自动同步，删改时清理死链
- **可观测性**：stats/healthSummary/summary 导览
- **主动建议**：冲突检测、合并建议、过期提醒
- **重要性分级**：CRITICAL/HIGH/NORMAL/TEMPORAL 四级
- **变更历史**：合并时自动快照旧版本
- **标签分组**：tags 逻辑分组
- **project-name**：人类可读的目录名替代纯 hash
