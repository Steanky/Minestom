package net.minestom.server.thread;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * An object which may be acquired.
 * @param <T> the type of object which may be acquired
 * @see Acquirable
 */
public interface AcquirableSource<T> {
    /**
     * Returns the acquirable for this object. To safely perform operations on the object, the user must call
     * {@link Acquirable#sync(Consumer)} or {@link Acquirable#lock()} (followed by a subsequent unlock) on the
     * acquirable instance.
     *
     * @return the acquirable for this object
     */
    @NotNull Acquirable<? extends T> getAcquirable();
}
