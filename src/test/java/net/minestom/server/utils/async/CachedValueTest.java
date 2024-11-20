package net.minestom.server.utils.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachedValueTest {
    @Test
    void nullValueTest() {
        CachedValue<Object> cachedValue = CachedValue.cachedValue(() -> null);

        assertNull(cachedValue.get());
        assertNull(cachedValue.get());
    }

    @Test
    void invalidationTest() {
        AtomicInteger count = new AtomicInteger();

        CachedValue<Integer> cachedValue = CachedValue.cachedValue(count::getAndIncrement);

        assertEquals(0, cachedValue.get());
        assertEquals(0, cachedValue.get());

        cachedValue.invalidate();

        assertEquals(1, cachedValue.get());
        assertEquals(1, cachedValue.get());

        cachedValue.invalidate();

        assertEquals(2, cachedValue.get());
        assertEquals(2, cachedValue.get());
    }

    @Test
    void awaitComputationTest() {
        AtomicInteger invokeCount = new AtomicInteger();
        CachedValue<Integer> cachedValue = CachedValue.cachedValue(() -> {
            if (invokeCount.getAndIncrement() > 0) {
                fail("Cache was called more than once");
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                fail("Thread interrupted while sleeping");
            }

            return 1;
        });

        Thread firstRead = new Thread(() -> assertEquals(1, cachedValue.get()));
        Thread secondRead = new Thread(() -> assertEquals(1, cachedValue.get()));
        Thread thirdRead = new Thread(() -> assertEquals(1, cachedValue.get()));

        firstRead.start();
        secondRead.start();
        thirdRead.start();

        try {
            firstRead.join();
            secondRead.join();
            thirdRead.join();
        } catch (InterruptedException e) {
            fail("Thread interrupted while joining");
        }

        assertEquals(1, cachedValue.get());
    }
}