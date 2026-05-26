# PsyChat - AI 心理陪伴 Agent 系统

## 项目概述

基于 Spring AI 的智能心理陪伴对话系统，具备多层记忆、RAG 混合检索、情绪追踪与 Agentic 主动关怀能力。
58 个 Java 文件，4300+ 行后端代码，已发布 GitHub：https://github.com/Yakuso234/psychat

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | SpringBoot 3.3.6, JDK 17, Maven 3.9+ |
| AI | Spring AI 1.0.6（ChatClient + EmbeddingModel + @Tool） |
| LLM | DeepSeek v4-flash（聊天 + Function Calling） |
| Embedding | SiliconFlow BAAI/bge-m3（1024 维） |
| Rerank | SiliconFlow BAAI/bge-reranker-v2-m3 |
| ORM | MyBatis-Plus 3.5.9 |
| 缓存 | Redis 7.2.4（Docker 容器 `ai-redis1`:6380） |
| 数据库 | MySQL 8.0.36（Docker 容器 `ai-mysql1`:3307，密码123456，库名`ai_companion`） |
| 向量库 | Milvus 2.4.5（Docker 容器 `ai-milvus1`:19530） |
| 前端 | Vue3 + Vite + Element Plus + ECharts |

## 目录结构

```
yakusoAiAgent/
├── README.md
├── CLAUDE.md
├── docker/
│   ├── docker-compose.yml          # MySQL + Redis + Etcd + MinIO + Milvus
│   └── mysql/init/01-schema.sql    # 建表 SQL
├── backend/psychat/                # SpringBoot 后端（Maven 项目）
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd             # Maven Wrapper
│   └── src/main/java/com/yakuso/psychat/
│       ├── PsychatApplication.java         # 启动类（@EnableAsync + @EnableScheduling）
│       ├── common/                  # JwtUtil, AuthContext(ThreadLocal), Result, TraceFilter
│       ├── config/                  # WebConfig, WebSocketConfig, RedisConfig, MilvusConfig
│       │   ├── AiConfig.java        # Spring AI 双 Provider Bean（DeepSeek + SiliconFlow）
│       │   ├── AsyncConfig.java     # @Async 线程池 + MDC TaskDecorator
│       │   ├── ToolRegistry.java    # 工具注册/分发
│       │   └── ToolConfig.java      # @PostConstruct 注册 notify_crisis + offer/show_weekly_report
│       ├── filter/                  # JwtAuthFilter
│       ├── entity/                  # User, ChatMessage, BindRelation, UserFact, EmotionEvent, UserPreference, CrisisNotification
│       ├── dto/                     # LoginRequest, RegisterRequest, ChatRequest, BindRequest, LoginResponse, BindVO
│       ├── mapper/                  # MyBatis-Plus Mapper × 7
│       ├── service/
│       │   ├── AuthService.java         # 登录/注册，BCrypt
│       │   ├── ChatService.java         # SSE 聊天 + Function Calling + 八层 Prompt 拼接 + 记忆/RAG/事实/情绪
│       │   ├── CrisisTool.java          # 危机通知（@Tool 注解，DB查绑定→WebSocket推+MySQL持久化+Redis 30min冷却）
│       │   ├── WeeklyReportTool.java    # 情绪周报工具（@Tool 注解，user_request/auto_detect 双模式）
│       │   ├── EmbeddingService.java    # SiliconFlow BGE-M3 Embedding（Spring AI EmbeddingModel）
│       │   ├── RerankService.java       # SiliconFlow Rerank 精排（BAAI/bge-reranker-v2-m3）
│       │   ├── MemoryService.java       # Milvus 记忆 CRUD + 时间衰减 + Zettelkasten 链接 + 500条上限(可配置) + 过期优先淘汰 + @Scheduled每日清理
│       │   ├── KnowledgeService.java    # Milvus 知识库混合检索（向量+Rerank+tag+priority+ 动态nprobe）
│       │   ├── LocalEmotionLexicon.java # 本地情绪词库（危机30+关键词 + 情绪130+关键词，零延迟）
│       │   ├── KnowledgeSeeder.java     # @Async 启动播种 28 条知识到 knowledge_base
│       │   ├── SummaryService.java      # @Async 摘要生成→embed→Milvus（Spring AI ChatClient）
│       │   ├── FactExtractionService.java  # @Async 事实提取+去重+置信度→MySQL（Spring AI ChatClient）
│       │   ├── EmotionAnalysisService.java # @Async 情绪分析（20类标签+五级）→ Spring AI ChatClient
│       │   ├── EmotionReportService.java   # 情绪周报生成（用户画像+偏好→ChatClient→JSON+ECharts数据）
│       │   ├── UserPreferenceService.java  # 用户偏好 CRUD
│       │   └── NotificationService.java    # WebSocket 通知
│       ├── controller/
│       │   ├── AuthController.java       # /api/auth/register, /api/auth/login
│       │   ├── ChatController.java       # /api/chat/send(SSE), /history, /sessions, /memory, /knowledge, /seed-test-memories
│       │   ├── BindController.java       # /api/bind/*
│       │   ├── FactController.java       # /api/fact/*
│       │   ├── EmotionController.java    # /api/emotion/recent, /api/emotion/weekly-report
│       │   ├── PreferenceController.java # /api/preference GET/PUT
│       │   ├── NotificationController.java # /api/notification/*
│       │   └── GlobalExceptionHandler.java # @RestControllerAdvice + traceId 注入
│       └── websocket/
│           └── NotificationHandler.java
│   └── src/main/resources/
│       ├── application.yml              # 全部配置（Spring AI + SiliconFlow + Milvus + AI人设 + 干预技巧 + app.memory.max-per-user）
│       └── knowledge-seed.json          # 28条知识库（3 crisis + 7 intervention + 15 empathy + 2 forbidden）
└── frontend/psychat-ui/                # Vue3 前端
    └── src/
        ├── api/                    # request.js(axios+拦截器), chat.js
        ├── router/index.js         # 路由 + 登录守卫
        └── views/
            ├── LoginView.vue       # 登录/注册
            ├── ChatView.vue        # 主聊天页（SSE + Session + 情绪周报按钮+弹窗+ECharts+SSE report事件自动弹窗）
            ├── AdminView.vue       # 绑定管理 + 危机通知铃铛
            └── FactView.vue        # 记忆页（结构化事实 + 向量记忆双 Tab）
```

## 数据库表（MySQL ai_companion）

| 表 | 字段 | 用途 |
|----|------|------|
| users | id, username(UNIQUE), password_hash, nickname, role(ADMIN/USER) | 用户 |
| chat_messages | id, user_id, session_id, role(USER/ASSISTANT), content, emotion_summary | 对话历史 |
| bind_relations | id, admin_id, user_id, status(PENDING/ACCEPTED/REJECTED), initiator | 双向绑定 |
| user_facts | id, user_id, category, fact_content, confidence, status(active/superseded/deprecated) | 结构化事实，重复提取提升置信度 |
| emotion_events | id, user_id, session_id, message_id, emotion_label, intensity, summary | 情绪分析（AI输出五级emotion_level→存储映射intensity） |
| user_preferences | id, user_id(UNIQUE), tone_style, response_length, allow_proactive | 用户偏好 |
| crisis_notifications | id, admin_id, user_id, username, risk_level, evidence, summary, is_read, created_at | 危机告警持久化 |

## Milvus 集合

| 集合 | 字段 | 维度 | 索引 | 用途 |
|------|------|------|------|------|
| user_memories | id, user_id(PartitionKey), content, embedding, created_at, linked_memory_ids | 1024 | AUTOINDEX, COSINE | 对话摘要记忆，500条/用户上限(可配置) |
| knowledge_base | id, category, emotion_tags, title, content, embedding, priority, usage_count | 1024 | AUTOINDEX, COSINE | 专业知识库，28条种子，4种检索模式 |

## 核心业务流程

### 聊天流程（ChatService.chat）
```
用户发消息
  → TraceFilter → MDC.put("traceId", uuid8)
  → saveMessage(MySQL) 同步存
  → CompletableFuture.runAsync(..., taskExecutor):    ← P1-1 MDC 透传
      1. loadContext(Redis) → 短期最近10轮
      2. recallMemories(Milvus) → 时间衰减 + Zettelkasten 链接扩展 → Top-3
      3. loadPreferences(MySQL) → 用户偏好
      4. loadFactsText(MySQL) → 结构化事实（白名单4类×每类2条×总量8条，score排序）
      5. loadRecentEmotions(MySQL) → 当前session最近3轮
      6. buildKnowledgeContext → RAG 三级检索：
         a. LocalEmotionLexicon.analyze() → 130+关键词零延迟 → isCrisis? + tags
         b. analyzeEmotionTrajectory(DB近3轮) → mode: crisis/escalating/deescalating/stable
         c. KnowledgeService.retrieve(embedding, query, tags, categoryFilter, topK, mode)
            → Milvus search (withExpr硬过滤, PartitionKey, 动态nprobe 8/32)   ← P0-4
            → RerankService.rerank(SiliconFlow bge-reranker)                    ← P0-2
            → Java 重排(tag+priority+usage) + 强制 forbidden 注入
      7. buildMessages(偏好+记忆+事实+情绪+知识RAG+节奏+短期上下文+当前消息+tools)
      8. callModelWithTools → HttpClient SSE → DeepSeek v4-flash
         ├── content delta → SSE 推送前端
         ├── reasoning_content delta → 累积（第二轮 tool 调用时原样带回）
         ├── tool_calls delta → ToolCallAccumulator 累积
         └── finish_reason:
             ├── "stop" → 返回全文
             └── "tool_calls" → notify_crisis / offer_weekly_report / show_weekly_report
                 → show_weekly_report 返回 __REPORT__:{} → SSE event:report → 前端自动弹窗
                 → 结果回传 → 第2轮 API → SSE 推送
      9. 回复完成 → saveMessage(MySQL) + saveContext(Redis)
      10. @Async → EmotionAnalysisService (Spring AI ChatClient) → emotion_events
      11. @Async → SummaryService (Spring AI ChatClient) → embed → store(Milvus, linked)
      12. @Async → FactExtractionService (Spring AI ChatClient) → 去重 → user_facts
```

### AI 提示词拼接结构（八层）
```
[系统人设] 你是心语...（含干预技巧：盒式呼吸/接地术/行为激活/愤怒共情）
[用户偏好] 语气风格: warm, 回复长度: medium, 主动问候: 否
[Milvus 记忆 Top-3 + Zettelkasten 链接] 以下是你过去与这位用户的对话回忆...
[结构化事实] 关于用户的已知信息（白名单4类，总量8条）
[近期情绪轨迹] 焦虑·难过：考试压力 → 低落·低落：失眠...
[知识RAG指引] 本轮检索到的回应剧本（共情方向+干预技巧+禁止话术）
[当前节奏] 现在是深夜23:15，已连续对话5轮
[Redis 短期上下文] 最近10轮对话
[当前消息] 用户刚说的
```

### Agent 工具体系（3 个工具）

| 工具 | 触发条件 | 处理逻辑 | 冷却/阈值 |
|------|---------|---------|----------|
| notify_crisis | AI 判断用户有自伤/自杀意图 | 查绑定→WebSocket推+MySQL持久化 | Redis 30min |
| offer_weekly_report | 用户主动要求(trigger=user_request) / AI感知情绪剧烈(trigger=auto_detect) | auto_detect 先过 shouldProactiveOffer() ≥3次>0.7硬阈值，不够→闭嘴 | 硬阈值≥3次 |
| show_weekly_report | 用户同意查看 | 生成完整报告→SSE event:report→前端自动弹窗 | 无 |

**工具调用循环**：最大 3 轮，防无限循环。

### 记忆与个性化体系

| 层 | 存储 | 内容 | 检索方式 | 淘汰策略 |
|----|------|------|----------|---------|
| Redis 短期上下文 | chat:context:{userId} | 最近10轮完整对话 | 全量拼接 | 24h TTL |
| Milvus 长期记忆 | user_memories | AI 摘要(1-2句) | 向量语义 Top-3 + Zettelkasten 链接扩展 | 时间衰减(7d/30d/90d) + 过期优先淘汰 + LRU + 500条硬上限(可配置) + @Scheduled每日3:00清理 |
| Milvus 知识库 | knowledge_base | 28条结构化指令 | 三级管道(向量→Rerank→Java重排) | usage 衰减 |
| MySQL 事实 | user_facts | 分类+内容+置信度 | 白名单4类×每类2条×总量8条, score=0.7×recency+0.3×confidence | active/superseded/deprecated 状态管理 |
| MySQL 情绪 | emotion_events | 20类标签+五级强度+摘要 | 当前session近3轮正序注入 | — |
| MySQL 偏好 | user_preferences | 语气/长度/主动问候 | 每会话读1条注入 | — |

### RAG 检索详细链路

```
用户消息
  → LocalEmotionLexicon.analyze()
      → 危机关键词(30+)命中? → isCrisis=true, tags=["绝望"], mode=crisis
      → 未命中 → 情绪关键词(130+)匹配 → tags(≤3个)
  → loadRecentEmotions(MySQL, 近3轮)
  → analyzeEmotionTrajectory(): delta>0.2→escalating, delta<-0.15→deescalating, else→stable
  → tags 合并（词库 + DB历史）
  → embed(message) → SiliconFlow BGE-M3
  → KnowledgeService.retrieve(embedding, query, tags, categoryFilter, topK, mode)
      → Milvus search (withExpr 硬过滤, COSINE, 动态nprobe, fetchSize=topK×3)
      → RerankService.rerank(query, documents, topN) → SiliconFlow bge-reranker
      → Java 重排: baseScore(cosine/rerank) + catBonus(0.15) + tagBonus(0.1/tag, max0.3) × priorityFactor × usageFactor
      → Top-K 截断
      → 强制注入 forbidden（单独 withExpr 搜索，永远至少 1 条）
  → 格式化: 危机模式加 "⚠️ 危机模式：请严格按照以下安全协议回应"
```

## Spring AI 集成架构

```
Spring AI:
  ├── ChatClient (deepseekChatClient)
  │   ├── EmotionAnalysisService  ───┐
  │   ├── SummaryService         ────┤──> DeepSeek v4-flash
  │   ├── FactExtractionService  ────┤    (api.deepseek.com)
  │   └── EmotionReportService   ────┘
  │
  └── EmbeddingModel (siliconFlowEmbeddingModel)
      └── EmbeddingService      ──────> SiliconFlow BGE-M3
                                        (api.siliconflow.cn)

HttpClient (自定义 SSE):
  └── ChatService.streaming ──────────> DeepSeek v4-flash (保留 reasoning_content)
```

## 启动方式

### 1. Docker 中间件
```bash
cd docker
docker-compose up -d mysql redis          # Phase 1 必需
docker-compose up -d                      # 全部（含 Milvus）
```

### 2. 后端
```bash
cd backend/psychat

# IDEA Run Config → Environment variables:
#   DEEPSEEK_API_KEY=sk-xxx
#   SILICONFLOW_API_KEY=sk-xxx
./mvnw spring-boot:run
```
端口 8088。

### 3. 前端
```bash
cd frontend/psychat-ui
npm install
npm run dev
```
端口 5173，Vite 代理 `/api` → 8088。

## 环境变量

| 变量 | 必需 | 用途 |
|------|------|------|
| DEEPSEEK_API_KEY | ✅ | DeepSeek 聊天 + 事实提取 + 情绪分析 + 摘要 |
| SILICONFLOW_API_KEY | 推荐 | Embedding + Rerank。不配则 Milvus/Rerank 降级 |

## 当前进度

### Phase 1-4 ✅ 已完成（基础聊天→向量记忆→情绪追踪→知识RAG）

### Phase 5 ✅ Spring AI 集成 + 多项优化
- Spring AI 1.0.6 集成：ChatClient 替换非流式调用，EmbeddingModel 替换手写 Embedding，@Tool 注解声明式工具
- 流式聊天保留 HttpClient + BufferedReader（兼容 DeepSeek reasoning_content）
- P0-4: Milvus Partition Key 用户物理隔离 + 启动自动 schema 迁移
- P1-1: Trace ID 全链路透传（TraceFilter + AsyncConfig TaskDecorator + logging pattern）
- P0-2: RAG Rerank 精排（SiliconFlow BAAI/bge-reranker-v2-m3，复用 SILICONFLOW_API_KEY）
- P2-1: Zettelkasten 记忆链接网络（存入时自动链接 Top-3 相似记忆，召回时沿链扩展）
- P2-2: 情绪周报（用户画像+偏好驱动的个性化报告 + ECharts 折线图 + Agentic 主动推送）
- 记忆时间衰减（7d/30d/90d 三阶段）+ 过期优先淘汰 + LRU + 500 条/用户硬上限（app.memory.max-per-user 可配置，@Scheduled 每日 3:00 清理 >90d 过期记忆）
- 情绪体系从 13 类扩到 20 类，词库从 50 词扩到 130+ 词
- 动态 nprobe（crisis=32 / 普通=8）
- 全局异常处理 + traceId 错误消息注入
- 前端 SSE report 命名事件 + 自动弹窗

### Phase 6 ✅ 性能基准测试 + 记忆系统优化
- 记忆上限从 200 提到 500，改为 `app.memory.max-per-user` 可配置（application.yml）
- 淘汰策略升级：>90d 过期记忆优先淘汰 → 不够再 LRU，日志区分淘汰来源
- `@Scheduled(cron = "0 0 3 * * ?")` 每日凌晨 3 点自动清理过期记忆（`PsychatApplication` 加 `@EnableScheduling`）
- 性能基准测试 (`PerformanceBenchmarkTest.java`)，5 项 × 5 档规模：
  - 本地词库：**5µs** 正常 / **0.4µs** 危机 (10000 ops, JIT预热)
  - Embedding API：**83ms** avg (SiliconFlow BGE-M3, 1024维)
  - RAG 端到端：普通 **235ms** (nprobe=8) / 危机 **136ms** (nprobe=32, 硬过滤反快42%)
  - 记忆召回：**~12ms**，50→100→200→350→500 延迟平稳无衰减
  - 记忆写入：**16→22ms** (+6ms 淘汰检查开销，500 条时可接受)

### 测试接口
- `POST /api/chat/seed-test-memories` — 播种 8 条测试记忆（2-120 天前）
- `DELETE /api/chat/knowledge` — 清空知识库并重新播种
- `DELETE /api/chat/memory` — 清除当前用户全部记忆
- `GET /api/emotion/weekly-report` — 情绪周报

### 面试准备
- 简历项目描述（精简版）
- GitHub: https://github.com/Yakuso234/psychat
- 面试深挖问答 60 题: `C:\Users\52373\Desktop\心语助手开发\面试深挖问答.md`
- 性能测试代码: `backend/psychat/src/test/java/com/yakuso/psychat/PerformanceBenchmarkTest.java`
