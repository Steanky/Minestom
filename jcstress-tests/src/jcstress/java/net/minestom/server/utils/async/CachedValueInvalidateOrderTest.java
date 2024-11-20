package net.minestom.server.utils.async;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.lang.invoke.VarHandle;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

@JCStressTest
@Outcome(id = "true, true", expect = ACCEPTABLE)
@State
public class CachedValueInvalidateOrderTest {
    private final CachedValue<Integer> cachedValue = CachedValue.cachedValue(CachedValueInvalidateOrderTest::computation);

    private static int computation() {
        return 1;
    }

    @Actor
    public void actor1() {
        for (int i = 0; i < 100; i++) cachedValue.get();
        cachedValue.invalidate();
    }

    @Actor
    public void actor2() {
        for (int i = 0; i < 10; i++) cachedValue.get();
        cachedValue.invalidate();
    }

    @Arbiter
    public void arbiter(ZZ_Result r) {
        VarHandle valueAccess = CachedValue.access();
        Object value = (Object) valueAccess.get(cachedValue);

        r.r1 = value == CachedValue.INVALID;
        r.r2 = cachedValue.waiters().isEmpty();
    }
}
