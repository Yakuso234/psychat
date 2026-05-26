package com.yakuso.psychat;

import com.yakuso.psychat.dto.RegisterRequest;
import com.yakuso.psychat.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 性能基准测试，验证简历量化数据（词库 5µs / RAG 235ms / 记忆 ~12ms 等）。
 *
 * 运行前需要：
 * - 环境变量：DEEPSEEK_API_KEY、SILICONFLOW_API_KEY
 * - Docker 服务：MySQL (ai-mysql1:3307)、Redis (ai-redis1:6380)、Milvus (ai-milvus1:19530)
 */
@Disabled("需要 API Key 和 Docker 中间件，仅在本地开发环境运行")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmarkTest {

    @Autowired private LocalEmotionLexicon lexicon;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private KnowledgeService knowledgeService;
    @Autowired private MemoryService memoryService;
    @Autowired private RerankService rerankService;
    @Autowired private AuthService authService;

    private static Long testUserId;
    private static List<Float> sampleEmbedding;
    private static final int WARMUP = 3;
    private static final int ITERATIONS = 15;
    private static final List<String> SUMMARY = new ArrayList<>();

    // ── memory text generation ──
    private static final String[] TIMES = {
            "今天", "昨天", "上周", "前几天", "最近", "这周一", "上周末", "月初",
            "半个月前", "三周前", "上个月", "前一阵子", "最近几天", "这周末", "昨天下午"
    };
    private static final String[] TOPICS = {
            "工作压力很大，连续加班后感到身心俱疲",
            "和家人发生了争执，觉得自己的感受没有被理解",
            "和朋友聊天后心情好了很多，觉得有人陪伴真好",
            "考试临近感到焦虑，担心自己准备得不够充分",
            "在感情中遇到了困惑，不知道该怎么和对方沟通",
            "尝试了新学的冥想方法，感觉内心的焦虑平复了一些",
            "对未来感到迷茫，不知道该选择哪条职业道路",
            "同事的言行让自己感到不舒服，不知道该如何应对",
            "回忆起了童年的往事，有些怀念也有些伤感",
            "身体出现了一些不适，担心自己的健康状况",
            "看完一部电影后感触很深，联想到自己的经历",
            "搬家后对新环境还不太适应，感到有些孤单",
            "和许久未见的老朋友重逢，觉得时间过得太快",
            "开始学习一项新技能，过程虽然困难但很有成就感",
            "失眠的情况又出现了，躺在床上翻来覆去睡不着",
            "在社交场合感到紧张和不自在，想逃离那个环境",
            "收到了一个好消息，觉得生活还是有希望的",
            "犯了一个错误后反复在心里责怪自己，很难释怀",
            "觉得自己在人际关系中总是付出更多，有些委屈",
            "最近天气变化让自己情绪也跟着起伏不定",
            "在日记里写下了最近的感受，觉得情绪有了出口",
            "经济上的压力让自己喘不过气来，每天都过得很紧绷",
            "觉得自己在团队中不被重视，付出和回报不成正比",
            "对某个决定感到后悔，总是在想如果当初选了另一条路会怎样",
            "发现自己总是忍不住和别人比较，越比越焦虑"
    };
    private static final String[] EMOTIONS = {
            "感到非常焦虑和不安", "情绪有些低落和疲惫", "处于一种迷茫的状态",
            "觉得内心很平静", "有些烦躁和不满", "既期待又害怕",
            "感到深深的孤独", "对自己有些失望", "心里堵得慌",
            "有一种无力感", "感到被忽视和冷落", "内心充满矛盾"
    };

    @BeforeAll
    static void banner() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  PsyChat Performance Benchmark — Full Scale Test");
        System.out.println("=".repeat(70));
    }

    @BeforeEach
    void ensureUser() {
        if (testUserId != null) return;
        try {
            RegisterRequest req = new RegisterRequest();
            req.setUsername("perf-bench-user");
            req.setPassword("bench123");
            req.setRole("USER");
            var resp = authService.register(req);
            testUserId = resp.getUserId();
            System.out.println("[SETUP] Test user: id=" + testUserId);
        } catch (Exception e) {
            com.yakuso.psychat.dto.LoginRequest loginReq = new com.yakuso.psychat.dto.LoginRequest();
            loginReq.setUsername("perf-bench-user");
            loginReq.setPassword("bench123");
            var resp = authService.login(loginReq);
            testUserId = resp.getUserId();
            System.out.println("[SETUP] Test user (existing): id=" + testUserId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Benchmark 1: LocalEmotionLexicon
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    void benchmarkLocalLexicon() {
        System.out.println("\n─── 1. Local Emotion Lexicon ───");

        String[] normalTexts = {
                "最近考试压力好大，每天失眠睡不着，焦虑得快喘不过气了",
                "感觉自己什么都做不好，好难过，想哭，讨厌自己",
                "今天和同事吵架了，真的很生气，觉得太不公平了",
                "一个人在家好孤单，没人理解我，活着好累",
                "不知道怎么办了，对未来一片迷茫，好像什么都做不好"
        };
        String[] crisisTexts = {
                "我真的撑不下去了，活着好累，想死",
                "有时候真的想一了百了，不想活了",
                "我已经活够了，没什么可留恋的，想结束这一切"
        };

        // warmup
        for (int i = 0; i < 1000; i++) lexicon.analyze(normalTexts[i % normalTexts.length]);

        // normal: 10000 ops
        int ops = 10000;
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            lexicon.analyze(normalTexts[i % normalTexts.length]);
        }
        double normalAvgUs = (System.nanoTime() - t0) / 1000.0 / ops;

        // crisis: 10000 ops
        t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            lexicon.analyze(crisisTexts[i % crisisTexts.length]);
        }
        double crisisAvgUs = (System.nanoTime() - t0) / 1000.0 / ops;

        // correctness
        var nr = lexicon.analyze("最近考试压力好大，失眠睡不着，好焦虑，觉得自己好没用");
        var cr = lexicon.analyze("我不想活了，活着好累，想结束这一切");

        String l1 = String.format("  Normal (130+ keywords): %.2f us/op  (%d ops)  tags=%s", normalAvgUs, ops, nr.emotionTags());
        String l2 = String.format("  Crisis (30+ keywords):  %.2f us/op  (%d ops)  isCrisis=%s", crisisAvgUs, ops, cr.isCrisis());
        System.out.println(l1);
        System.out.println(l2);
        SUMMARY.add(l1);
        SUMMARY.add(l2);
    }

    // ═══════════════════════════════════════════════════════════════
    // Benchmark 2: Embedding Service
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    void benchmarkEmbedding() {
        System.out.println("\n─── 2. Embedding API (SiliconFlow BGE-M3 1024-dim) ───");

        String testText = "我今天心情很不好，工作压力很大，感觉非常焦虑，晚上也睡不着";

        // warmup
        for (int i = 0; i < 3; i++) embeddingService.embed(testText);

        long total = 0, min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.currentTimeMillis();
            List<Float> vec = embeddingService.embed(testText);
            long elapsed = System.currentTimeMillis() - t0;
            total += elapsed;
            if (elapsed < min) min = elapsed;
            if (elapsed > max) max = elapsed;
            if (vec != null && sampleEmbedding == null) sampleEmbedding = vec;
        }

        if (sampleEmbedding == null) {
            // generate fallback random vector for Milvus tests
            sampleEmbedding = new ArrayList<>(1024);
            var rng = ThreadLocalRandom.current();
            for (int i = 0; i < 1024; i++) sampleEmbedding.add(rng.nextFloat() * 2 - 1);
            // normalize
            double norm = 0;
            for (float v : sampleEmbedding) norm += v * v;
            norm = Math.sqrt(norm);
            for (int i = 0; i < 1024; i++) sampleEmbedding.set(i, (float)(sampleEmbedding.get(i) / norm));
        }

        String l1 = String.format("  avg=%dms  min=%dms  max=%dms  dims=%d  iters=%d",
                total / ITERATIONS, min, max,
                sampleEmbedding != null ? sampleEmbedding.size() : 0, ITERATIONS);
        System.out.println(l1);
        SUMMARY.add("  Embedding: " + l1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Benchmark 3: RAG Pipeline (Milvus → Rerank → Java Rerank)
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(3)
    void benchmarkRagPipeline() {
        System.out.println("\n─── 3. RAG Full Pipeline (28 knowledge entries) ───");

        if (sampleEmbedding == null) {
            System.out.println("  SKIP: no embedding");
            SUMMARY.add("  RAG: SKIPPED");
            return;
        }

        String userQuery = "我最近总是失眠，白天没有精神，感觉好累好焦虑";
        List<String> normalTags = List.of("焦虑", "疲惫");

        // ensure knowledge base loaded
        if (knowledgeService.isEmpty()) {
            System.out.println("  WARNING: Knowledge base is empty! Run KnowledgeSeeder first.");
            SUMMARY.add("  RAG: SKIPPED (empty knowledge base)");
            return;
        }

        // ── Normal mode ──
        System.out.println("  Normal mode (nprobe=8, no category filter)...");
        for (int i = 0; i < WARMUP; i++) {
            knowledgeService.retrieve(sampleEmbedding, userQuery, normalTags, null, 3, "normal");
        }

        long normalTotal = 0, normalMin = Long.MAX_VALUE, normalMax = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.currentTimeMillis();
            var results = knowledgeService.retrieve(sampleEmbedding, userQuery, normalTags, null, 3, "normal");
            long elapsed = System.currentTimeMillis() - t0;
            normalTotal += elapsed;
            if (elapsed < normalMin) normalMin = elapsed;
            if (elapsed > normalMax) normalMax = elapsed;
        }

        // ── Crisis mode ──
        System.out.println("  Crisis mode (nprobe=32, category=crisis)...");
        List<String> crisisTags = List.of("绝望");
        for (int i = 0; i < 2; i++) {
            knowledgeService.retrieve(sampleEmbedding, "我不想活了", crisisTags, "crisis", 3, "crisis");
        }

        long crisisTotal = 0, crisisMin = Long.MAX_VALUE, crisisMax = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.currentTimeMillis();
            var results = knowledgeService.retrieve(sampleEmbedding, "我不想活了", crisisTags, "crisis", 5, "crisis");
            long elapsed = System.currentTimeMillis() - t0;
            crisisTotal += elapsed;
            if (elapsed < crisisMin) crisisMin = elapsed;
            if (elapsed > crisisMax) crisisMax = elapsed;
        }

        // ── Rerank standalone ──
        List<String> testDocs = List.of(
                "当你感到焦虑时，试试盒式呼吸法：吸气4秒，屏息4秒，呼气4秒，屏息4秒",
                "失眠是很常见的困扰，可以尝试睡前泡脚、听轻音乐",
                "我理解你现在的疲惫感，有时候我们需要的不是解决问题，而是被理解"
        );
        long rerankTotal = 0;
        for (int i = 0; i < 5; i++) {
            long t0 = System.currentTimeMillis();
            rerankService.rerank(userQuery, testDocs, 3);
            rerankTotal += System.currentTimeMillis() - t0;
        }

        double nAvg = normalTotal / (double) ITERATIONS;
        double cAvg = crisisTotal / (double) ITERATIONS;
        double rAvg = rerankTotal / 5.0;

        String l1 = String.format("  Normal (nprobe=8):    avg=%dms  min=%dms  max=%dms", (int)nAvg, normalMin, normalMax);
        String l2 = String.format("  Crisis (nprobe=32):   avg=%dms  min=%dms  max=%dms", (int)cAvg, crisisMin, crisisMax);
        String l3 = String.format("  Rerank API:           avg=%dms (3 docs)", (int)rAvg);
        String l4 = String.format("  Milvus+Java (est):    ~%dms (RAG total - Rerank)", (int)(nAvg - rAvg));

        System.out.println(l1);
        System.out.println(l2);
        System.out.println(l3);
        System.out.println(l4);
        SUMMARY.add(l1);
        SUMMARY.add(l2);
        SUMMARY.add(l3);
        SUMMARY.add(l4);
    }

    // ═══════════════════════════════════════════════════════════════
    // Benchmark 4: Memory System at Scale (50→100→200→350→500)
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(4)
    void benchmarkMemoryAtScale() {
        System.out.println("\n─── 4. Memory System at Scale (50/100/200/350/500) ───");

        if (testUserId == null || sampleEmbedding == null) {
            System.out.println("  SKIP: no user or embedding");
            SUMMARY.add("  Memory: SKIPPED");
            return;
        }

        final int TOTAL = 500;
        memoryService.clearMemories(testUserId);

        List<String> allTexts = generateMemoryTexts(TOTAL);
        System.out.println("  Generated " + allTexts.size() + " unique memory texts");
        System.out.println("  (Seeding " + TOTAL + " memories will take ~" + (TOTAL * 85 / 1000) + "s for embedding alone)");

        int[] stages = {50, 100, 200, 350, 500};
        int prev = 0;

        for (int stage : stages) {
            int toSeed = stage - prev;
            System.out.printf("%n  [Phase] Seeding %d → %d memories...%n", prev, stage);
            seedBatch(allTexts.subList(prev, stage), prev, TOTAL);
            benchmarkMemoryOps(stage);
            prev = stage;
        }

        memoryService.clearMemories(testUserId);
        System.out.println("\n  Cleaned up.");
    }

    private List<String> generateMemoryTexts(int count) {
        List<String> texts = new ArrayList<>();
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            String time = TIMES[rng.nextInt(TIMES.length)];
            String topic = TOPICS[rng.nextInt(TOPICS.length)];
            String emotion = EMOTIONS[rng.nextInt(EMOTIONS.length)];
            texts.add(String.format("[记忆#%d] %s用户%s，%s。", i + 1, time, topic, emotion));
        }
        return texts;
    }

    private void seedBatch(List<String> texts, int startIdx, int total) {
        int done = 0;
        for (int i = 0; i < texts.size(); i++) {
            try {
                List<Float> emb = embeddingService.embed(texts.get(i));
                if (emb != null) {
                    long createdAt = System.currentTimeMillis() / 1000
                            - ThreadLocalRandom.current().nextLong(0, 85 * 86400); // 0-85 days ago
                    memoryService.storeWithTimestamp(testUserId, texts.get(i), emb, createdAt);
                    done++;
                }
            } catch (Exception e) {
                // skip
            }
            if ((i + 1) % 50 == 0) {
                System.out.printf("    seeded %d/%d...%n", startIdx + done, total);
            }
        }
        System.out.printf("    done: %d memories stored%n", startIdx + done);
    }

    private void benchmarkMemoryOps(int totalMemories) {
        // ── recall latency ──
        long recallTotal = 0, recallMin = Long.MAX_VALUE, recallMax = 0;
        int avgResultCount = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.currentTimeMillis();
            var results = memoryService.recall(testUserId, sampleEmbedding, 3);
            long elapsed = System.currentTimeMillis() - t0;
            recallTotal += elapsed;
            if (elapsed < recallMin) recallMin = elapsed;
            if (elapsed > recallMax) recallMax = elapsed;
            avgResultCount += results.size();
        }

        double recallAvg = recallTotal / (double) ITERATIONS;

        // ── store latency (single insert, tests eviction check overhead) ──
        long storeTotal = 0;
        for (int i = 0; i < 5; i++) {
            List<Float> emb = embeddingService.embed("test store " + i + " " + System.currentTimeMillis());
            if (emb != null) {
                long t0 = System.currentTimeMillis();
                memoryService.storeWithTimestamp(testUserId,
                        "[store-latency-test] 临时测试记忆 #" + i,
                        emb,
                        System.currentTimeMillis() / 1000);
                storeTotal += System.currentTimeMillis() - t0;
            }
        }
        double storeAvg = storeTotal / 5.0;

        String l1 = String.format("  %3d memories | recall: avg=%dms min=%dms max=%dms results=%d | store: avg=%dms",
                totalMemories, (int) recallAvg, recallMin, recallMax,
                avgResultCount / ITERATIONS, (int) storeAvg);
        System.out.println(l1);
        SUMMARY.add(l1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Summary
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(99)
    void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  BENCHMARK SUMMARY");
        System.out.println("=".repeat(70));
        for (String line : SUMMARY) {
            System.out.println(line);
        }
        System.out.println("=".repeat(70));
    }
}
