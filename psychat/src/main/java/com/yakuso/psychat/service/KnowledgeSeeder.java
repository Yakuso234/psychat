package com.yakuso.psychat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    private final KnowledgeService knowledgeService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public KnowledgeSeeder(KnowledgeService knowledgeService,
                           EmbeddingService embeddingService,
                           ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        try {
            if (!knowledgeService.isEmpty()) {
                log.info("Knowledge base already populated, skipping seed.");
                return;
            }
        } catch (Exception e) {
            log.warn("Could not check knowledge base status: {}", e.getMessage());
            return;
        }
        doSeed();
    }

    @Async
    public void forceReSeed() {
        log.info("Force re-seeding knowledge base...");
        doSeed();
    }

    private void doSeed() {
        log.info("Seeding from knowledge-seed.json...");

        try {
            ClassPathResource resource = new ClassPathResource("knowledge-seed.json");
            List<Map<String, Object>> seeds;
            try (InputStream is = resource.getInputStream()) {
                seeds = objectMapper.readValue(is,
                        new TypeReference<List<Map<String, Object>>>() {});
            }

            int stored = 0;
            for (var s : seeds) {
                try {
                    String category = (String) s.get("category");
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) s.get("emotionTags");
                    String title = (String) s.get("title");
                    String content = (String) s.get("content");
                    int priority = ((Number) s.get("priority")).intValue();

                    // Build searchable text: title + first 200 chars of content for embedding
                    String searchable = title + "\n" + content.substring(0, Math.min(200, content.length()));
                    List<Float> embedding = embeddingService.embed(searchable);
                    if (embedding == null) {
                        log.warn("Embedding failed for: {}", title);
                        continue;
                    }

                    knowledgeService.store(category, tags, title, content, priority, embedding);
                    stored++;

                    // throttle to avoid overwhelming the embedding API
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                } catch (Exception e) {
                    log.warn("Failed to store seed entry: {}", e.getMessage());
                }
            }

            log.info("Knowledge seeding complete: {}/{} entries stored", stored, seeds.size());
        } catch (Exception e) {
            log.error("Knowledge seeding failed: {}", e.getMessage(), e);
        }
    }
}
