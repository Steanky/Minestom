package net.minestom.server.utils.async;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

@ApiStatus.Internal
@ApiStatus.Experimental
public final class CachedValue<T> implements Supplier<T> {
    @VisibleForTesting
    static final Object INVALID = new Object();

    @VisibleForTesting
    static final Object COMPUTING = new Object();

    private static final int INVALIDATE_MASK = 0x8000_0000;
    private static final int COMPUTE_MASK = 0x1FFF_FFFF;

    private static final int STATUS_MASK = 0x6000_0000;
    private static final int UNBLOCK_COMPUTE = 0x2000_0000;
    private static final int UNBLOCK_INVALIDATE = 0x4000_0000;

    private static final VarHandle SIGNAL_ACCESS;
    private static final VarHandle VALUE_ACCESS;

    static {
        try {
            SIGNAL_ACCESS = MethodHandles.lookup().findVarHandle(CachedValue.class, "signal", int.class);
            VALUE_ACCESS = MethodHandles.lookup().findVarHandle(CachedValue.class, "value", Object.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    record Waiter(Thread thread, boolean invalidate) {}

    private final Supplier<? extends T> valueSupplier;
    private final Deque<Waiter> waiters;

    /**
     * Accessed only through {@link CachedValue#SIGNAL_ACCESS}.
     */
    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private int signal;

    /**
     * Accessed only through {@link CachedValue#VALUE_ACCESS} and the constructor.
     */
    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
    private Object value;

    private CachedValue(@NotNull Supplier<? extends T> valueSupplier) {
        this.valueSupplier = valueSupplier;
        this.waiters = new ConcurrentLinkedDeque<>();
        this.value = INVALID;
    }

    @SuppressWarnings("unchecked")
    public static <T> CachedValue<T> cachedValue(@NotNull Supplier<? extends T> supplier) {
        return supplier instanceof CachedValue<? extends T> other ? (CachedValue<T>) other : new CachedValue<>(supplier);
    }

    @VisibleForTesting
    static @NotNull VarHandle valueAccess() {
        return VALUE_ACCESS;
    }

    @VisibleForTesting
    static @NotNull VarHandle signalAccess() { return SIGNAL_ACCESS; }

    @VisibleForTesting
    @NotNull Queue<Waiter> waiters() {
        return waiters;
    }

    private Object awaitComputation(@NotNull Thread currentThread, int waitToken) {
        Object witness;
        boolean interrupted = false;
        do {
            LockSupport.park(this);
            if (Thread.interrupted())
                interrupted = true;
        }
        while ((witness = (Object) VALUE_ACCESS.getAcquire(this)) == COMPUTING
                || (((int)SIGNAL_ACCESS.getAcquire(this)) & STATUS_MASK) != waitToken);

        if (interrupted) currentThread.interrupt();
        return witness;
    }

    /**
     * Spins until the bit indicated by {@code mask} is non-zero. That's what the point of the mask is.
     * <p>
     * Spinning is generally not ideal, but the amount of time spent waiting is negligible, as the threads being waited
     * upon only have to advance far enough to read the value from memory.
     *
     * @param mask the bitmask
     */
    private void spinForMask(int mask) {
        while ((((int) SIGNAL_ACCESS.getAcquire(this)) & mask) != 0)
            Thread.onSpinWait();
    }

    /**
     * Unblocks any threads waiting on computation to finish.
     * <p>
     * Threads waiting to retrieve the result of a computation, but not invalidate it, are unblocked first. Once these
     * threads have successfully retrieved the value, the invalidation thread (if any) is allowed to run.
     */
    private void unblockWaiters() {
        SIGNAL_ACCESS.getAndBitwiseOr(this, UNBLOCK_COMPUTE);

        boolean foundInvalidate = false;
        for (Waiter waiter : waiters) {
            if (!foundInvalidate && waiter.invalidate) {
                foundInvalidate = true;

                // wait for all computes to finish before unblocking any invalidate
                // computes are finished when the lowest 29 bits of signal are 0
                spinForMask(COMPUTE_MASK);

                // unblock the invalidate thread
                // XOR to change ...01... to ...10...
                SIGNAL_ACCESS.getAndBitwiseXor(this, STATUS_MASK);
            }

            LockSupport.unpark(waiter.thread);
        }

        if (foundInvalidate) spinForMask(INVALIDATE_MASK);
        else spinForMask(COMPUTE_MASK);

        SIGNAL_ACCESS.getAndBitwiseAnd(this, ~STATUS_MASK);
        waiters.clear();
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
                    final Object setObject = VALUE_ACCESS.compareAndExchange(this, COMPUTING, newValue);
                    final boolean interruptBySet = setObject != COMPUTING;

                    if (!interruptBySet)
                        unblockWaiters();

                    // if another thread set during our compute, use its value
                    return interruptBySet ? (T) setObject : newValue;
                }
            }
            else if (currentValue == COMPUTING) {
                final Thread currentThread = Thread.currentThread();

                synchronized (waiters) {
                    final Object sample = (Object) VALUE_ACCESS.getAcquire(this);

                    // invalidated, continue
                    if (sample == INVALID) continue;

                    // no need to wait for the computation
                    if (sample != COMPUTING) return (T) sample;

                    final int oldSignal = (int) SIGNAL_ACCESS.getAndAdd(this, 1);

                    // check if the next add would overflow the compute mask
                    // if so, undo our modification and throw an error
                    if (((oldSignal + 2) & COMPUTE_MASK) == 0) {
                        SIGNAL_ACCESS.getAndAdd(this, -1);
                        throw new IllegalStateException("Compute thread overflow");
                    }

                    // computation is ongoing!
                    waiters.addFirst(new Waiter(currentThread, false));
                }

                final Object witness = awaitComputation(currentThread, UNBLOCK_COMPUTE);
                SIGNAL_ACCESS.getAndAdd(this, -1);
                return (T) witness;
            }
            else return (T) currentValue;
        }
    }

    /**
     * Forcibly sets the cached value. If this is done mid-computation, any calls to {@link CachedValue#get()} waiting
     * on the result will ultimately return {@code value}.
     *
     * @param value the value to set
     */
    public void set(@Nullable T value) {
        synchronized (waiters) {
            final Object oldValue = (Object) VALUE_ACCESS.getAndSet(this, value);

            // if we set during a computation, unblock the waiters
            if (oldValue == COMPUTING)
                unblockWaiters();
        }
    }

    /**
     * Atomically sets the cached value if it is not already computed, or presently being computed, and returns whether
     * the operation succeeded.
     * <p>
     * This method will generally <i>not</i> cause the result of an in-progress computation to change.
     *
     * @param value the value to set
     * @return true if the cached value was change as a result of this operation, false otherwise
     * @implNote Unlike {@link CachedValue#set(Object)}, this method does not need to perform internal synchronization,
     * and so can in some cases be more efficient.
     */
    public boolean setIfInvalid(@Nullable T value) {
        // synchronization is not necessary
        // we only need to sync if we go from COMPUTING to INVALID or another value
        return VALUE_ACCESS.compareAndSet(this, INVALID, value);
    }

    /**
     * Invalidates the cached value. A subsequent call to {@link CachedValue#get()} will cause a re-computation. If a
     * computation is already ongoing, any threads waiting on the result will receive that value when it finishes.
     * However, the result will not be cached and a future call to {@code get} will cause another computation.
     * <p>
     *
     *
     * @return true if this method call invalidated a computation, false otherwise
     */
    public boolean invalidate() {
        final Thread currentThread = Thread.currentThread();

        synchronized (waiters) {
            final Object witness = (Object) VALUE_ACCESS.getAcquire(this);

            // already invalid, do nothing
            if (witness == INVALID)
                return false;

            // wait-free invalidation
            if (witness != COMPUTING && VALUE_ACCESS.compareAndSet(this, witness, INVALID))
                return true;

            // if there's already an invalidate operation ongoing, return
            if (((int) SIGNAL_ACCESS.getAndBitwiseOr(this, INVALIDATE_MASK) & INVALIDATE_MASK) != 0)
                return false;

            waiters.addLast(new Waiter(currentThread, true));
        }

        awaitComputation(currentThread, UNBLOCK_INVALIDATE);
        VALUE_ACCESS.setRelease(this, INVALID);
        SIGNAL_ACCESS.getAndBitwiseAnd(this, ~INVALIDATE_MASK);
        return true;
    }
}
