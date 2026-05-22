package com.yakuso.psychat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final MilvusServiceClient milvusClient;
    private final ObjectMapper objectMapper;
    private final RerankService rerankService;
    private final String collectionName;
    private boolean loaded = false;

    public KnowledgeService(MilvusServiceClient milvusClient,
                            ObjectMapper objectMapper,
                            RerankService rerankService,
                            @Value("${milvus.knowledge-collection-name}") String collectionName) {
        this.milvusClient = milvusClient;
        this.objectMapper = objectMapper;
        this.rerankService = rerankService;
        this.collectionName = collectionName;
    }

    public record KnowledgeEntry(
            long id,
            String category,
            List<String> emotionTags,
            String title,
            String content,
            int priority,
            int usageCount
    ) {}

    public record RetrievalResult(
            KnowledgeEntry entry,
            double score
    ) {}

    public void store(String category, List<String> emotionTags, String title,
                      String content, int priority, List<Float> embedding) {
        ensureLoaded();

        try {
            String tagsJson = objectMapper.writeValueAsString(emotionTags);
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("category", List.of(category)));
            fields.add(new InsertParam.Field("emotion_tags", List.of(tagsJson)));
            fields.add(new InsertParam.Field("title", List.of(title)));
            fields.add(new InsertParam.Field("content", List.of(content)));
            fields.add(new InsertParam.Field("embedding", List.of(embedding)));
            fields.add(new InsertParam.Field("priority", List.of((long) priority)));
            fields.add(new InsertParam.Field("usage_count", List.of(0L)));

            R<MutationResult> r = milvusClient.insert(
                    InsertParam.newBuilder().withCollectionName(collectionName).withFields(fields).build());
            if (r.getStatus() != 0) {
                log.warn("Store knowledge failed: {}", r.getMessage());
            }
        } catch (Exception e) {
            log.warn("Store knowledge error: {}", e.getMessage());
        }
    }

    /**
     * Hybrid retrieval: vector similarity + emotion tag bonus + priority weighting.
     * Fetches topK*3 candidates via vector search, then re-ranks in Java.
     */
    public List<RetrievalResult> retrieve(List<Float> queryEmbedding, String userQuery,
                                          List<String> activeEmotionTags,
                                          String categoryFilter, int topK, String mode) {
        ensureLoaded();

        int fetchSize = topK * 3;
        List<RetrievalResult> results = new ArrayList<>();

        // dynamic nprobe: crisis mode needs higher recall, normal mode favors latency
        int nprobe = "crisis".equals(mode) ? 32 : 8;

        try {
            var builder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(queryEmbedding))
                    .withMetricType(MetricType.COSINE)
                    .withTopK(fetchSize)
                    .withOutFields(List.of("id", "category", "emotion_tags", "title", "content", "priority", "usage_count"))
                    .withParams("{\"nprobe\": " + nprobe + "}");

            // hard filter: when searching for a specific category, restrict to it
            if (categoryFilter != null) {
                builder.withExpr("category == \"" + categoryFilter + "\"");
            }

            R<SearchResults> r = milvusClient.search(builder.build());

            if (r.getStatus() != 0 || r.getData() == null) {
                log.warn("Knowledge retrieval failed: status={} msg={}",
                        r.getStatus(), r.getMessage());
                return results;
            }

            var fieldsData = r.getData().getResults().getFieldsDataList();
            int scoresCount = r.getData().getResults().getScoresCount();

            int resultCount = 0;
            for (var fd : fieldsData) {
                if ("id".equals(fd.getFieldName())) {
                    resultCount = fd.getScalars().getLongData().getDataList().size();
                    break;
                }
            }
            if (resultCount == 0) {
                resultCount = scoresCount;
            }

            // parse each field into column arrays
            Map<String, List<?>> columns = new LinkedHashMap<>();
            for (var fd : fieldsData) {
                String name = fd.getFieldName();
                if ("id".equals(name) || "priority".equals(name) || "usage_count".equals(name)) {
                    columns.put(name, fd.getScalars().getLongData().getDataList());
                } else {
                    columns.put(name, fd.getScalars().getStringData().getDataList());
                }
            }

            // scores come from the search result separately
            List<Float> scores = new ArrayList<>();
            for (var sd : r.getData().getResults().getScoresList()) {
                scores.add(sd);
            }

            // collect document contents for rerank
            List<String> documents = new ArrayList<>();
            for (int i = 0; i < resultCount; i++) {
                documents.add(getString(columns, "content", i));
            }

            // rerank by semantic relevance (uses Jina Reranker if configured)
            var rerankResults = rerankService.rerank(userQuery, documents, Math.min(resultCount, 20));
            double[] rerankScores = new double[resultCount];
            for (var rr : rerankResults) {
                if (rr.index() < resultCount) {
                    rerankScores[rr.index()] = rr.score();
                }
            }
            boolean useRerank = rerankResults.stream().anyMatch(rr -> rr.score() > 0);

            log.info("RAG candidates: filter={}, tags={}, fetchSize={}, topK={}, rerank={}",
                    categoryFilter != null ? categoryFilter : "none",
                    activeEmotionTags != null ? String.join(",", activeEmotionTags) : "none",
                    fetchSize, topK, useRerank ? "enabled" : "fallback");

            for (int i = 0; i < resultCount; i++) {
                int idx = i;
                String category = getString(columns, "category", idx);
                String tagsJson = getString(columns, "emotion_tags", idx);
                String title = getString(columns, "title", idx);
                String content = getString(columns, "content", idx);
                long id = getLong(columns, "id", idx);
                int priority = (int) getLong(columns, "priority", idx);
                int usageCount = (int) getLong(columns, "usage_count", idx);

                List<String> tags = parseTags(tagsJson);
                double cosineScore = i < scores.size() ? scores.get(i) : 0.0;
                double baseScore = useRerank ? rerankScores[i] : cosineScore;

                // --- re-rank ---
                double finalScore = baseScore;

                // category boost (exact match gets +0.15)
                boolean catMatch = categoryFilter != null && categoryFilter.equals(category);
                if (catMatch) {
                    finalScore += 0.15;
                }

                // emotion tag match bonus (+0.1 per matching tag, max +0.3)
                int tagMatches = 0;
                if (activeEmotionTags != null) {
                    for (String t : activeEmotionTags) {
                        if (tags.contains(t)) tagMatches++;
                    }
                }
                double tagBonus = Math.min(tagMatches * 0.1, 0.3);
                finalScore += tagBonus;

                // priority weighting (0-10 scale, multiply into score)
                double priorityFactor = 1.0 + (priority - 5) * 0.04;
                finalScore *= priorityFactor;

                // usage_count decay (avoid repeating)
                double usageFactor = Math.max(0.5, 1.0 - usageCount * 0.1);
                finalScore *= usageFactor;

                String scoreLabel = useRerank ? "rerank" : "cos";
                log.info("  #{} [{}] {}={} cat={} tag={} pri=x{} usg=x{} -> {} | {}",
                        i + 1,
                        category,
                        scoreLabel,
                        String.format("%.3f", baseScore),
                        catMatch ? "+0.15" : " -",
                        tagBonus > 0 ? "+" + String.format("%.2f", tagBonus) : " -",
                        String.format("%.2f", priorityFactor),
                        String.format("%.2f", usageFactor),
                        String.format("%.3f", finalScore),
                        title);

                results.add(new RetrievalResult(
                        new KnowledgeEntry(id, category, tags, title, content, priority, usageCount),
                        finalScore));
            }

            results.sort((a, b) -> Double.compare(b.score, a.score));

            // log final selection
            List<RetrievalResult> selected = results.stream().limit(topK).collect(Collectors.toList());
            log.info("RAG selected top-{}/{}:", selected.size(), results.size());
            for (int i = 0; i < selected.size(); i++) {
                var sel = selected.get(i);
                String catEmoji = switch (sel.entry().category()) {
                    case "crisis" -> "#";
                    case "intervention" -> "+";
                    case "empathy" -> "~";
                    case "forbidden" -> "!";
                    default -> "-";
                };
                log.info("  {}{} [{}] {} (score={})",
                        catEmoji, i + 1, sel.entry().category(), sel.entry().title(),
                        String.format("%.3f", sel.score()));
            }

            return selected;
        } catch (Exception e) {
            log.warn("Knowledge retrieval error: {}", e.getMessage());
        }

        return List.of();
    }

    public void recordUsage(List<Long> ids) {
        // Milvus doesn't support in-place update easily; we log it for now.
        // Future: maintain a Redis counter per knowledge entry.
        log.debug("Knowledge usage recorded: {}", ids);
    }

    public boolean isEmpty() {
        ensureLoaded();
        try {
            var r = milvusClient.query(
                    io.milvus.param.dml.QueryParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withExpr("id >= 0")
                            .withOutFields(List.of("id"))
                            .withLimit(1L)
                            .build());
            if (r.getStatus() == 0 && r.getData() != null) {
                for (var fd : r.getData().getFieldsDataList()) {
                    if ("id".equals(fd.getFieldName())) {
                        return fd.getScalars().getLongData().getDataList().isEmpty();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("isEmpty check failed: {}", e.getMessage());
        }
        return true;
    }

    public void clearAll() {
        ensureLoaded();
        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("id >= 0")
                .build());
        log.info("Cleared all knowledge entries");
    }

    private void ensureLoaded() {
        if (loaded) return;
        var r = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (r.getStatus() == 0) {
            loaded = true;
            log.info("Knowledge collection loaded: {}", collectionName);
        } else {
            log.warn("Failed to load knowledge collection {}: {}", collectionName, r.getMessage());
        }
    }

    private List<String> parseTags(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String getString(Map<String, List<?>> columns, String name, int idx) {
        List<?> list = columns.get(name);
        if (list == null || idx >= list.size()) return "";
        Object val = list.get(idx);
        return val != null ? val.toString() : "";
    }

    private long getLong(Map<String, List<?>> columns, String name, int idx) {
        List<?> list = columns.get(name);
        if (list == null || idx >= list.size()) return 0L;
        Object val = list.get(idx);
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }
}
