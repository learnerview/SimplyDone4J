package io.github.learnerview.simplydone4j.api.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonPayloadCodecTest {

    private JacksonPayloadCodec codec;

    record UserCreatedEvent(String userId, String email, int age) {}

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = JsonMapper.builder().build();
        codec = new JacksonPayloadCodec(mapper);
    }

    @Test
    void shouldConvertObjectToMapAndBack() {
        UserCreatedEvent original = new UserCreatedEvent("usr-123", "alice@example.com", 30);

        Map<String, Object> map = codec.toMap(original);
        assertNotNull(map);
        assertEquals("usr-123", map.get("userId"));
        assertEquals("alice@example.com", map.get("email"));
        assertEquals(30, map.get("age"));

        UserCreatedEvent restored = codec.fromMap(map, UserCreatedEvent.class);
        assertNotNull(restored);
        assertEquals(original.userId(), restored.userId());
        assertEquals(original.email(), restored.email());
        assertEquals(original.age(), restored.age());
    }

    @Test
    void shouldHandleMapDirectlyInToMap() {
        Map<String, Object> input = Map.of("key", "val", "num", 42);
        Map<String, Object> result = codec.toMap(input);
        assertEquals(input, result);
    }

    @Test
    void shouldHandleNullAndEmpty() {
        assertTrue(codec.toMap(null).isEmpty());
        assertNull(codec.fromMap(null, UserCreatedEvent.class));
        assertNull(codec.fromMap(Map.of(), UserCreatedEvent.class));
        assertNull(codec.fromMap(Map.of("userId", "1"), null));
    }
}
