# PsyChat — AI 心理陪伴 Agent 系统

基于 Spring AI 的智能心理陪伴对话系统，具备多层记忆、RAG 混合检索、情绪追踪与 Agentic 主动关怀能力。

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Spring Boot 3.3.6, JDK 17 |
| AI | Spring AI 1.0.6, DeepSeek v4-flash（聊天）, BAAI/bge-m3（向量化，SiliconFlow） |
| ORM | MyBatis-Plus 3.5.9 |
| 缓存 | Redis 7.2（Docker） |
| 数据库 | MySQL 8.0（Docker） |
| 向量库 | Milvus 2.4.5（Docker） |
| 前端 | Vue 3 + Vite + Element Plus + ECharts |

## 系统架构

```
客户端 (Vue3)
  │  SSE 流式 + REST API
  ▼
Spring Boot (8088)
  ├─ TraceFilter        → MDC traceId 全链路追踪
  ├─ JwtAuthFilter      → JWT 鉴权
  ├─ ChatController     → /api/chat/send (SSE)
  │   └─ ChatService
  │       ├─ Redis（短期上下文，最近10轮，24h TTL）
  │       ├─ Milvus user_memories（长期向量记忆，200条/用户，时间衰减）
  │       ├─ Milvus knowledge_base（28条结构化知识库）
  │       ├─ MySQL user_facts（结构化用户画像）
  │       ├─ MySQL emotion_events（20类情绪标签 + 五级强度）
  │       ├─ MySQL user_preferences（语气/长度/主动问候偏好）
  │       └─ HttpClient → DeepSeek API（SSE 流式 + Function Calling）
  │
  ├─ Agent 工具调用
  │   ├─ notify_crisis      → WebSocket 推送 + MySQL 持久化 + Redis 冷却
  │   ├─ offer_weekly_report → Agentic 主动推送（硬阈值控制）
  │   └─ show_weekly_report  → SSE 事件前端自动弹窗
  │
  └─ @Async 异步后处理（traceId 透传）
      ├─ EmotionAnalysisService  → ChatClient.call()（Spring AI）
      ├─ SummaryService          → embed → Milvus（Zettelkasten 记忆链接）
      └─ FactExtractionService   → 去重 → MySQL
```

## 核心亮点

### Agent 工具调用体系
- 基于 Spring AI `@Tool` 注解声明式工具定义
- 危机告警：WebSocket 实时推送 + MySQL 持久化不漏消息 + Redis 30min 冷却防抖
- 情绪周报：Agentic 主动推送（≥3 次高强度情绪触发），用户确认后前端自动弹窗
- 流式 SSE 保留 DeepSeek reasoning_content 思考链，工具调用轮次完整透传

### RAG 混合检索
- 三级管道：本地情绪词库（130+ 关键词，零延迟）→ Milvus 向量检索 → 硅基流动 Rerank 精排
- 4 种检索模式自适应切换：crisis / escalating / deescalating / stable
- 动态 nprobe：危机 32（高召回）/ 普通 8（低延迟）
- 标签加权 + priority 加权 + usage 衰减 Java 层重排序
- 禁止话术强制注入，防止 AI 二次伤害

### Agentic Memory 多层记忆
- Redis 短期上下文：最近 10 轮，24h TTL
- Milvus 长期记忆：7d/30d/90d 三阶段时间衰减（1.0→0.5→0.1），>90d 自动淘汰，200 条/用户硬上限
- Zettelkasten 记忆链接：存入时自动检索 Top-3 相似记忆建立链接，召回时沿链扩展
- MySQL 结构化画像：白名单 4 类 × 每类 2 条 × 总量 8 条，recency+confidence 评分

### 情绪追踪与可视化
- 本地词库 130+ 关键词零延迟扫描
- 20 类情绪标签 + 五级强度（平静→低落→难过→悲观→绝望）
- 会话级情绪轨迹注入 Prompt
- 情绪周报 ECharts 折线图可视化（Spring AI ChatClient 驱动生成）

### 全链路可观测
- Trace ID：HTTP Filter → Agent 决策 → RAG 检索 → @Async 后处理全链路透传
- MDC + TaskDecorator 异步线程上下文传递
- RAG 召回质量逐条拆解：cosine/rerank/tag/priority/usage 分项日志

## 快速启动

### 1. Docker 中间件
```bash
cd docker
docker-compose up -d mysql redis          # Phase 1 必需
docker-compose up -d                      # 全部（含 Milvus）
```

### 2. 后端
```bash
cd backend/psychat

# 环境变量（IDEA Run Configuration 或命令行）：
#   DEEPSEEK_API_KEY=sk-xxx
#   SILICONFLOW_API_KEY=sk-xxx

./mvnw spring-boot:run
```
端口：8088

### 3. 前端
```bash
cd frontend/psychat-ui
npm install
npm run dev
```
端口：5173，Vite 代理 `/api` → 8088

## 目录结构

```
yakusoAiAgent/
├── docker/
│   ├── docker-compose.yml
│   └── mysql/init/01-schema.sql
├── backend/psychat/
│   └── src/main/java/com/yakuso/psychat/
│       ├── common/          # JwtUtil, AuthContext(ThreadLocal), TraceFilter, Result
│       ├── config/          # WebConfig, MilvusConfig, AiConfig(Spring AI), ToolConfig, AsyncConfig
│       ├── filter/          # JwtAuthFilter
│       ├── entity/          # User, ChatMessage, EmotionEvent, UserFact, ...
│       ├── dto/             # ChatRequest, LoginRequest, ...
│       ├── mapper/          # MyBatis-Plus Mapper
│       ├── service/         # ChatService, MemoryService, KnowledgeService, ...
│       ├── controller/      # ChatController, EmotionController, ...
│       └── websocket/       # NotificationHandler
└── frontend/psychat-ui/
    └── src/views/           # ChatView, AdminView, LoginView, FactView
```

## 关键指标

| 指标 | 数值 |
|------|------|
| 知识库条目 | 28 条结构化知识 |
| 情绪标签 | 20 类，130+ 关键词 |
| 检索模式 | 4 种自适应切换 |
| 记忆容量 | 200 条/用户，90 天过期 |
| 后端代码 | 58 文件，4300+ 行 |
| Rerank 模型 | SiliconFlow BAAI/bge-reranker-v2-m3 |
| 用户隔离 | Milvus Partition Key 物理隔离 |
