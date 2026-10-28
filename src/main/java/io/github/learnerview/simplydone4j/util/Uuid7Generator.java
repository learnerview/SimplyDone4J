package io.github.learnerview.simplydone4j.util;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A monotonic UUIDv7 generator.
 *
 * <p>A UUIDv7 is a timestamp followed by random bits: the first 48 bits are the Unix
 * time in milliseconds, the rest is randomness. That makes the identifiers sortable by
 * creation time, which a random UUIDv4 is not.</p>
 *
 * <p>Two things are wrong with a random UUID as a job id. First, the ids carry no
 * information: the database has to sort them to find the oldest job, and an index on the
 * id is an index on noise. Second, a v4 has no time component at all, so an id cannot be
 * traced back to the moment the job was submitted -- which is exactly the moment an
 * operator asks about when a job is stuck.</p>
 *
 * <p>The generator is monotonic: within the same millisecond the counter is incremented
 * instead of redrawn, so two ids created in the same millisecond still sort in creation
 * order. Without that, two jobs submitted in the same millisecond would sort arbitrarily
 * against each other, and the ordering guarantee would hold only on average. The clock
 * is read once per millisecond and the counter is carried across milliseconds, so the ids
 * are strictly increasing.</p>
 *
 * <p>The layout is the RFC 9562 version 7 layout, so the ids are valid UUIDs that any
 * UUID parser accepts, and they sort in creation order under the standard UUID
 * comparison rules, which compare the bytes rather than the string form.</p>
 */
public final class Uuid7Generator {

    /** 48 bits of milliseconds, 4 bits of version, 12 bits of counter. */
    private static final int COUNTER_BITS = 12;
    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;

    /** The last timestamp and the counter that goes with it. */
    private record State(long timestamp, int counter) { }

    private final AtomicReference<State> state = new AtomicReference<>(new State(0, 0));

    /**
     * The next identifier.
     */
    public String next() {
        long now = System.currentTimeMillis();
        int counter;

        while (true) {
            State current = state.get();

            if (now > current.timestamp()) {
                counter = 0;
                if (state.compareAndSet(current, new State(now, counter))) {
                    break;
                }
                continue;
            }

            // The same millisecond, or the clock went backwards: carry the counter so the
            // id never moves back in time.
            counter = current.counter() + 1;
            if (counter > COUNTER_MAX) {
                // The counter is full. Move to the next millisecond rather than wrapping
                // around and handing out an id that sorts before one already issued.
                counter = 0;
                if (state.compareAndSet(current, new State(current.timestamp() + 1, counter))) {
                    now = current.timestamp() + 1;
                    break;
                }
                continue;
            }
            if (state.compareAndSet(current, new State(current.timestamp(), counter))) {
                break;
            }
        }

        return encode(now, counter);
    }

    /**
     * The RFC 9562 version 7 layout: 48 bits of milliseconds, 4 bits of version, 12 bits
     * of counter, 2 bits of variant, 62 bits of randomness.
     */
    private static String encode(long timestampMillis, int counter) {
        long timestamp = timestampMillis & 0xFFFF_FFFF_FFFFL;

        // The 12 bits after the version are the counter, so two ids created in the same
        // millisecond differ in the bits that decide their order.
        long randA = counter & COUNTER_MAX;
        long randB = ThreadLocalRandom.current().nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;

        long msb = (timestamp << 16) | 0x7000L | randA;
        long lsb = 0x8000_0000_0000_0000L | randB;

        return format(msb, lsb);
    }

    private static String format(long msb, long lsb) {
        return new StringBuilder(36)
                .append(hex(msb >>> 32, 8))
                .append('-')
                .append(hex(msb >>> 16, 4))
                .append('-')
                .append(hex(msb, 4))
                .append('-')
                .append(hex(lsb >>> 48, 4))
                .append('-')
                .append(hex(lsb, 12))
                .toString();
    }

    private static String hex(long value, int length) {
        // The width is a minimum for String.format, so the value is masked down to the
        // number of digits the field is allowed to occupy.
        long mask = length >= 16 ? -1L : (1L << (length * 4)) - 1;
        return String.format("%0" + length + "x", value & mask);
    }
}
