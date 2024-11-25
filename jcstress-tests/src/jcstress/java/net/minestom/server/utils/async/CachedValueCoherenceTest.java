package net.minestom.server.utils.async;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.lang.invoke.VarHandle;

import static org.openjdk.jcstress.annotations.Expect.*;

@JCStressTest
@Outcome(id = "0, true, true", expect = ACCEPTABLE)
@Outcome(id = "1, true, true", expect = ACCEPTABLE)
@State
public class CachedValueCoherenceTest {
    private final CachedValue<Integer> cachedValue = CachedValue.cachedValue(CachedValueCoherenceTest::computation);

    private static int computation() {
        return 1;
    }

    @Actor
    public void actor1() {
        cachedValue.get();
    }

    @Actor
    public void actor2() {
        for (int i = 0; i < 10; i++) cachedValue.invalidate();
    }

    @Arbiter
    public void arbiter(IZZ_Result r) {
        VarHandle valueAccess = CachedValue.valueAccess();
        Object value = (Object) valueAccess.getVolatile(cachedValue);

        if (value == CachedValue.INVALID) {
            r.r1 = 0;
            r.r2 = true;
            r.r3 = cachedValue.waiters().isEmpty();
        }
        else if (value == CachedValue.COMPUTING) {
            r.r1 = 2;
            r.r2 = true;
            r.r3 = cachedValue.waiters().isEmpty();
        }
        else {
            r.r1 = 1;
            r.r2 = value.equals(1);
            r.r3 = cachedValue.waiters().isEmpty();
        }
    }
}
