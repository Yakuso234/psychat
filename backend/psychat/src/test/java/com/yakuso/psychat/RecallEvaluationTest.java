package com.yakuso.psychat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yakuso.psychat.dto.RegisterRequest;
import com.yakuso.psychat.service.*;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RAG 和记忆系统的离线召回评测。
 *
 * RAG：28 条种子 + LLM 变体扩展 → 112 条条目 + LLM 生成 327 条 query → 三管道对比
 * Memory：10 条目标记忆 + 200 条噪声 → 4 级语料大海捞针
 *
 * 运行前需要：
 * - 环境变量：DEEPSEEK_API_KEY、SILICONFLOW_API_KEY
 * - Docker 服务：Milvus (ai-milvus1:19530)
 * - 首次运行约 10 分钟（含 LLM 数据生成），后续从 target/recall-eval-cache.json 读取缓存
 */
@Disabled("需要 API Key 和 Milvus，首次运行约 10 分钟（含 LLM 数据生成）")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecallEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(RecallEvaluationTest.class);

    // ═══════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════
    private static final int VARIANTS_PER_SEED = 3;          // 28 → 112 entries
    private static final int QUERIES_PER_ENTRY = 3;
    private static final int EVAL_TOP_K = 5;
    private static final int NOISE_MEMORY_COUNT = 200;
    private static final int PLANTED_MEMORY_COUNT = 10;
    private static final Path CACHE_FILE = Path.of("target/recall-eval-cache.json");

    // ═══════════════════════════════════════════════════════════════
    // Data structures
    // ═══════════════════════════════════════════════════════════════
    record EvalEntry(long id, String category, List<String> emotionTags, String title,
                     String content, int priority, int usageCount) {}

    record MilvusHit(long id, String category, List<String> emotionTags, String title,
                     String content, int priority, int usageCount, double cosineScore) {}

    record QueryEval(String query, long targetEntryId, List<String> expectedTags, String expectedCategory) {}

    record EvalMetrics(double recall1, double recall3, double recall5, double mrr, double ndcg5,
                       int totalQueries, String label) {
        @Override
        public String toString() {
            return String.format("%s: R@1=%.1f%% R@3=%.1f%% R@5=%.1f%% MRR=%.3f NDCG@5=%.3f (n=%d)",
                    label, recall1 * 100, recall3 * 100, recall5 * 100, mrr, ndcg5, totalQueries);
        }
    }

    record CachedData(List<SeedEntry> seeds, List<VariantEntry> variants, List<QueryMapping> queries) {}
    record SeedEntry(String category, List<String> emotionTags, String title, String content, int priority) {}
    record VariantEntry(String category, List<String> emotionTags, String title, String content, int priority) {}
    record QueryMapping(long entryIndex, String query, List<String> expectedTags, String expectedCategory) {}

    // ═══════════════════════════════════════════════════════════════
    // Injected services
    // ═══════════════════════════════════════════════════════════════
    @Autowired private KnowledgeService knowledgeService;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private RerankService rerankService;
    @Autowired private MemoryService memoryService;
    @Autowired private MilvusServiceClient milvusClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private ChatClient deepseekChatClient;

    @Value("${milvus.knowledge-collection-name}")
    private String knowledgeCollectionName;

    @Value("${milvus.collection-name}")
    private String memoryCollectionName;

    // ═══════════════════════════════════════════════════════════════
    // Shared state
    // ═══════════════════════════════════════════════════════════════
    private static Long testUserId;
    private static List<EvalEntry> allEntries = new ArrayList<>();
    private static List<QueryEval> allQueries = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();

    // Memory eval state (shared between setup and eval test methods)
    private static List<String> memTargetContents = new ArrayList<>();
    private static List<List<String>> memTargetQueries = new ArrayList<>();

    @BeforeAll
    static void banner() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  PsyChat Recall Evaluation — RAG + Memory System");
        System.out.println("=".repeat(70));
    }

    @BeforeEach
    void ensureUser() {
        if (testUserId != null) return;
        try {
            RegisterRequest req = new RegisterRequest();
            req.setUsername("recall-eval-user");
            req.setPassword("eval123");
            req.setRole("USER");
            testUserId = authService.register(req).getUserId();
            log.info("Test user created: id={}", testUserId);
        } catch (Exception e) {
            var loginReq = new com.yakuso.psychat.dto.LoginRequest();
            loginReq.setUsername("recall-eval-user");
            loginReq.setPassword("eval123");
            testUserId = authService.login(loginReq).getUserId();
            log.info("Test user (existing): id={}", testUserId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RAG Part 1: Expand knowledge base 28 → ~112 entries
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    void expandKnowledgeBase() throws Exception {
        System.out.println("\n─── 1. RAG: Expand Knowledge Base ───");

        CachedData cached = loadCache();
        if (cached != null && !cached.variants.isEmpty()) {
            System.out.println("  Using cached data (" + cached.variants.size() + " variants)");
            storeAllEntries(cached);
            return;
        }

        List<SeedEntry> seeds = loadSeedsFromFile();
        System.out.println("  Loaded " + seeds.size() + " seed entries from knowledge-seed.json");
        System.out.println("  Generating " + VARIANTS_PER_SEED + " variants per seed via LLM...");

        List<VariantEntry> allVariants = new ArrayList<>();
        int batchSize = 2; // smaller batches → simpler JSON → fewer parse errors
        for (int batchStart = 0; batchStart < seeds.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, seeds.size());
            List<SeedEntry> batch = seeds.subList(batchStart, batchEnd);
            List<VariantEntry> batchVariants = generateVariants(batch);
            // Retry once if batch failed
            if (batchVariants.isEmpty()) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                System.out.printf("  Batch %d/%d: retrying...%n",
                        batchStart / batchSize + 1, (seeds.size() + batchSize - 1) / batchSize);
                batchVariants = generateVariants(batch);
            }
            allVariants.addAll(batchVariants);
            System.out.printf("  Batch %d/%d: %d seeds → %d variants%n",
                    batchStart / batchSize + 1,
                    (seeds.size() + batchSize - 1) / batchSize,
                    batch.size(), batchVariants.size());
            if (batchStart + batchSize < seeds.size()) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        // Store seeds + variants in Milvus
        storeSeedsAndVariants(seeds, allVariants);

        saveCache(seeds, allVariants, null);
        System.out.println("  Total: " + seeds.size() + " seeds + " + allVariants.size()
                + " variants = " + (seeds.size() + allVariants.size()) + " entries stored");
    }

    // ═══════════════════════════════════════════════════════════════
    // RAG Part 2: Generate test queries
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    void generateTestQueries() throws Exception {
        System.out.println("\n─── 2. RAG: Generate Test Queries ───");

        CachedData cached = loadCache();
        if (cached != null && cached.queries != null && !cached.queries.isEmpty()) {
            System.out.println("  Using cached queries (" + cached.queries.size() + " total)");
            buildQueryEvalsFromMappings(cached.queries);
            return;
        }

        if (allEntries.isEmpty()) {
            System.out.println("  ERROR: No entries. Run expandKnowledgeBase first.");
            return;
        }

        System.out.println("  Generating " + QUERIES_PER_ENTRY + " queries per entry for "
                + allEntries.size() + " entries via LLM...");

        List<QueryMapping> allMappings = new ArrayList<>();
        int batchSize = 5;
        for (int batchStart = 0; batchStart < allEntries.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, allEntries.size());
            List<EvalEntry> batch = allEntries.subList(batchStart, batchEnd);
            List<QueryMapping> batchQueries = generateQueries(batch, batchStart);
            allMappings.addAll(batchQueries);
            System.out.printf("  Batch %d/%d: %d entries → %d queries%n",
                    batchStart / batchSize + 1,
                    (allEntries.size() + batchSize - 1) / batchSize,
                    batch.size(), batchQueries.size());
            if (batchStart + batchSize < allEntries.size()) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }

        // Save to cache
        CachedData existing = loadCache();
        saveCache(existing != null ? existing.seeds() : loadSeedsFromFile(),
                existing != null ? existing.variants() : List.of(),
                allMappings);

        buildQueryEvalsFromMappings(allMappings);
        System.out.println("  Total queries generated: " + allQueries.size());
    }

    @Test
    @Order(3)
    void evaluateRagRecall() {
        System.out.println("\n─── 3. RAG: Recall Evaluation ───");

        if (allQueries.isEmpty()) {
            System.out.println("  ERROR: No test queries. Run generateTestQueries first.");
            return;
        }
        if (allEntries.isEmpty()) {
            System.out.println("  ERROR: No entries. Run expandKnowledgeBase first.");
            return;
        }

        System.out.println("  Evaluating " + allQueries.size() + " queries against "
                + allEntries.size() + " entries");
        System.out.println("  Comparing 3 pipeline modes: Vector | Vector+Rerank | Full");

        // Run eval
        List<Long> vectorHits = new ArrayList<>();    // rank of target in vector-only
        List<Long> rerankHits = new ArrayList<>();     // rank in vector+rerank
        List<Long> fullHits = new ArrayList<>();       // rank in full pipeline
        List<Double> vectorMRR = new ArrayList<>();
        List<Double> rerankMRR = new ArrayList<>();
        List<Double> fullMRR = new ArrayList<>();

        int progressStep = Math.max(1, allQueries.size() / 10);
        for (int qi = 0; qi < allQueries.size(); qi++) {
            QueryEval qe = allQueries.get(qi);
            List<Float> emb = embeddingService.embed(qe.query);
            if (emb == null) continue;

            // Run raw Milvus search once, compute all 3 orderings from same results
            List<MilvusHit> rawHits = searchMilvusRaw(emb, qe.expectedCategory, EVAL_TOP_K * 3, "normal");
            if (rawHits.isEmpty()) continue;

            // Vector only: sort by cosine descending
            List<MilvusHit> vOrder = rawHits.stream()
                    .sorted((a, b) -> Double.compare(b.cosineScore, a.cosineScore))
                    .toList();

            // Vector + Rerank
            List<String> docs = rawHits.stream().map(h -> h.content).toList();
            var rerankResults = rerankService.rerank(qe.query, docs, Math.min(rawHits.size(), 20));
            double[] rerankScores = new double[rawHits.size()];
            boolean useRerank = false;
            for (var rr : rerankResults) {
                if (rr.index() < rawHits.size()) {
                    rerankScores[rr.index()] = rr.score();
                    if (rr.score() > 0) useRerank = true;
                }
            }
            List<MilvusHit> rOrder = new ArrayList<>(rawHits);
            if (useRerank) {
                rOrder.sort((a, b) -> {
                    int ai = rawHits.indexOf(a), bi = rawHits.indexOf(b);
                    return Double.compare(rerankScores[bi], rerankScores[ai]);
                });
            }

            // Full pipeline: call KnowledgeService.retrieve()
            var fullResults = knowledgeService.retrieve(emb, qe.query, qe.expectedTags,
                    qe.expectedCategory, EVAL_TOP_K, "normal");
            Set<Long> fullIds = new LinkedHashSet<>();
            for (var fr : fullResults) fullIds.add(fr.entry().id());

            // Compute rank of target in each ordering
            long vRank = findRank(vOrder, qe.targetEntryId);
            long rRank = findRank(rOrder, qe.targetEntryId);
            long fRank = findRankByIdSet(fullIds, qe.targetEntryId);

            vectorHits.add(vRank);
            rerankHits.add(rRank);
            fullHits.add(fRank);
            if (vRank > 0) vectorMRR.add(1.0 / vRank);
            if (rRank > 0) rerankMRR.add(1.0 / rRank);
            if (fRank > 0) fullMRR.add(1.0 / fRank);

            if (qi % progressStep == 0) {
                System.out.printf("  Progress: %d/%d queries%n", qi, allQueries.size());
            }
        }

        // Compute metrics
        EvalMetrics vMetrics = computeMetrics(vectorHits, vectorMRR, allQueries.size(), "Vector only");
        EvalMetrics rMetrics = computeMetrics(rerankHits, rerankMRR, allQueries.size(), "Vector+Rerank");
        EvalMetrics fMetrics = computeMetrics(fullHits, fullMRR, allQueries.size(), "Full pipeline");

        System.out.println("\n  ── RAG Recall Results ──");
        System.out.println("  " + vMetrics);
        System.out.println("  " + rMetrics);
        System.out.println("  " + fMetrics);

        // Hard filter analysis
        analyzeHardFilter();

        REPORT.add("RAG Knowledge Base (" + allEntries.size() + " entries, " + allQueries.size() + " queries):");
        REPORT.add("  " + vMetrics);
        REPORT.add("  " + rMetrics);
        REPORT.add("  " + fMetrics);
    }

    // ═══════════════════════════════════════════════════════════════
    // Memory Part 1: Setup — plant targets + noise
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(4)
    void setupMemoryEval() {
        System.out.println("\n─── 4. Memory: Setup Evaluation ───");

        if (testUserId == null) {
            System.out.println("  ERROR: No test user");
            return;
        }

        memoryService.clearMemories(testUserId);
        System.out.println("  Cleared existing memories for user " + testUserId);

        // Plant target memories (hand-crafted, distinctive)
        List<String> targets = List.of(
                "[目标记忆#A] 上周二和咨询师深入聊了关于社交焦虑的问题，在人群中总是感到被注视和评判，手心出汗心跳加速，咨询师建议从小范围社交开始练习，比如先和便利店店员说谢谢",
                "[目标记忆#B] 昨天提到了最近频繁做噩梦的事，经常梦见从高处坠落、或者在黑暗中被追赶，咨询师说这可能跟我白天的工作压力有关，建议睡前做渐进式放松",
                "[目标记忆#C] 之前告诉过你我最喜欢的减压方式是去海边散步，听海浪的声音能让我平静下来，海风吹在脸上的感觉能让我忘记烦恼",
                "[目标记忆#D] 上个月我跟妈妈大吵了一架，因为她总是拿我和表姐比较，说表姐又升职了而我还在原地踏步，我气得摔门出去了，到现在还没主动联系她",
                "[目标记忆#E] 最近开始记录每天的三件好事，不管多小的事都写下来，比如今天喝到了好喝的咖啡、同事夸了我的新发型、下班看到了漂亮的晚霞，这个方法确实在慢慢改变我看待生活的方式",
                "[目标记忆#F] 我觉得自己在工作中总是付出很多但没人看到，老板把好项目都给了别人，我做的都是边角料的活，感觉自己像隐形人一样",
                "[目标记忆#G] 最近在尝试断舍离，清理了很多旧物，也删了社交媒体上一些从来不联系的联系人和账号，好像这样心里也清理出了一些空间",
                "[目标记忆#H] 我发现自己很难拒绝别人，同事让我帮忙加班我就加，朋友借钱我就借，明明自己也不宽裕，但就是说不出口那个不字",
                "[目标记忆#I] 上周去做了心理咨询，咨询师说我可能有轻微的广泛性焦虑，给我做了GAD-7量表，得分12分属于中度，建议我做CBT认知行为治疗",
                "[目标记忆#J] 今天是你告诉我的盒式呼吸法练习的第15天，已经能很自然地做了，焦虑的时候不用想就能开始吸气…屏住…呼气…，确实比一开始要好用多了"
        );

        // Queries for each target (1 direct + 1 implicit + 1 noisy)
        Map<Integer, List<String>> targetQueries = new HashMap<>();
        targetQueries.put(0, List.of(
                "最近参加聚会时总觉得别人在看我，很不自在，手心都是汗，想逃",
                "和一群不熟的人在一起的时候，我总是不知道该说什么，尴尬得想消失",
                "今天午饭同事叫我去聚餐我找借口推了，其实我很饿但我更怕社交"
        ));
        targetQueries.put(1, List.of(
                "我最近睡不好，总是做噩梦惊醒，梦见自己从很高的地方掉下来",
                "这几天特别累，睡觉也不踏实，早上醒来比没睡还累",
                "昨天开会被领导批评了，晚上又做了那个被追赶的梦，好累"
        ));
        targetQueries.put(2, List.of(
                "今天心情很烦躁，好想去海边走走，听听海浪的声音",
                "有什么能让人平静下来的方法吗？我现在脑子很乱",
                "周末去了趟海边，发现一个人在沙滩上走走确实比在家刷手机舒服多了"
        ));
        targetQueries.put(3, List.of(
                "家人总是拿我和别人比较，真的很烦，感觉怎么做都不够好",
                "亲戚聚会总是变成比孩子大会，我妈又拿我表姐说事了，想发火",
                "过年不想回家了，每次回去都要被比较工资、比较对象，压力比上班还大"
        ));
        targetQueries.put(4, List.of(
                "想试试每天记录一些积极的事情，但不知道有没有用",
                "最近情绪有些低落，有人建议我做感恩练习，不知道从哪开始",
                "今天其实挺丧的，但同事给我带了一杯奶茶，好像也没那么糟了"
        ));
        targetQueries.put(5, List.of(
                "工作上感到很沮丧，付出了很多但是好像没人看见我的努力",
                "老板永远看不到我做了什么，但他的意思是我还不够努力",
                "同期进公司的人都升职了，只有我还在原地，感觉自己在公司是透明的"
        ));
        targetQueries.put(6, List.of(
                "最近很烦，想清理一下自己的东西，也许该删一些从来不发消息的联系人了",
                "觉得生活里堆了太多东西，不管是实体的还是心里的",
                "周末整理了一下衣柜，扔了两大袋不穿的衣服，好像也把一些不开心的记忆扔掉了"
        ));
        targetQueries.put(7, List.of(
                "同事让我帮忙加班我又答应了，明明自己已经很累了但就是说不出口",
                "我好像不太会拒绝别人，每次都把不字咽回去",
                "朋友又跟我借钱了，我自己也不宽裕但还是借了，事后又后悔"
        ));
        targetQueries.put(8, List.of(
                "我去做了心理评估，咨询师说我可能有焦虑症，建议我做CBT",
                "最近总是莫名紧张，做了个量表说中度焦虑，不知道该不该认真对待",
                "同事说我只是想太多了，但咨询师说我的焦虑水平确实偏高"
        ));
        targetQueries.put(9, List.of(
                "你之前教我的那个呼吸方法真的有用，我现在练习了两周感觉好多了",
                "最近焦虑的时候会下意识开始做呼吸练习，感觉能控制住自己了",
                "盒式呼吸法我现在每天睡前做，睡眠质量好像提高了"
        ));

        // Store target memories with recent timestamps
        long now = System.currentTimeMillis() / 1000;
        int planted = 0;
        for (int i = 0; i < targets.size(); i++) {
            List<Float> emb = embeddingService.embed(targets.get(i));
            if (emb != null) {
                long ts = now - ThreadLocalRandom.current().nextLong(1, 8) * 86400;
                memoryService.storeWithTimestamp(testUserId, targets.get(i), emb, ts);
                planted++;
            }
        }

        System.out.println("  Planted " + planted + " target memories (noise will be added during eval)");

        // Save target memory contents and queries for the eval step
        memTargetContents = new ArrayList<>(targets);
        memTargetQueries = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            memTargetQueries.add(targetQueries.getOrDefault(i, List.of()));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Memory Part 2: Run recall eval at different noise levels
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(5)
    void evaluateMemoryRecall() {
        System.out.println("\n─── 5. Memory: Recall Evaluation ───");

        if (testUserId == null) {
            System.out.println("  ERROR: No test user");
            return;
        }

        if (memTargetContents.isEmpty()) {
            System.out.println("  ERROR: No target memories. Run setupMemoryEval first.");
            return;
        }

        System.out.println("  Evaluating " + memTargetContents.size() + " target memories");
        System.out.println("  Seeding noise in phases: 50 → 100 → 150 → 200");

        // Pre-generate all noise texts (no embedding yet)
        List<String> allNoiseTexts = generateNoiseMemories(NOISE_MEMORY_COUNT);
        long now = System.currentTimeMillis() / 1000;
        int[] noisePhases = {50, 100, 150, 200};
        int noiseStored = 0;
        // Cache embeddings to avoid re-embedding same texts
        Map<Integer, List<Float>> noiseEmbeddings = new HashMap<>();

        for (int phaseTarget : noisePhases) {
            int toStore = phaseTarget - noiseStored;
            System.out.printf("%n  ── Seeding noise %d→%d (%d new) ──%n", noiseStored, phaseTarget, toStore);

            // Embed and store new noise
            for (int i = noiseStored; i < phaseTarget; i++) {
                if (!noiseEmbeddings.containsKey(i)) {
                    List<Float> emb = embeddingService.embed(allNoiseTexts.get(i));
                    if (emb != null) noiseEmbeddings.put(i, emb);
                }
                List<Float> emb = noiseEmbeddings.get(i);
                if (emb != null) {
                    long ts = now - ThreadLocalRandom.current().nextLong(0, 81) * 86400;
                    memoryService.storeWithTimestamp(testUserId, allNoiseTexts.get(i), emb, ts);
                }
            }
            noiseStored = phaseTarget;

            // Run recall eval at this noise level
            int totalHits3 = 0, totalHits5 = 0;
            double totalMRR = 0;
            int totalQueries = 0;

            for (int ti = 0; ti < memTargetContents.size(); ti++) {
                List<String> queries = memTargetQueries.get(ti);
                if (queries.isEmpty()) continue;

                for (String query : queries) {
                    List<Float> emb = embeddingService.embed(query);
                    if (emb == null) continue;

                    List<String> recalled = memoryService.recall(testUserId, emb, 10);
                    totalQueries++;

                    int rank = -1;
                    String targetPrefix = memTargetContents.get(ti).substring(0,
                            Math.min(30, memTargetContents.get(ti).length()));
                    for (int ri = 0; ri < recalled.size(); ri++) {
                        if (recalled.get(ri).contains(targetPrefix)) {
                            rank = ri + 1;
                            break;
                        }
                    }

                    if (rank > 0) {
                        totalMRR += 1.0 / rank;
                        if (rank <= 3) totalHits3++;
                        if (rank <= 5) totalHits5++;
                    }
                }
            }

            double recall3 = totalQueries > 0 ? (double) totalHits3 / totalQueries : 0;
            double recall5 = totalQueries > 0 ? (double) totalHits5 / totalQueries : 0;
            double mrr = totalQueries > 0 ? totalMRR / totalQueries : 0;

            String line = String.format("  Corpus=%d: R@3=%.1f%% R@5=%.1f%% MRR=%.3f (n=%d queries)",
                    noiseStored + memTargetContents.size(), recall3 * 100, recall5 * 100, mrr, totalQueries);
            System.out.println(line);
            REPORT.add("  " + line);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Report
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(99)
    void printReport() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  RECALL EVALUATION REPORT");
        System.out.println("=".repeat(70));
        System.out.println("  KB entries: " + allEntries.size());
        System.out.println("  Test queries (RAG): " + allQueries.size());
        System.out.println("  Target memories: " + PLANTED_MEMORY_COUNT);
        System.out.println("  Noise memories: " + NOISE_MEMORY_COUNT);
        System.out.println();
        for (String line : REPORT) {
            System.out.println(line);
        }
        System.out.println("=".repeat(70));
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Raw Milvus search (replicates KnowledgeService.retrieve internals)
    // ═══════════════════════════════════════════════════════════════
    private List<MilvusHit> searchMilvusRaw(List<Float> queryEmbedding, String categoryFilter,
                                             int topK, String mode) {
        int fetchSize = topK * 3;
        int nprobe = "crisis".equals(mode) ? 32 : 8;

        try {
            var builder = SearchParam.newBuilder()
                    .withCollectionName(knowledgeCollectionName)
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(queryEmbedding))
                    .withMetricType(MetricType.COSINE)
                    .withTopK(fetchSize)
                    .withOutFields(List.of("id", "category", "emotion_tags", "title",
                            "content", "priority", "usage_count"))
                    .withParams("{\"nprobe\": " + nprobe + "}");

            if (categoryFilter != null && !categoryFilter.isEmpty()) {
                builder.withExpr("category == \"" + categoryFilter + "\"");
            }

            R<SearchResults> r = milvusClient.search(builder.build());
            if (r.getStatus() != 0 || r.getData() == null) return List.of();

            var fieldsData = r.getData().getResults().getFieldsDataList();
            int resultCount = 0;
            for (var fd : fieldsData) {
                if ("id".equals(fd.getFieldName())) {
                    resultCount = fd.getScalars().getLongData().getDataList().size();
                    break;
                }
            }
            if (resultCount == 0) return List.of();

            // Parse columns
            Map<String, List<?>> columns = new LinkedHashMap<>();
            for (var fd : fieldsData) {
                String name = fd.getFieldName();
                if ("id".equals(name) || "priority".equals(name) || "usage_count".equals(name)) {
                    columns.put(name, fd.getScalars().getLongData().getDataList());
                } else {
                    columns.put(name, fd.getScalars().getStringData().getDataList());
                }
            }

            List<Float> scores = new ArrayList<>();
            for (var sd : r.getData().getResults().getScoresList()) {
                scores.add(sd);
            }

            List<MilvusHit> hits = new ArrayList<>();
            for (int i = 0; i < resultCount; i++) {
                int idx = i;
                long id = getLongCol(columns, "id", idx);
                String category = getStringCol(columns, "category", idx);
                String tagsJson = getStringCol(columns, "emotion_tags", idx);
                String title = getStringCol(columns, "title", idx);
                String content = getStringCol(columns, "content", idx);
                int priority = (int) getLongCol(columns, "priority", idx);
                int usageCount = (int) getLongCol(columns, "usage_count", idx);
                double cosineScore = i < scores.size() ? scores.get(i) : 0.0;
                List<String> tags = parseTags(tagsJson);

                hits.add(new MilvusHit(id, category, tags, title, content, priority, usageCount, cosineScore));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Raw Milvus search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Find rank of target ID in hit list
    // ═══════════════════════════════════════════════════════════════
    private long findRank(List<MilvusHit> hits, long targetId) {
        for (int i = 0; i < Math.min(hits.size(), EVAL_TOP_K); i++) {
            if (hits.get(i).id == targetId) return i + 1;
        }
        return -1; // not found in top-K
    }

    private long findRankByIdSet(Set<Long> ids, long targetId) {
        int rank = 1;
        for (Long id : ids) {
            if (id == targetId) return rank;
            rank++;
            if (rank > EVAL_TOP_K) break;
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Compute metrics from rank lists
    // ═══════════════════════════════════════════════════════════════
    private EvalMetrics computeMetrics(List<Long> ranks, List<Double> mrrValues,
                                        int totalQueries, String label) {
        int r1 = 0, r3 = 0, r5 = 0;
        double sumMRR = 0;
        double sumNDCG = 0;

        for (long rank : ranks) {
            if (rank == 1) r1++;
            if (rank > 0 && rank <= 3) r3++;
            if (rank > 0 && rank <= 5) r5++;
        }
        for (double v : mrrValues) sumMRR += v;

        // NDCG@5: binary relevance (1.0 if target in position i, 0 otherwise)
        double idcg = 1.0; // ideal: relevance=1 at position 1
        for (long rank : ranks) {
            if (rank > 0 && rank <= EVAL_TOP_K) {
                sumNDCG += 1.0 / (Math.log(rank + 1) / Math.log(2));
            }
        }
        double ndcg5 = totalQueries > 0 ? (sumNDCG / totalQueries) / idcg : 0;

        return new EvalMetrics(
                totalQueries > 0 ? (double) r1 / totalQueries : 0,
                totalQueries > 0 ? (double) r3 / totalQueries : 0,
                totalQueries > 0 ? (double) r5 / totalQueries : 0,
                totalQueries > 0 ? sumMRR / totalQueries : 0,
                ndcg5,
                totalQueries,
                label
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Hard filter analysis
    // ═══════════════════════════════════════════════════════════════
    private void analyzeHardFilter() {
        System.out.println("\n  ── Category Hard Filter Analysis ──");
        int falseExclude = 0;
        int checked = 0;

        for (QueryEval qe : allQueries) {
            if (qe.expectedCategory == null || qe.expectedCategory.isEmpty()) continue;
            // Find the target entry
            EvalEntry target = null;
            for (var e : allEntries) {
                if (e.id == qe.targetEntryId) { target = e; break; }
            }
            if (target == null) continue;

            // If category filter is applied and target's category doesn't match → false exclusion
            if (!qe.expectedCategory.equals(target.category)) {
                falseExclude++;
            }
            checked++;
        }

        if (checked > 0) {
            System.out.printf("  Category filter false-exclusion rate: %d/%d (%.1f%%)%n",
                    falseExclude, checked, 100.0 * falseExclude / checked);
            REPORT.add(String.format("  Hard filter false-exclusion: %d/%d (%.1f%%)",
                    falseExclude, checked, 100.0 * falseExclude / checked));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Load seed entries from knowledge-seed.json
    // ═══════════════════════════════════════════════════════════════
    private List<SeedEntry> loadSeedsFromFile() throws IOException {
        Path seedFile = Path.of("src/main/resources/knowledge-seed.json");
        String json = Files.readString(seedFile);
        List<Map<String, Object>> raw = objectMapper.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});
        List<SeedEntry> seeds = new ArrayList<>();
        for (var m : raw) {
            String category = (String) m.get("category");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) m.get("emotionTags");
            String title = (String) m.get("title");
            String content = (String) m.get("content");
            int priority = ((Number) m.get("priority")).intValue();
            seeds.add(new SeedEntry(category, tags != null ? tags : List.of(), title, content, priority));
        }
        return seeds;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Generate variants via LLM
    // ═══════════════════════════════════════════════════════════════
    private List<VariantEntry> generateVariants(List<SeedEntry> batch) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < batch.size(); i++) {
                var s = batch.get(i);
                if (i > 0) sb.append(",\n");
                sb.append(String.format(
                        "{\"index\":%d,\"category\":\"%s\",\"emotionTags\":%s,\"title\":\"%s\",\"priority\":%d,\"content\":\"%s\"}",
                        i, s.category, objectMapper.writeValueAsString(s.emotionTags),
                        s.title.replace("\"", "\\\""), s.priority,
                        s.content.replace("\"", "\\\"").replace("\n", "\\n")));
            }
            sb.append("\n]");

            String prompt = String.format("""
                    你是一个心理学对话系统知识库构建助手。对每条知识条目生成%d个变体。
                    要求：保持相同的category、emotionTags、priority；不同场景和措辞；保持原有格式结构（如【共情方向】【可用话术】【禁止】等）。
                    以JSON数组输出，每个元素为{index, variants: [{title, content}]}，index对应输入条目的索引。

                    输入条目：
                    %s""", VARIANTS_PER_SEED, sb);

            String resp = deepseekChatClient.prompt().user(prompt).call().content();
            return parseVariantResponse(resp, batch);
        } catch (Exception e) {
            log.warn("Variant generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<VariantEntry> parseVariantResponse(String resp, List<SeedEntry> batch) {
        List<VariantEntry> result = new ArrayList<>();
        try {
            // Extract JSON array from response
            String json = resp;
            int start = resp.indexOf('[');
            int end = resp.lastIndexOf(']');
            if (start >= 0 && end > start) json = resp.substring(start, end + 1);

            List<Map<String, Object>> items = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (var item : items) {
                int idx = ((Number) item.get("index")).intValue();
                SeedEntry seed = idx < batch.size() ? batch.get(idx) : null;
                if (seed == null) continue;
                List<Map<String, String>> variants = (List<Map<String, String>>) item.get("variants");
                if (variants == null) continue;
                for (var v : variants) {
                    result.add(new VariantEntry(seed.category, seed.emotionTags,
                            v.get("title"), v.get("content"), seed.priority));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse variant response: {}", e.getMessage());
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Generate test queries via LLM
    // ═══════════════════════════════════════════════════════════════
    private List<QueryMapping> generateQueries(List<EvalEntry> batch, int globalOffset) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < batch.size(); i++) {
                var e = batch.get(i);
                if (i > 0) sb.append(",\n");
                String shortContent = e.content.length() > 150
                        ? e.content.substring(0, 150) + "..."
                        : e.content;
                sb.append(String.format(
                        "{\"index\":%d,\"id\":%d,\"category\":\"%s\",\"emotionTags\":%s,\"title\":\"%s\",\"content\":\"%s\"}",
                        i, e.id, e.category,
                        objectMapper.writeValueAsString(e.emotionTags),
                        e.title.replace("\"", "\\\""),
                        shortContent.replace("\"", "\\\"").replace("\n", "\\n")));
            }
            sb.append("\n]");

            String prompt = String.format("""
                    你是对话系统测试数据生成助手。对每条知识条目生成%d条用户消息，这些消息应触发系统检索到对应的知识条目。
                    要求：
                    - 第1条：直接表达情绪和需求
                    - 第2条：含蓄暗示，不直接说关键词
                    - 第3条：在聊其他话题时提到相关情绪
                    输出JSON数组，每个元素为{index, queries: [q1, q2, q3]}，index对应输入条目的索引。

                    知识条目：
                    %s""", QUERIES_PER_ENTRY, sb);

            String resp = deepseekChatClient.prompt().user(prompt).call().content();
            return parseQueryResponse(resp, globalOffset);
        } catch (Exception e) {
            log.warn("Query generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<QueryMapping> parseQueryResponse(String resp, int globalOffset) {
        List<QueryMapping> result = new ArrayList<>();
        try {
            String json = resp;
            int start = resp.indexOf('[');
            int end = resp.lastIndexOf(']');
            if (start >= 0 && end > start) json = resp.substring(start, end + 1);

            List<Map<String, Object>> items = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (var item : items) {
                long batchIndex = ((Number) item.get("index")).longValue();
                long globalIndex = globalOffset + batchIndex;
                @SuppressWarnings("unchecked")
                List<String> queries = (List<String>) item.get("queries");
                if (queries == null) continue;
                for (String q : queries) {
                    result.add(new QueryMapping(globalIndex, q, List.of(), ""));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse query response: {}", e.getMessage());
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Store seeds + variants in Milvus
    // ═══════════════════════════════════════════════════════════════
    private void storeSeedsAndVariants(List<SeedEntry> seeds, List<VariantEntry> variants) {
        knowledgeService.clearAll();
        allEntries.clear();

        // Store seeds
        for (SeedEntry seed : seeds) {
            List<Float> emb = embeddingService.embed(seed.content);
            if (emb == null) continue;
            knowledgeService.store(seed.category, seed.emotionTags, seed.title, seed.content, seed.priority, emb);
        }
        // Store variants
        for (VariantEntry v : variants) {
            List<Float> emb = embeddingService.embed(v.content);
            if (emb == null) continue;
            knowledgeService.store(v.category, v.emotionTags, v.title, v.content, v.priority, emb);
        }

        // Read back all entries to get their Milvus-generated IDs
        reloadAllEntries();
    }

    private void storeAllEntries(CachedData cached) {
        knowledgeService.clearAll();
        allEntries.clear();

        for (SeedEntry s : cached.seeds) {
            List<Float> emb = embeddingService.embed(s.content);
            if (emb != null) knowledgeService.store(s.category, s.emotionTags, s.title, s.content, s.priority, emb);
        }
        for (VariantEntry v : cached.variants) {
            List<Float> emb = embeddingService.embed(v.content);
            if (emb != null) knowledgeService.store(v.category, v.emotionTags, v.title, v.content, v.priority, emb);
        }
        reloadAllEntries();
    }

    private void reloadAllEntries() {
        allEntries.clear();
        try {
            var r = milvusClient.query(
                    io.milvus.param.dml.QueryParam.newBuilder()
                            .withCollectionName(knowledgeCollectionName)
                            .withExpr("id >= 0")
                            .withOutFields(List.of("id", "category", "emotion_tags", "title",
                                    "content", "priority", "usage_count"))
                            .withLimit(200L)
                            .build());
            if (r.getStatus() != 0 || r.getData() == null) return;

            Map<String, List<?>> columns = new LinkedHashMap<>();
            for (var fd : r.getData().getFieldsDataList()) {
                String name = fd.getFieldName();
                if ("id".equals(name) || "priority".equals(name) || "usage_count".equals(name)) {
                    columns.put(name, fd.getScalars().getLongData().getDataList());
                } else {
                    columns.put(name, fd.getScalars().getStringData().getDataList());
                }
            }

            int count = 0;
            List<?> idList = columns.get("id");
            if (idList != null) count = idList.size();
            for (int i = 0; i < count; i++) {
                int idx = i;
                long id = getLongCol(columns, "id", idx);
                String category = getStringCol(columns, "category", idx);
                String title = getStringCol(columns, "title", idx);
                String content = getStringCol(columns, "content", idx);
                int priority = (int) getLongCol(columns, "priority", idx);
                int usageCount = (int) getLongCol(columns, "usage_count", idx);
                List<String> tags = parseTags(getStringCol(columns, "emotion_tags", idx));
                allEntries.add(new EvalEntry(id, category, tags, title, content, priority, usageCount));
            }
        } catch (Exception e) {
            log.warn("Failed to reload entries from Milvus: {}", e.getMessage());
        }
    }

    private void buildQueryEvalsFromMappings(List<QueryMapping> mappings) {
        allQueries.clear();
        for (QueryMapping qm : mappings) {
            if (qm.entryIndex < allEntries.size()) {
                EvalEntry entry = allEntries.get((int) qm.entryIndex);
                allQueries.add(new QueryEval(qm.query, entry.id, entry.emotionTags, entry.category));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Cache I/O
    // ═══════════════════════════════════════════════════════════════
    private CachedData loadCache() {
        try {
            if (Files.exists(CACHE_FILE)) {
                return objectMapper.readValue(CACHE_FILE.toFile(), CachedData.class);
            }
        } catch (Exception e) {
            log.debug("No cache found: {}", e.getMessage());
        }
        return null;
    }

    private void saveCache(List<SeedEntry> seeds, List<VariantEntry> variants, List<QueryMapping> queries) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(CACHE_FILE.toFile(), new CachedData(seeds, variants, queries));
            log.info("Cache saved: {}", CACHE_FILE);
        } catch (Exception e) {
            log.warn("Failed to save cache: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Noise memory generation (programmatic, no LLM)
    // ═══════════════════════════════════════════════════════════════
    private static final String[] NOISE_TIMES = {
            "今天", "昨天", "上周", "前几天", "最近", "这周一", "上周末", "月初",
            "半个月前", "三周前", "上个月", "前一阵子", "最近几天", "这周末", "昨天下午"
    };
    private static final String[] NOISE_TOPICS = {
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
    private static final String[] NOISE_EMOTIONS = {
            "感到非常焦虑和不安", "情绪有些低落和疲惫", "处于一种迷茫的状态",
            "觉得内心很平静", "有些烦躁和不满", "既期待又害怕",
            "感到深深的孤独", "对自己有些失望", "心里堵得慌",
            "有一种无力感", "感到被忽视和冷落", "内心充满矛盾"
    };

    private List<String> generateNoiseMemories(int count) {
        List<String> texts = new ArrayList<>();
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            String time = NOISE_TIMES[rng.nextInt(NOISE_TIMES.length)];
            String topic = NOISE_TOPICS[rng.nextInt(NOISE_TOPICS.length)];
            String emotion = NOISE_EMOTIONS[rng.nextInt(NOISE_EMOTIONS.length)];
            texts.add(String.format("[干扰记忆#%d] %s用户%s，%s。", i + 1, time, topic, emotion));
        }
        return texts;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: Column parsers (replicated from KnowledgeService)
    // ═══════════════════════════════════════════════════════════════
    private String getStringCol(Map<String, List<?>> columns, String name, int idx) {
        List<?> list = columns.get(name);
        if (list == null || idx >= list.size()) return "";
        Object val = list.get(idx);
        return val != null ? val.toString() : "";
    }

    private long getLongCol(Map<String, List<?>> columns, String name, int idx) {
        List<?> list = columns.get(name);
        if (list == null || idx >= list.size()) return 0L;
        Object val = list.get(idx);
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private List<String> parseTags(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
