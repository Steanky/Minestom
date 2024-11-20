package net.minestom.server.utils.async;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

    private void unblockWaiters() {
        Thread lastWaiter = null;
        while (!waiters.isEmpty()) {
            Thread thisWaiter = waiters.peek();
            if (thisWaiter != lastWaiter) {
                LockSupport.unpark(thisWaiter);
                lastWaiter = thisWaiter;
            }

            Thread.onSpinWait();
        }
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
                    boolean interruptBySet = !VALUE_ACCESS.compareAndSet(this, COMPUTING, newValue);

                    if (!interruptBySet)
                        unblockWaiters();

                    // at this point, we may have been invalidated by a call to invalidate()
                    // we may be in state COMPUTING if a new computation has started, or INVALID if it hasn't
                    final Object sample = (Object) VALUE_ACCESS.getAcquire(this);
                    if (sample == INVALID || sample == COMPUTING) continue;

                    // if another thread set during our compute, use its value
                    return interruptBySet ? (T) sample : newValue;
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
                waiters.poll();

                if (witness != INVALID) return (T) witness;
            }
            else return (T) currentValue;
        }
    }

    public void set(@Nullable T value) {
        synchronized (waiters) {
            final Object oldValue = VALUE_ACCESS.getAndSet(this, value);

            // if we set during a computation, unblock the waiters
            if (oldValue == COMPUTING)
                unblockWaiters();
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

            waiters.add(currentThread);
        }

        awaitComputation(currentThread);
        VALUE_ACCESS.setRelease(this, INVALID);
        waiters.poll();
    }
}
