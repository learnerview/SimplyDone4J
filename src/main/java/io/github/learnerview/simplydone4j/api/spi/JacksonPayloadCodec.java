package io.github.learnerview.simplydone4j.api.spi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link PayloadCodec} implementation backed by Jackson 3 {@link ObjectMapper}.
 */
public final class JacksonPayloadCodec implements PayloadCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;

    public JacksonPayloadCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object payload) {
        if (payload == null) {
            return Collections.emptyMap();
        }
        if (payload instanceof Map<?, ?> map) {
            return new java.util.LinkedHashMap<>((Map<String, Object>) map);
        }
        Map<String, Object> converted = mapper.convertValue(payload, MAP_TYPE);
        return converted == null ? Collections.emptyMap() : new java.util.LinkedHashMap<>(converted);
    }

    @Override
    public <T> T fromMap(Map<String, Object> map, Class<T> targetType) {
        if (map == null || map.isEmpty() || targetType == null) {
            return null;
        }
        return mapper.convertValue(map, targetType);
    }

    public ObjectMapper getMapper() {
        return mapper;
    }
}
