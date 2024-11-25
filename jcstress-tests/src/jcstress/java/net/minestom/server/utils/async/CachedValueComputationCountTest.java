package net.minestom.server.utils.async;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

@JCStressTest
@Outcome(id = "true, 100, 1", expect = ACCEPTABLE)
@Outcome(id = "true, 101, 1", expect = ACCEPTABLE)
@State
public class CachedValueComputationCountTest {
    private final CachedValue<Integer> cachedValue = CachedValue.cachedValue(this::computation);
    private final AtomicInteger computations = new AtomicInteger();

    private int computation() {
        computations.getAndIncrement();
        return 1;
    }

    @Actor
    public void actor1() {
        for (int i = 0; i < 100; i++) {
            cachedValue.invalidate();
            int value = cachedValue.get();
            if (value != 1) throw new IllegalStateException("Unexpected value");
        }

    }

    @Actor
    public void actor2() {
        for (int i = 0; i < 100; i++) {
            int value = cachedValue.get();
            if (value != 1) throw new IllegalStateException("Unexpected value");
        }
    }

    @Arbiter
    public void arbiter(ZII_Result r) {
        r.r1 = cachedValue.waiters().isEmpty();
        r.r2 = computations.get();
        r.r3 = cachedValue.get();
    }
}
