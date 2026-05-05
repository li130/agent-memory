<p align="center">
  <h1 align="center">🧠 agent-memory</h1>
  <p align="center"><strong>File-Based Cross-Session Memory for AI Agents</strong></p>
  <p align="center">Pure Markdown · YAML Frontmatter · LLM Side-Query · Self-Healing Lifecycle</p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.13-green" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Tests-47%2F47-brightgreen" alt="Tests">
  <img src="https://img.shields.io/badge/storage-pure_files-black" alt="Storage">
  <img src="https://img.shields.io/badge/AI-AgentScope-purple" alt="AI">
</p>

---

A Java port of the Claude Code memory subsystem. AI agents don't remember across conversations by default — this gives them a memory that grows, forgets, and self-corrects.

> raw input → LLM classifies/names/summarizes → atomic write to `.md` → nightly distillation → next-day brief + suggestions

[中文文档](README.md)

## Quick Start

```bash
git clone https://github.com/li130/agent-memory.git
cd agent-memory

# Edit application.yaml with your API key
./mvnw test          # 47 tests should pass
./mvnw spring-boot:run
```

```yaml
agent-memory:
  base-path: ./data
  project-name: my-memory
  llm:
    provider: openai           # openai | dashscope | ollama
    api-key: ${API_KEY}
    model: gpt-4o-mini
    base-url: https://api.deepseek.com   # optional, for compatible APIs
  kairos:
    enabled: true              # nightly distillation
    distill-cron: "0 0 2 * * ?"
```

## Architecture

```
data/projects/my-memory/
├── memory/
│   ├── MEMORY.md               ← Index (sorted by relevanceScore)
│   ├── xyz.md                  ← Active memories
│   ├── DAILY_BRIEF.md         ← Generated each night
│   ├── PENDING_SUGGESTIONS.md ← Issues the system found
│   └── .archive/              ← Dormant memories (weight < 0.2)
│       └── versions/          ← Historical snapshots on merge
└── logs/                      ← KAIROS daily logs
    └── YYYY/MM/YYYY-MM-DD.md
```

## Modules

| Module | Role |
|--------|------|
| `MemoryStorage` | File I/O, atomic writes (`.tmp→rename`), path sandboxing |
| `MemoryIndex` | Scan & generate `MEMORY.md`, dual-limit truncation (200 lines / 25KB) |
| `MemoryClassifier` | LLM side-query → auto-classify, name, summarize, dedup |
| `MemoryRecall` | LLM side-query recall + `related` chain traversal + noise filter |
| `MemoryInjector` | Prompt assembly, ignore-instruction detection |
| `SideQueryClient` | AgentScope SDK wrapper (OpenAI / DashScope / Ollama) |
| `MemorySystem` | **Central facade** — 47 APIs, one entry point |
| `KairosLogger` | Append daily conversations to date-stamped log files |
| `NightlyDistiller` | Nightly cron: distill → decay → archive → suggestions → dead-ref check → brief |

## Memory Types

| Type | Stores | Example |
|------|--------|---------|
| `USER` | Role, preferences, background | "I'm a senior Java dev, prefer WebFlux" |
| `FEEDBACK` | Corrections & confirmations | "Don't mock the database in tests" |
| `PROJECT` | Context not in code | "Merge freeze starts Friday for Q3" |
| `REFERENCE` | External system pointers | "Pipeline bugs in Linear INGEST" |

## API Overview

```java
@Autowired MemorySystem m;

// Write — LLM handles classification, naming, summarization
m.save("I prefer reactive programming", Map.of("type", "USER"));

// Recall
m.recall("Java testing");          // LLM picks top 5
m.recallWithRelated("topic");      // + follows related chain to end

// Unlimited recall for large-context models (DeepSeek 1M tokens)
var opts = new MemoryRecall.RecallOptions().maxResults(-1);
m.recall("query", opts, Set.of(), Set.of());

// Overview & stats
m.summary("Java testing");         // "You might want to recall: ① Testing rules ② Java pref..."
m.stats().healthSummary();         // "45 active, 8 archived | avg weight 0.72 | 3 near-dormant"

// Lifecycle
m.reinforce("testing-rules.md");   // +0.02 weight boost
m.decayAll();                      // decay all (skips memories recalled today)
m.archiveDormant();                // weight < 0.2 → .archive/
m.awaken("old-memory");            // resurrect with weight 0.5

// Tags
m.listByTag("java");
m.tagStats();

// Archive & versions
m.listArchived();
m.listVersions("testing-rules.md");

// Maintenance
m.readIndex();                     // MEMORY.md content
m.listManifest();                  // all memory entries
m.delete("temp-note");             // auto-cleans dead references
m.rebuildIndex();
```

## Memory Lifecycle

```
Created (weight 0.70)
  │
  ├─ User confirms → reinforce() → weight ↑
  ├─ Unused for long → decayAll() → weight ↓
  │
  ├─ weight > 0.5  → active, normal recall
  ├─ 0.3~0.5       → declining, lower priority
  ├─ 0.20~0.25     → near-dormant, system alerts
  └─ < 0.2         → archived to .archive/
                        │
                   awaken() → resurrected (weight 0.5)
```

### Importance Levels

| Level | Init Weight | Decay | Use case |
|-------|------------|-------|----------|
| `CRITICAL` | 0.95 | minimal | core principles |
| `HIGH` | 0.80 | slow | long-term preferences |
| `NORMAL` | 0.70 | normal | default |
| `TEMPORAL` | 0.50 | fast | temporary events |

### Relevance Score

```
relevanceScore = weight × recency_factor × (1 + reinforced × 0.05)
```

## Nightly Distillation (KAIROS)

At 02:00 every night:

```
distill yesterday's log → decayAll → archiveDormant
  → detectIssues (conflicts/merges/expiry)
  → checkDeadReferences
  → write DAILY_BRIEF.md
```

## Memory File Format

```markdown
---
name: never-mock-database
description: Integration tests must use real database
type: feedback
importance: CRITICAL
weight: 0.95
reinforced: 5
lastRecalled: 2026-05-04T20:00:00Z
decayRate: 0.01
tags: [java testing database]
related: [senior-java-dev.md]
---

Never mock the database in integration tests.

**Why:** Previous mock/prod divergence caused a production incident.
**How to apply:** Use Testcontainers or embedded H2 for integration tests.
```

## Roadmap

- [ ] **Agent integration** — wire into AgentScope ReActAgent for automatic in-conversation memory
- [ ] **Conversational memory** — agent proactively asks "these two memories conflict, merge?"
- [ ] **Knowledge graph visualization** — Mermaid/Graphviz export
- [ ] **External knowledge ingestion** — URL summarization, PDF parsing
- [ ] **Web dashboard** — interactive memory browser

## References

- Design doc: `memory/Memory-design.md`
- AgentScope docs: https://java.agentscope.io/llms-full.txt

## License

MIT
