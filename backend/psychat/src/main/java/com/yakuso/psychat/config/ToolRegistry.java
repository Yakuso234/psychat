package com.yakuso.psychat.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final List<Map<String, Object>> definitions = new ArrayList<>();
    private final Map<String, BiFunction<Long, Map<String, Object>, String>> handlers = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String name, String description, String parametersJsonSchema,
                         BiFunction<Long, Map<String, Object>, String> handler) {
        try {
            Map<String, Object> parameters = objectMapper.readValue(
                    parametersJsonSchema, new TypeReference<Map<String, Object>>() {});

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            function.put("description", description);
            function.put("parameters", parameters);

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);

            definitions.add(tool);
            handlers.put(name, handler);
            log.info("Tool registered: {}", name);
        } catch (Exception e) {
            log.error("Failed to register tool: {}", name, e);
        }
    }

    public List<Map<String, Object>> getDefinitions() {
        return definitions;
    }

    public String execute(String name, Long userId, Map<String, Object> arguments) {
        BiFunction<Long, Map<String, Object>, String> handler = handlers.get(name);
        if (handler == null) {
            return "{\"error\":\"unknown tool: " + name + "\"}";
        }
        return handler.apply(userId, arguments);
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }
}
