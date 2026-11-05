package io.github.learnerview.simplydone4j.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

@Component
public class OrderStore {

    private static final Logger log = LoggerFactory.getLogger(OrderStore.class);
    private static final String ORDER_KEY_PREFIX = "parcelflow:order:";
    private static final String ORDER_INDEX = "parcelflow:orders";
    private static final String JOB_INDEX = "parcelflow:order-jobs";
    private static final int MAX_RECENT_ORDERS = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Object[] locks = new Object[64];

    public OrderStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public void save(OrderRecord order) {
        String json;
        try {
            json = objectMapper.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize order " + order.orderId(), e);
        }
        redis.opsForValue().set(orderKey(order.orderId()), json);
        redis.opsForZSet().add(ORDER_INDEX, order.orderId(), order.createdAt().toEpochMilli());
        if (order.jobId() != null) {
            redis.opsForHash().put(JOB_INDEX, order.jobId(), order.orderId());
        }
    }

    public OrderRecord update(String orderId, UnaryOperator<OrderRecord> mutation) {
        synchronized (lockFor(orderId)) {
            OrderRecord current = find(orderId);
            if (current == null) {
                return null;
            }
            OrderRecord updated = mutation.apply(current);
            if (updated != null) {
                save(updated);
            }
            return updated;
        }
    }

    public OrderRecord find(String orderId) {
        return read(redis.opsForValue().get(orderKey(orderId)));
    }

    public OrderRecord findByJobId(String jobId) {
        Object orderId = redis.opsForHash().get(JOB_INDEX, jobId);
        return orderId == null ? null : find(orderId.toString());
    }

    public List<OrderRecord> recent() {
        return load(redis.opsForZSet().reverseRange(ORDER_INDEX, 0, MAX_RECENT_ORDERS - 1L));
    }

    public List<OrderRecord> all() {
        return load(redis.opsForZSet().range(ORDER_INDEX, 0, -1));
    }

    public long count() {
        Long count = redis.opsForZSet().zCard(ORDER_INDEX);
        return count == null ? 0 : count;
    }

    public void delete(String orderId) {
        OrderRecord order = find(orderId);
        redis.delete(orderKey(orderId));
        redis.opsForZSet().remove(ORDER_INDEX, orderId);
        if (order != null && order.jobId() != null) {
            redis.opsForHash().delete(JOB_INDEX, order.jobId());
        }
    }

    private List<OrderRecord> load(Set<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(orderIds);
        List<String> keys = ids.stream().map(this::orderKey).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return List.of();
        }
        List<OrderRecord> orders = new ArrayList<>(ids.size());
        for (String value : values) {
            OrderRecord order = read(value);
            if (order != null) {
                orders.add(order);
            }
        }
        return orders;
    }

    private OrderRecord read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OrderRecord.class);
        } catch (JsonProcessingException e) {
            log.warn("Skipping an unreadable order record", e);
            return null;
        }
    }

    private String orderKey(String orderId) {
        return ORDER_KEY_PREFIX + orderId;
    }

    private Object lockFor(String orderId) {
        return locks[Math.floorMod(orderId.hashCode(), locks.length)];
    }
}
