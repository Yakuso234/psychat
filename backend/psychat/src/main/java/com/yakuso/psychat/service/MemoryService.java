package com.yakuso.psychat.service;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MilvusServiceClient milvusClient;
    private final String collectionName;
    private boolean loaded = false;

    // Time decay constants
    private static final double DECAY_FULL = 1.0;
    private static final double DECAY_MID = 0.5;
    private static final double DECAY_LOW = 0.1;
    private static final double DAY_SECONDS = 24.0 * 3600.0;
    private static final long EXPIRE_SECONDS = 90L * 24 * 3600;

    // Per-user memory cap (configurable, default 500)
    @Value("${app.memory.max-per-user:500}")
    private int maxMemoriesPerUser;

    public MemoryService(MilvusServiceClient milvusClient,
                         @Value("${milvus.collection-name}") String collectionName) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
    }

    public void store(Long userId, String content, List<Float> embedding) {
        ensureLoaded();

        evictIfNeeded(userId);

        String linkedIds = findLinkedMemories(userId, embedding);

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("user_id", List.of(userId)));
        fields.add(new InsertParam.Field("content", List.of(content)));
        fields.add(new InsertParam.Field("embedding", List.of(embedding)));
        fields.add(new InsertParam.Field("created_at", List.of(System.currentTimeMillis() / 1000)));
        fields.add(new InsertParam.Field("linked_memory_ids", List.of(linkedIds)));

        R<MutationResult> r = milvusClient.insert(
                InsertParam.newBuilder().withCollectionName(collectionName).withFields(fields).build());
        if (r.getStatus() != 0) {
            log.warn("Store to {} failed: {}", collectionName, r.getMessage());
        } else {
            log.debug("Memory stored with links: {}", linkedIds);
        }
    }

    private String findLinkedMemories(Long userId, List<Float> queryEmbedding) {
        try {
            R<SearchResults> r = milvusClient.search(
                    SearchParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withVectorFieldName("embedding")
                            .withVectors(List.of(queryEmbedding))
                            .withMetricType(MetricType.COSINE)
                            .withTopK(4)
                            .withExpr("user_id == " + userId)
                            .withOutFields(List.of("id"))
                            .withParams("{\"nprobe\": 16}")
                            .build());
            if (r.getStatus() != 0 || r.getData() == null) return "[]";
            List<Long> ids = new ArrayList<>();
            for (var fd : r.getData().getResults().getFieldsDataList()) {
                if ("id".equals(fd.getFieldName())) {
                    for (var v : fd.getScalars().getLongData().getDataList()) {
                        ids.add(v);
                    }
                }
            }
            if (ids.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(ids.size(), 3); i++) {
                if (i > 0) sb.append(",");
                sb.append(ids.get(i));
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to find linked memories: {}", e.getMessage());
            return "[]";
        }
    }

    public void storeWithTimestamp(Long userId, String content, List<Float> embedding, long createdAtSeconds) {
        ensureLoaded();

        evictIfNeeded(userId);

        String linkedIds = findLinkedMemories(userId, embedding);

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("user_id", List.of(userId)));
        fields.add(new InsertParam.Field("content", List.of(content)));
        fields.add(new InsertParam.Field("embedding", List.of(embedding)));
        fields.add(new InsertParam.Field("created_at", List.of(createdAtSeconds)));
        fields.add(new InsertParam.Field("linked_memory_ids", List.of(linkedIds)));

        R<MutationResult> r = milvusClient.insert(
                InsertParam.newBuilder().withCollectionName(collectionName).withFields(fields).build());
        if (r.getStatus() != 0) {
            log.warn("Store to {} failed: {}", collectionName, r.getMessage());
        }
    }

    private void evictIfNeeded(Long userId) {
        long now = System.currentTimeMillis() / 1000;
        long expireThreshold = now - EXPIRE_SECONDS;

        var r = milvusClient.query(
                io.milvus.param.dml.QueryParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withExpr("user_id == " + userId)
                        .withOutFields(List.of("id", "created_at"))
                        .withLimit((long) (maxMemoriesPerUser + 1))
                        .build());

        if (r.getStatus() != 0 || r.getData() == null) return;

        List<Long> ids = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (var fd : r.getData().getFieldsDataList()) {
            if ("id".equals(fd.getFieldName())) {
                for (var v : fd.getScalars().getLongData().getDataList()) ids.add(v);
            } else if ("created_at".equals(fd.getFieldName())) {
                for (var v : fd.getScalars().getLongData().getDataList()) times.add(v);
            }
        }

        if (ids.size() <= maxMemoriesPerUser) return;

        // sort: expired first (priority evict), then oldest first (LRU)
        record MemEntry(long id, long time, boolean expired) {}
        List<MemEntry> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long t = i < times.size() ? times.get(i) : 0;
            entries.add(new MemEntry(ids.get(i), t, t < expireThreshold));
        }
        entries.sort((a, b) -> {
            if (a.expired != b.expired) return a.expired ? -1 : 1;
            return Long.compare(a.time, b.time);
        });

        int toRemove = ids.size() - maxMemoriesPerUser;
        String deleteIds = entries.stream()
                .limit(toRemove)
                .map(e -> String.valueOf(e.id))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        milvusClient.delete(io.milvus.param.dml.DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("id in [" + deleteIds + "]")
                .build());

        long expiredCount = entries.stream().limit(toRemove).filter(e -> e.expired).count();
        log.info("Evicted {} memories for user {} ({} expired>90d, {} LRU, limit={})",
                toRemove, userId, expiredCount, toRemove - expiredCount, maxMemoriesPerUser);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredMemories() {
        ensureLoaded();
        long expireThreshold = System.currentTimeMillis() / 1000 - EXPIRE_SECONDS;
        try {
            var r = milvusClient.delete(io.milvus.param.dml.DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("created_at < " + expireThreshold)
                    .build());
            if (r.getStatus() == 0) {
                log.info("Scheduled cleanup: deleted expired memories (created_at < {})", expireThreshold);
            }
        } catch (Exception e) {
            log.warn("Scheduled cleanup failed: {}", e.getMessage());
        }
    }

    public List<String> recall(Long userId, List<Float> queryEmbedding, int topK) {
        ensureLoaded();

        int fetchSize = topK * 3;

        R<SearchResults> r = milvusClient.search(
                SearchParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withVectorFieldName("embedding")
                        .withVectors(List.of(queryEmbedding))
                        .withMetricType(MetricType.COSINE)
                        .withTopK(fetchSize)
                        .withExpr("user_id == " + userId)
                        .withOutFields(List.of("id", "content", "created_at", "linked_memory_ids"))
                        .withParams("{\"nprobe\": 16}")
                        .build());

        if (r.getStatus() != 0 || r.getData() == null) {
            return List.of();
        }

        // parse results into columns
        var fieldsData = r.getData().getResults().getFieldsDataList();
        int resultCount = 0;
        for (var fd : fieldsData) {
            if ("id".equals(fd.getFieldName())) {
                resultCount = fd.getScalars().getLongData().getDataList().size();
                break;
            }
        }
        if (resultCount == 0) return List.of();

        List<String> contents = new ArrayList<>();
        List<Long> createdAts = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        List<String> linkedIdsList = new ArrayList<>();
        for (var fd : fieldsData) {
            String name = fd.getFieldName();
            if ("content".equals(name)) {
                for (var s : fd.getScalars().getStringData().getDataList()) {
                    contents.add(s.toString());
                }
            } else if ("linked_memory_ids".equals(name)) {
                for (var s : fd.getScalars().getStringData().getDataList()) {
                    linkedIdsList.add(s.toString());
                }
            } else if ("id".equals(name)) {
                for (var v : fd.getScalars().getLongData().getDataList()) {
                    ids.add(v);
                }
            } else if ("created_at".equals(name)) {
                for (var v : fd.getScalars().getLongData().getDataList()) {
                    createdAts.add(v);
                }
            }
        }

        // parse cosine scores
        List<Double> scores = new ArrayList<>();
        for (var sd : r.getData().getResults().getScoresList()) {
            scores.add((double) sd);
        }

        // build scored entries with time decay
        record ScoredMemory(int idx, String content, double cosine, double decay,
                            double finalScore, double ageDays) {}
        List<ScoredMemory> scored = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;

        log.info("Memory recall candidates (total={}) for user {}", resultCount, userId);

        for (int i = 0; i < resultCount && i < contents.size(); i++) {
            double cosine = i < scores.size() ? scores.get(i) : 0.0;
            long createdAt = i < createdAts.size() ? createdAts.get(i) : now;
            double decay = timeDecay(now, createdAt);
            double ageDays = (now - createdAt) / DAY_SECONDS;
            double finalScore = cosine * decay;

            if (decay <= 0) {
                log.debug("  #{} expired ({}d old): skipped", i + 1, Math.round(ageDays));
                continue;
            }

            String preview = contents.get(i).length() > 40
                    ? contents.get(i).substring(0, 40) + "..."
                    : contents.get(i);
            log.info("  #{} cos={} decay={} ({}d) -> final={} | {}",
                    i + 1,
                    String.format("%.3f", cosine),
                    String.format("%.2f", decay),
                    Math.round(ageDays),
                    String.format("%.3f", finalScore),
                    preview);

            scored.add(new ScoredMemory(i, contents.get(i), cosine, decay, finalScore, ageDays));
        }

        scored.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        // log selected topK
        if (!scored.isEmpty()) {
            log.info("Memory recall top-{} (from {} candidates after expiry filter):",
                    Math.min(topK, scored.size()), scored.size());
            for (int i = 0; i < Math.min(topK, scored.size()); i++) {
                var m = scored.get(i);
                String preview = m.content.length() > 50
                        ? m.content.substring(0, 50) + "..."
                        : m.content;
                log.info("  #{} cos={} x decay={} ({}d) = {} | {}",
                        i + 1,
                        String.format("%.3f", m.cosine),
                        String.format("%.2f", m.decay),
                        Math.round(m.ageDays),
                        String.format("%.3f", m.finalScore),
                        preview);
            }
        }

        List<String> topContents = new ArrayList<>();
        java.util.Set<Long> linkedIds = new java.util.LinkedHashSet<>();

        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            var m = scored.get(i);
            topContents.add(m.content());

            if (m.idx() < linkedIdsList.size()) {
                String linkedJson = linkedIdsList.get(m.idx());
                if (linkedJson != null && !linkedJson.equals("[]") && !linkedJson.isBlank()) {
                    try {
                        for (String p : linkedJson.replace("[", "").replace("]", "").split(",")) {
                            String t = p.trim();
                            if (!t.isEmpty()) linkedIds.add(Long.parseLong(t));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (!linkedIds.isEmpty()) {
            var linkedContents = queryByIds(linkedIds);
            for (String linked : linkedContents) {
                if (!topContents.contains(linked)) {
                    topContents.add(linked);
                    log.info("  linked: {}", linked.length() > 50 ? linked.substring(0, 50) + "..." : linked);
                }
            }
        }

        return topContents;
    }

    private List<String> queryByIds(java.util.Set<Long> ids) {
        try {
            String idList = ids.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
            if (idList.isEmpty()) return List.of();
            var r = milvusClient.query(
                    io.milvus.param.dml.QueryParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withExpr("id in [" + idList + "]")
                            .withOutFields(List.of("content"))
                            .withLimit((long) ids.size())
                            .build());
            List<String> results = new ArrayList<>();
            if (r.getStatus() == 0 && r.getData() != null) {
                for (var fd : r.getData().getFieldsDataList()) {
                    if ("content".equals(fd.getFieldName())) {
                        for (var s : fd.getScalars().getStringData().getDataList()) {
                            results.add(s.toString());
                        }
                    }
                }
            }
            return results;
        } catch (Exception e) {
            log.debug("Failed to query linked memories: {}", e.getMessage());
            return List.of();
        }
    }

    private double timeDecay(long now, long createdAtSeconds) {
        double ageDays = (now - createdAtSeconds) / DAY_SECONDS;

        if (ageDays <= 7) return DECAY_FULL;
        if (ageDays <= 30) {
            // linear: 1.0 at day 7 → 0.5 at day 30
            return DECAY_FULL - (ageDays - 7) / 23.0 * (DECAY_FULL - DECAY_MID);
        }
        if (ageDays <= 90) {
            // linear: 0.5 at day 30 → 0.1 at day 90
            return DECAY_MID - (ageDays - 30) / 60.0 * (DECAY_MID - DECAY_LOW);
        }
        return 0.0;
    }

    public List<String> listAll(Long userId, int limit) {
        ensureLoaded();
        var r = milvusClient.query(
                io.milvus.param.dml.QueryParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withExpr("user_id == " + userId)
                        .withOutFields(List.of("content"))
                        .withLimit((long) limit)
                        .build());

        List<String> results = new ArrayList<>();
        if (r.getStatus() == 0 && r.getData() != null) {
            for (var field : r.getData().getFieldsDataList()) {
                if ("content".equals(field.getFieldName())) {
                    for (var s : field.getScalars().getStringData().getDataList()) {
                        results.add(s.toString());
                    }
                }
            }
        }
        return results;
    }

    public void clearMemories(Long userId) {
        ensureLoaded();
        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("user_id == " + userId)
                .build());
        log.info("Cleared all memories for user {}", userId);
    }

    private void ensureLoaded() {
        if (loaded) return;
        var r = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (r.getStatus() == 0) {
            loaded = true;
            log.info("Memory collection loaded: {}", collectionName);
        } else {
            log.warn("Failed to load memory collection {}: {}", collectionName, r.getMessage());
        }
    }
}
