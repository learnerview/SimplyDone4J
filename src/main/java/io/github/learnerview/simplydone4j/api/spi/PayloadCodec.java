package io.github.learnerview.simplydone4j.api.spi;

import java.util.Map;

/**
 * SPI for converting job payloads to and from {@code Map<String, Object>} representation.
 *
 * <p>Allows applications to supply their own serialization strategy or configure
 * custom mappers, modules, and naming strategies without affecting the library's
 * internal storage format.</p>
 */
public interface PayloadCodec {

    /**
     * Converts a typed payload object into a map representation for job storage.
     *
     * @param payload the source payload object, may be null
     * @return map representation, or null if payload is null
     */
    Map<String, Object> toMap(Object payload);

    /**
     * Deserializes a map representation into a typed target object.
     *
     * @param map the map representation of the payload
     * @param targetType the target class to deserialize into
     * @param <T> the target type
     * @return deserialized instance of {@code T}, or null if map is null/empty
     */
    <T> T fromMap(Map<String, Object> map, Class<T> targetType);
}
