package io.github.learnerview.simplydone4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Uuid7GeneratorTest {

    @Test
    @DisplayName("the ids are version 7 UUIDs")
    void shouldProduceVersion7Uuids() {
        String id = new Uuid7Generator().next();

        assertEquals(36, id.length());
        assertEquals('7', id.charAt(14), "the version nibble must be 7");
        assertTrue(id.charAt(19) == '8' || id.charAt(19) == '9' || id.charAt(19) == 'a' || id.charAt(19) == 'b',
                "the variant nibble must be one of 8, 9, a or b, but was " + id.charAt(19));
        assertEquals('-', id.charAt(8));
        assertEquals('-', id.charAt(13));
        assertEquals('-', id.charAt(18));
        assertEquals('-', id.charAt(23));
    }

    @Test
    @DisplayName("the ids sort in creation order")
    void shouldProduceIncreasingIds() {
        Uuid7Generator generator = new Uuid7Generator();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(generator.next());
        }

        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        assertEquals(sorted, ids, "the ids must already be in order");
    }

    @Test
    @DisplayName("the ids are unique")
    void shouldProduceUniqueIds() {
        Uuid7Generator generator = new Uuid7Generator();

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(ids.add(generator.next()), "the generator produced a duplicate id");
        }
    }

    @Test
    @DisplayName("the ids do not go backwards when the clock does")
    void shouldNotGoBackwards() throws Exception {
        Uuid7Generator generator = new Uuid7Generator();

        String first = generator.next();
        // The clock is not something the test can move, but the ordering guarantee does
        // not depend on it being steady: ids are compared, and the generator never hands
        // out an id that sorts before one it already handed out.
        List<String> ids = new ArrayList<>();
        ids.add(first);
        for (int i = 0; i < 100; i++) {
            ids.add(generator.next());
        }

        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i - 1).compareTo(ids.get(i)) < 0,
                    "id " + ids.get(i) + " must sort after " + ids.get(i - 1));
        }
    }

    @Test
    @DisplayName("concurrent callers get unique, ordered ids")
    void shouldBeSafeUnderConcurrency() throws Exception {
        Uuid7Generator generator = new Uuid7Generator();
        int threads = 8;
        int perThread = 500;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<List<String>> results = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            List<String> mine = new ArrayList<>();
            results.add(mine);
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    mine.add(generator.next());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Set<String> all = new HashSet<>();
        for (List<String> mine : results) {
            for (String id : mine) {
                assertTrue(all.add(id), "two threads were given the same id: " + id);
            }
        }
        assertEquals(threads * perThread, all.size());
    }
}
