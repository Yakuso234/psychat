package com.yakuso.psychat.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.Arrays;
import java.util.List;

@Configuration
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("${milvus.collection-name}")
    private String memoryCollectionName;

    @Value("${milvus.knowledge-collection-name}")
    private String knowledgeCollectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    @Bean
    public MilvusServiceClient milvusClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .build()
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initCollections() {
        MilvusServiceClient client = milvusClient();

        // wait for Milvus
        for (int i = 0; i < 5; i++) {
            try {
                client.hasCollection(HasCollectionParam.newBuilder()
                        .withCollectionName(memoryCollectionName).build());
                break;
            } catch (Exception e) {
                log.warn("Milvus not ready (attempt {}/5): {}", i + 1, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }

        ensureCollection(client, memoryCollectionName);
        ensureKnowledgeCollection(client, knowledgeCollectionName);
    }

    private void ensureCollection(MilvusServiceClient client, String name) {
        try {
            R<Boolean> has = client.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(name).build());
            if (has.getData() != null && has.getData()) {
                // check if schema needs migration (partition key added in Phase 5)
                var desc = client.describeCollection(
                        io.milvus.param.collection.DescribeCollectionParam.newBuilder()
                                .withCollectionName(name).build());
                boolean hasUserIdField = false;
                boolean hasPartitionKey = false;
                boolean hasLinkedIds = false;
                if (desc.getData() != null) {
                    for (var f : desc.getData().getSchema().getFieldsList()) {
                        if ("user_id".equals(f.getName())) {
                            hasUserIdField = true;
                            if (f.getIsPartitionKey()) hasPartitionKey = true;
                        }
                        if ("linked_memory_ids".equals(f.getName())) hasLinkedIds = true;
                    }
                }
                if (hasUserIdField && (!hasPartitionKey || !hasLinkedIds)) {
                    log.info("Migrating {} (partitionKey={}, linkedIds={})...", name, hasPartitionKey, hasLinkedIds);
                    client.dropCollection(
                            io.milvus.param.collection.DropCollectionParam.newBuilder()
                                    .withCollectionName(name).build());
                    // fall through to recreate
                } else {
                    log.info("Milvus collection already exists: {}", name);
                    client.loadCollection(
                            LoadCollectionParam.newBuilder().withCollectionName(name).build());
                    log.info("Milvus collection loaded: {}", name);
                    return;
                }
            }

            log.info("Creating Milvus collection: {}", name);

            List<FieldType> fields = Arrays.asList(
                    FieldType.newBuilder().withName("id").withDataType(DataType.Int64)
                            .withPrimaryKey(true).withAutoID(true).build(),
                    FieldType.newBuilder().withName("user_id").withDataType(DataType.Int64)
                            .withPartitionKey(true).build(),
                    FieldType.newBuilder().withName("content").withDataType(DataType.VarChar)
                            .withMaxLength(65535).build(),
                    FieldType.newBuilder().withName("embedding").withDataType(DataType.FloatVector)
                            .withDimension(dimension).build(),
                    FieldType.newBuilder().withName("created_at").withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName("linked_memory_ids").withDataType(DataType.VarChar)
                            .withMaxLength(512).build()
            );

            client.createCollection(
                    CreateCollectionParam.newBuilder()
                            .withCollectionName(name)
                            .withFieldTypes(fields)
                            .build()
            );

            client.createIndex(
                    CreateIndexParam.newBuilder()
                            .withCollectionName(name)
                            .withFieldName("embedding")
                            .withIndexType(IndexType.AUTOINDEX)
                            .withMetricType(MetricType.COSINE)
                            .build()
            );

            client.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(name)
                            .build()
            );

            log.info("Milvus collection created and loaded: {}", name);
        } catch (Exception e) {
            log.error("Failed to init Milvus collection {}: {}", name, e.getMessage());
        }
    }

    private void ensureKnowledgeCollection(MilvusServiceClient client, String name) {
        try {
            R<Boolean> has = client.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(name).build());
            if (has.getData() != null && has.getData()) {
                log.info("Milvus knowledge collection already exists: {}", name);
                return;
            }

            log.info("Creating Milvus knowledge collection: {}", name);

            List<FieldType> fields = Arrays.asList(
                    FieldType.newBuilder().withName("id").withDataType(DataType.Int64)
                            .withPrimaryKey(true).withAutoID(true).build(),
                    FieldType.newBuilder().withName("category").withDataType(DataType.VarChar)
                            .withMaxLength(32).build(),
                    FieldType.newBuilder().withName("emotion_tags").withDataType(DataType.VarChar)
                            .withMaxLength(512).build(),
                    FieldType.newBuilder().withName("title").withDataType(DataType.VarChar)
                            .withMaxLength(256).build(),
                    FieldType.newBuilder().withName("content").withDataType(DataType.VarChar)
                            .withMaxLength(65535).build(),
                    FieldType.newBuilder().withName("embedding").withDataType(DataType.FloatVector)
                            .withDimension(dimension).build(),
                    FieldType.newBuilder().withName("priority").withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName("usage_count").withDataType(DataType.Int64).build()
            );

            client.createCollection(
                    CreateCollectionParam.newBuilder()
                            .withCollectionName(name)
                            .withFieldTypes(fields)
                            .build()
            );

            client.createIndex(
                    CreateIndexParam.newBuilder()
                            .withCollectionName(name)
                            .withFieldName("embedding")
                            .withIndexType(IndexType.AUTOINDEX)
                            .withMetricType(MetricType.COSINE)
                            .build()
            );

            client.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(name)
                            .build()
            );

            log.info("Milvus knowledge collection created and loaded: {}", name);
        } catch (Exception e) {
            log.error("Failed to init Milvus knowledge collection {}: {}", name, e.getMessage());
        }
    }
}
