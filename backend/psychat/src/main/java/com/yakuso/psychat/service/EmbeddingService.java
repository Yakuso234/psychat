package com.yakuso.psychat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<Float> embed(String text) {
        try {
            var request = new EmbeddingRequest(
                    List.of(text),
                    OpenAiEmbeddingOptions.builder().build());
            var response = embeddingModel.call(request);
            if (response.getResults().isEmpty()) {
                return null;
            }
            float[] output = response.getResults().get(0).getOutput();
            List<Float> result = new ArrayList<>(output.length);
            for (float v : output) {
                result.add(v);
            }
            return result;
        } catch (Exception e) {
            log.error("Embedding failed: {}", e.getMessage());
            return null;
        }
    }
}
