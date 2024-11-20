package net.minestom.server.utils.async;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

@ApiStatus.Internal
@ApiStatus.Experimental
public final class CachedValue<T> implements Supplier<T> {
    @VisibleForTesting
    static final Object INVALID = new Object();

    @VisibleForTesting
    static final Object COMPUTING = new Object();

    private static final VarHandle VALUE_ACCESS;

    static {
        try {
            VALUE_ACCESS = MethodHandles.lookup().findVarHandle(CachedValue.class, "value", Object.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Supplier<? extends T> valueSupplier;
    private final Queue<Thread> waiters;

    /**
     * Accessed through {@link CachedValue#VALUE_ACCESS}.
     */
    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
    private Object value;

    private CachedValue(@NotNull Supplier<? extends T> valueSupplier) {
        this.valueSupplier = valueSupplier;
        this.waiters = new ConcurrentLinkedQueue<>();
        this.value = INVALID;
    }

    @SuppressWarnings("unchecked")
    public static <T> CachedValue<T> cachedValue(@NotNull Supplier<? extends T> supplier) {
        return supplier instanceof CachedValue<? extends T> other ? (CachedValue<T>) other : new CachedValue<>(supplier);
    }

    @VisibleForTesting
    static @NotNull VarHandle access() {
        return VALUE_ACCESS;
    }

    @VisibleForTesting
    @NotNull Queue<Thread> waiters() {
        return waiters;
    }

    private Object awaitComputation(@NotNull Thread currentThread) {
        Object witness;
        boolean interrupted = false;
        do {
            LockSupport.park(this);
            if (Thread.interrupted())
                interrupted = true;
        }
        while ((witness = (Object) VALUE_ACCESS.getAcquire(this)) == COMPUTING
                || waiters.peek() != currentThread);

        if (interrupted) currentThread.interrupt();
        return witness;
    }

    private void removeWaiter(Thread currentThread) {
        if (waiters.poll() != currentThread) throw new IllegalStateException("Removed wrong waiter thread!");
    }

    @SuppressWarnings("unchecked")
    public T get() {
        while (true) {
            final Object currentValue = (Object) VALUE_ACCESS.compareAndExchange(this, INVALID, COMPUTING);

            if (currentValue == INVALID) {
                // current thread has rights to compute the value
                // other threads will be directed to wait for this computation
                final T newValue = valueSupplier.get();

                synchronized (waiters) {
                    // we must set the newValue here, so it is made visible to waiting threads
                    if (!VALUE_ACCESS.compareAndSet(this, COMPUTING, newValue))
                        throw new IllegalStateException("Failed to update value!");

                    // unblock any threads waiting for computation
                    Thread lastWaiter = null;
                    while (!waiters.isEmpty()) {
                        Thread thisWaiter = waiters.peek();
                        if (thisWaiter != lastWaiter) {
                            LockSupport.unpark(thisWaiter);
                            lastWaiter = thisWaiter;
                        }

                        Thread.onSpinWait();
                    }

                    // at this point, we may have been invalidated by a call to invalidate()
                    // we may be in state COMPUTING if a new computation has started, or INVALID if it hasn't
                    final Object sample = (Object) VALUE_ACCESS.getAcquire(this);
                    if (sample == INVALID || sample == COMPUTING) continue;

                    return newValue;
                }
            }
            else if (currentValue == COMPUTING) {
                final Thread currentThread = Thread.currentThread();

                synchronized (waiters) {
                    final Object sample = VALUE_ACCESS.getAcquire(this);

                    // no need to wait for the computation
                    if (sample != COMPUTING && sample != INVALID) return (T) sample;
                    else if (sample == INVALID) continue; // maybe compute the value ourselves?

                    // computation is ongoing!
                    waiters.add(currentThread);
                }

                Object witness = awaitComputation(currentThread);
                removeWaiter(currentThread);

                if (witness != INVALID) return (T) witness;
            }
            else return (T) currentValue;
        }
    }

    public synchronized void invalidate() {
        final Thread currentThread = Thread.currentThread();

        synchronized (waiters) {
            Object witness = (Object) VALUE_ACCESS.getAcquire(this);

            // already invalid, do nothing
            if (witness == INVALID) return;

            // wait-free invalidation
            if (witness != COMPUTING && VALUE_ACCESS.compareAndSet(this, witness, INVALID))
                return;

            if ((Object) VALUE_ACCESS.getAcquire(this) != COMPUTING)
                throw new IllegalStateException("Unexpected value!");

            waiters.add(currentThread);
        }

        Object witness = awaitComputation(currentThread);
        if (witness == COMPUTING || witness == INVALID)
            throw new IllegalStateException("Witness value is " +
                    (witness == COMPUTING ? "COMPUTING" : "INVALID") + " when it should have been a computed value");

        if (!VALUE_ACCESS.compareAndSet(this, witness, INVALID))
            throw new IllegalStateException("Value changed when it shouldn't have");

        removeWaiter(currentThread);
    }
}
