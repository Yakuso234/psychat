package com.yakuso.psychat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;

    public RerankService(
            @Value("${siliconflow.api-key:}") String apiKey,
            @Value("${siliconflow.base-url:https://api.siliconflow.cn}") String baseUrl,
            @Value("${rerank.model:BAAI/bge-reranker-v2-m3}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.enabled = apiKey != null && !apiKey.isBlank()
                && !apiKey.contains("your-siliconflow-key");
        if (enabled) {
            log.info("Rerank enabled via SiliconFlow: {} (model={})", baseUrl, model);
        } else {
            log.info("Rerank disabled (no SILICONFLOW_API_KEY configured)");
        }
    }

    public record ScoredDocument(int index, String text, double score) {}

    public List<ScoredDocument> rerank(String query, List<String> documents, int topN) {
        if (!enabled || documents.isEmpty()) {
            return defaultOrder(documents.size());
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "query", query,
                    "documents", documents,
                    "top_n", Math.min(topN, documents.size())
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/rerank"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Rerank error {}: {} - falling back", response.statusCode(), response.body());
                return defaultOrder(documents.size());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.get("results");
            if (results == null) return defaultOrder(documents.size());

            List<ScoredDocument> scored = new ArrayList<>();
            for (JsonNode r : results) {
                scored.add(new ScoredDocument(
                        r.get("index").asInt(),
                        documents.get(r.get("index").asInt()),
                        r.get("relevance_score").asDouble()));
            }
            scored.sort(java.util.Comparator.comparingInt(ScoredDocument::index));
            return scored;
        } catch (Exception e) {
            log.warn("Rerank failed: {} - falling back", e.getMessage());
            return defaultOrder(documents.size());
        }
    }

    private List<ScoredDocument> defaultOrder(int size) {
        List<ScoredDocument> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(new ScoredDocument(i, "", 0.0));
        }
        return list;
    }
}
