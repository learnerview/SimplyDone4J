package io.github.learnerview.simplydone4j.util;

import java.util.Objects;

/**
 * Encodes caller-supplied key components that are joined with a colon.
 *
 * <p>Without escaping, a producer or idempotency key containing a colon can collide with
 * another pair. Values that do not contain {@code %} or {@code :} are left untouched so
 * existing simple keys keep their historical format.</p>
 */
public final class RedisKeyCodec {

    private RedisKeyCodec() {
    }

    public static String encode(String value) {
        Objects.requireNonNull(value, "value");
        if (value.indexOf('%') < 0 && value.indexOf(':') < 0) {
            return value;
        }
        StringBuilder encoded = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                encoded.append("%25");
            } else if (c == ':') {
                encoded.append("%3A");
            } else {
                encoded.append(c);
            }
        }
        return encoded.toString();
    }

    public static String idempotencyKey(String prefix, String producer, String idempotencyKey) {
        return prefix + encode(producer) + ':' + encode(idempotencyKey);
    }

}
