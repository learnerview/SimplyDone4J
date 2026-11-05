package io.github.learnerview.simplydone4j.demo;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    private static final String STOCK_KEY = "parcelflow:inventory:stock";
    private static final String RELEASE_KEY_PREFIX = "parcelflow:inventory:released:";
    private static final int RELEASE_MARKER_TTL_SECONDS = 7 * 24 * 60 * 60;

    private static final Map<String, Integer> INITIAL_STOCK = Map.of(
            "cloud-runner", 18,
            "desk-lamp", 12,
            "travel-mug", 30,
            "weekend-pack", 8,
            "studio-headphones", 9,
            "trail-bottle", 4);

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local stock = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
            local quantity = tonumber(ARGV[2])
            if stock < quantity then
              return 0
            end
            redis.call('HINCRBY', KEYS[1], ARGV[1], -quantity)
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            redis.call('SET', KEYS[1], '1')
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('HINCRBY', KEYS[2], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public InventoryService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void seedStock() {
        INITIAL_STOCK.forEach((sku, quantity) ->
                redis.opsForHash().putIfAbsent(STOCK_KEY, sku, String.valueOf(quantity)));
    }

    public boolean reserve(String sku, int quantity) {
        Long result = redis.execute(RESERVE_SCRIPT, List.of(STOCK_KEY),
                sku, String.valueOf(quantity));
        return result != null && result == 1L;
    }

    public boolean releaseOnce(String orderId, String sku, int quantity) {
        String releaseKey = RELEASE_KEY_PREFIX + orderId + ":" + sku;
        Long result = redis.execute(RELEASE_SCRIPT, List.of(releaseKey, STOCK_KEY),
                sku, String.valueOf(quantity), String.valueOf(RELEASE_MARKER_TTL_SECONDS));
        return result != null && result == 1L;
    }

    public int stock(String sku) {
        Object value = redis.opsForHash().get(STOCK_KEY, sku);
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

    public Map<String, Integer> stocks() {
        Map<String, Integer> stocks = new LinkedHashMap<>();
        redis.opsForHash().entries(STOCK_KEY)
                .forEach((sku, quantity) -> stocks.put(sku.toString(), Integer.parseInt(quantity.toString())));
        return stocks;
    }

    public int restock(String sku, int quantity) {
        redis.opsForHash().increment(STOCK_KEY, sku, quantity);
        return stock(sku);
    }
}
