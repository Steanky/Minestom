package net.minestom.server.utils.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachedValueTest {
    private static void assertSignalZero(CachedValue<?> value) {
        assertEquals(0, (int) CachedValue.signalAccess().getVolatile(value), "non-zero signal");
    }

    @Test
    void nullValueTest() {
        CachedValue<Object> cachedValue = CachedValue.cachedValue(() -> null);

        assertNull(cachedValue.get());
        assertNull(cachedValue.get());
        assertSignalZero(cachedValue);
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
        assertSignalZero(cachedValue);
    }

    @Test
    void invalidationAwaitTest() {
        AtomicInteger invokeCount = new AtomicInteger();
        CachedValue<Integer> cachedValue = CachedValue.cachedValue(() -> {
            if (invokeCount.getAndIncrement() > 1) {
                fail("Cache was called more than twice");
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                fail("Thread interrupted while sleeping");
            }

            return 1;
        });

        assertEquals(1, cachedValue.get());

        Thread firstInvalidate = new Thread(cachedValue::invalidate);
        Thread secondInvalidate = new Thread(cachedValue::invalidate);
        Thread thirdInvalidate = new Thread(cachedValue::invalidate);

        firstInvalidate.start();
        secondInvalidate.start();
        thirdInvalidate.start();

        try {
            firstInvalidate.join();
            secondInvalidate.join();
            thirdInvalidate.join();
        } catch (InterruptedException e) {
            fail("Thread interrupted while joining");
        }

        assertSignalZero(cachedValue);
        assertEquals(1, cachedValue.get());
        assertSignalZero(cachedValue);
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
        assertSignalZero(cachedValue);
    }

    @Test
    void setOverwrites() {
        CachedValue<Integer> cachedValue = CachedValue.cachedValue(() -> 1);

        assertEquals(1, cachedValue.get());

        cachedValue.set(0);
        assertEquals(0, cachedValue.get());
        assertEquals(0, cachedValue.get());

        cachedValue.invalidate();

        assertEquals(1, cachedValue.get());
        assertSignalZero(cachedValue);
    }
}