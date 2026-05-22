package com.yakuso.psychat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
@Configuration
public class AiConfig {

    // ── DeepSeek ──

    @Value("${spring.ai.openai.api-key}")
    private String deepseekApiKey;

    @Value("${spring.ai.openai.base-url}")
    private String deepseekBaseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String deepseekChatModel;

    // ── SiliconFlow ──

    @Value("${siliconflow.api-key}")
    private String siliconFlowApiKey;

    @Value("${siliconflow.base-url}")
    private String siliconFlowBaseUrl;

    @Value("${siliconflow.embedding-model}")
    private String siliconFlowEmbeddingModel;

    @Bean
    @Primary
    public OpenAiApi deepseekOpenAiApi() {
        return OpenAiApi.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .build();
    }

    @Bean
    @Primary
    public OpenAiChatModel deepseekChatModel(OpenAiApi deepseekOpenAiApi) {
        return OpenAiChatModel.builder()
                .openAiApi(deepseekOpenAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(deepseekChatModel)
                        .build())
                .build();
    }

    @Bean
    @Primary
    public ChatClient deepseekChatClient(OpenAiChatModel deepseekChatModel) {
        return ChatClient.builder(deepseekChatModel).build();
    }

    // ── SiliconFlow Embedding ──

    @Bean
    public OpenAiApi siliconFlowOpenAiApi() {
        return OpenAiApi.builder()
                .baseUrl(siliconFlowBaseUrl)
                .apiKey(siliconFlowApiKey)
                .build();
    }

    @Bean
    public OpenAiEmbeddingModel siliconFlowEmbeddingModel(
            @Qualifier("siliconFlowOpenAiApi") OpenAiApi siliconFlowApi) {
        var options = OpenAiEmbeddingOptions.builder()
                .model(siliconFlowEmbeddingModel)
                .build();
        return new OpenAiEmbeddingModel(siliconFlowApi, MetadataMode.EMBED, options);
    }

    @Bean
    public EmbeddingModel embeddingModel(
            @Qualifier("siliconFlowEmbeddingModel") OpenAiEmbeddingModel siliconFlowEmbeddingModel) {
        return siliconFlowEmbeddingModel;
    }
}
