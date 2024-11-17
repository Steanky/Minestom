package net.minestom.server.tag;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.function.Consumer;

sealed interface StaticIntMap<T> permits StaticIntMap.Array, StaticIntMap.Hash {

    T get(@Range(from = 0, to = Integer.MAX_VALUE) int key);

    void forValues(@NotNull Consumer<T> consumer);

    @NotNull StaticIntMap<T> copy();

    // Methods potentially causing re-hashing

    void put(@Range(from = 0, to = Integer.MAX_VALUE) int key, T value);

    void remove(@Range(from = 0, to = Integer.MAX_VALUE) int key);

    void updateContent(@NotNull StaticIntMap<T> content);

    final class Array<T> implements StaticIntMap<T> {
        private static final Object[] EMPTY_ARRAY = new Object[0];

        private T[] array;

        public Array(T[] array) {
            this.array = array;
        }

        public Array() {
            //noinspection unchecked
            this.array = (T[]) EMPTY_ARRAY;
        }

        @Override
        public T get(int key) {
            final T[] array = this.array;
            return key < array.length ? array[key] : null;
        }

        @Override
        public void forValues(@NotNull Consumer<T> consumer) {
            final T[] array = this.array;
            for (T value : array) {
                if (value != null) consumer.accept(value);
            }
        }

        @Override
        public @NotNull StaticIntMap<T> copy() {
            return new Array<>(array.clone());
        }

        @Override
        public void put(int key, T value) {
            T[] array = this.array;
            if (key >= array.length) {
                array = updateArray(Arrays.copyOf(array, key * 2 + 1));
            }
            array[key] = value;
        }

        @Override
        public void updateContent(@NotNull StaticIntMap<T> content) {
            if (content instanceof StaticIntMap.Array<T> arrayMap) {
                updateArray(arrayMap.array.clone());
            } else {
                throw new IllegalArgumentException("Invalid content type: " + content.getClass());
            }
        }

        @Override
        public void remove(int key) {
            T[] array = this.array;
            if (key < array.length) array[key] = null;
        }

        T[] updateArray(T[] result) {
            this.array = result;
            return result;
        }
    }

    /**
     * A {@link StaticIntMap} implementation based off of an open-addressed quadratic probing hashtable.
     * <p>
     * Writes (i.e. calls to {@code put} or {@code remove} must be synchronized externally. Reads, however, do not
     * require synchronization -- any number of reads can occur concurrently alongside a write.
     *
     * @param <T> the type of object stored in the map
     */
    final class Hash<T> implements StaticIntMap<T> {
        private static final VarHandle IAA;
        private static final VarHandle OAA;

        static {
            IAA = MethodHandles.arrayElementVarHandle(int[].class);
            OAA = MethodHandles.arrayElementVarHandle(Object[].class);
        }

        private static final Entries EMPTY_ENTRIES = new Entries(new int[0], new Object[0]);
        private static final float LOAD_FACTOR = 0.7F;

        /**
         * Initial size of the table. Must be a power of 2.
         */
        private static final int INITIAL_SIZE = 8;

        private record Entries(int[] keys, Object[] values) {
            private static Entries copy(int[] keys, Object[] values) {
                assert keys.length == values.length;

                final int[] newKeys = new int[keys.length];
                final Object[] newValues = new Object[keys.length];

                for (int i = 0; i < newKeys.length; i++) {
                    final int k = (int) IAA.getOpaque(keys, i);
                    final Object v = (Object) OAA.getOpaque(values, i);
                    VarHandle.loadLoadFence();

                    newKeys[i] = k;
                    switch (k) {
                        case -1, 0: newValues[i] = v;
                    }
                }

                return new Entries(newKeys, newValues);
            }
        }

        private record Entry(int key, Object value) {}

        /**
         * Re-assigned whenever rehashing. {@code keys} and {@code values} array elements should only be accessed
         * through VarHandle static fields {@code IAA} and {@code OAA}, respectively, using opaque mode or stronger.
         */
        private volatile Entries entries;

        /**
         * Number of used elements in the table. Only used for determining when a rehash is needed.
         * <p>
         * It is not necessary to mark this variable as {@code volatile}: it is only accessed through write methods
         * {@code put} or {@code remove}. These methods must be synchronized externally.
         */
        private int size;

        private Hash(Entries entries) {
            this.entries = entries;
            this.size = computeSize(entries.keys);
        }

        public Hash() {
            this(EMPTY_ENTRIES);
        }

        /**
         * Used to compute the actual size of the map, based on the keys array.
         * <p>
         * Should ONLY be used on non-shared memory, i.e. the array must not be written to by another thread!
         *
         * @param k the key array
         * @return the size of the array
         */
        private static int computeSize(int[] k) {
            int size = 0;
            for (int key : k) {
                switch (key) {
                    case -1, 0: {}
                    default: size++;
                }
            }
            return size;
        }

        /**
         * Compute the probe sequence at index {@code i}, starting at {@code key}. The return value modulo the array
         * length must be computed in order to actually use the index.
         *
         * @param key the key to probe
         * @param i the probe sequence index
         * @return the probe index, without computing the modulus
         */
        private static int probeIndex(int key, int i) {
            // quadratic probing based on the function h(k, i) = h(k) + 0.5i + 0.5i^2
            // to avoid needing to do floating point math, we actually solve 2h(k, i) = 2h(k) + i + i^2
            return ((key << 1) + i + (i * i)) >>> 1;

            // linear probing
            // return key + i;
        }

        /**
         * Searches for {@code key} in the key set. Returns -1 iff the key does not exist. Should not be called on an
         * empty key array!
         * <p>
         * Used when getting or removing entries.
         *
         * @param key the key to search
         * @param k the key array
         * @return the index of the key; -1 iff not present
         */
        private static int probeKey(int key, int[] k) {
            final int mask = k.length - 1;

            for (int i = 0; ; i++) {
                final int probeIndex = probeIndex(key, i) & mask;
                final int sample = (int) IAA.getOpaque(k, probeIndex);

                if (sample == key) return probeIndex;
                else if (sample == 0) return -1;
            }
        }

        /**
         * Searches for a strictly empty entry. Does not take into account removed entries. Only used when rehashing, as
         * the new key array will not have any values that have been marked removed.
         * <p>
         * Should not be called on an empty array!
         *
         * @param key the key to ultimately put into the array
         * @param k the key array
         * @return the index of an empty place to put the key; -1 iff an empty position cannot be found
         */
        private static int probeEmpty(int key, int[] k) {
            final int mask = k.length - 1;

            for (int i = 0; ; i++) {
                final int probeIndex = probeIndex(key, i) & mask;
                if ((int) IAA.getOpaque(k, probeIndex) == 0) return probeIndex;
            }
        }

        /**
         * Searches for a place to insert the key. Will return (in order of priority):
         *
         * <ol>
         *     <li>the index of {@code key} if it exists</li>
         *     <li>first encountered removed entry</li>
         *     <li>an empty slot</li>
         * </ol>

         * Used only by {@code put}. As with the other probe methods, this should never be called on an empty array!
         *
         * @param key the key
         * @param k the key array
         * @return the index of the location at which they key should be inserted; -1 otherwise
         */
        private static int probePut(int key, int[] k) {
            final int mask = k.length - 1;

            int tombstoneIndex = -1;
            for (int i = 0; ; i++) {
                final int probeIndex = probeIndex(key, i) & mask;
                final int sample = (int) IAA.getOpaque(k, probeIndex);

                if (sample == 0) return tombstoneIndex == -1 ? probeIndex : tombstoneIndex;
                else if (tombstoneIndex == -1 && sample == -1) tombstoneIndex = probeIndex;
                else if (sample == key) return probeIndex;
            }
        }

        /**
         * Resizes the table. Can either increase or decrease the table size.
         * <p>
         * This method does not check if the new size of the table is actually sufficient to hold all of its items.
         *
         * @param newSize the new size of the table; must be a power of 2
         */
        @SuppressWarnings("unchecked")
        private void rehash(int newSize) {
            final Entries entries = this.entries;
            final int[] k = entries.keys;
            final T[] v = (T[]) entries.values;

            final int[] newK = new int[newSize];
            final T[] newV = (T[]) new Object[newSize];

            for (int i = 0; i < k.length; i++) {
                final int oldKey = (int) IAA.getOpaque(k, i);
                final T oldValue = (T) ((Object) OAA.getOpaque(v, i));
                VarHandle.loadLoadFence();

                switch (oldKey) {
                    case 0, -1: continue;
                }

                final int newIndex = probeEmpty(oldKey, newK);

                newK[newIndex] = oldKey;
                newV[newIndex] = oldValue;
            }

            this.entries = new Entries(newK, newV);
        }

        @SuppressWarnings("unchecked")
        @Override
        public T get(int key) {
            final Entries entries = this.entries;

            final int[] k = entries.keys;
            if (k.length == 0) return null;

            final T[] v = (T[]) entries.values;

            final int index = probeKey(key + 1, k);
            if (index == -1) return null;

            return (T) ((Object) OAA.getOpaque(v, index));
        }

        @SuppressWarnings("unchecked")
        @Override
        public void forValues(Consumer<T> consumer) {
            final Entries entries = this.entries;
            final int[] k = entries.keys;
            final T[] v = (T[]) entries.values;

            for (int i = 0; i < k.length; i++) {
                final int key = (int) IAA.getOpaque(k, i);
                final T value = (T) ((Object) OAA.getOpaque(v, i));
                VarHandle.loadLoadFence();

                switch (key) {
                    case 0, -1: continue;
                    default: consumer.accept(value);
                }
            }
        }

        @Override
        public StaticIntMap<T> copy() {
            final Entries entries = this.entries;
            return new Hash<>(Entries.copy(entries.keys, entries.values));
        }

        @SuppressWarnings("unchecked")
        @Override
        public void put(int key, T value) {
            final Entries entries = this.entries;

            final int[] k = entries.keys;
            final T[] v = (T[]) entries.values;
            key++;

            if (k.length == 0) {
                final int[] newK = new int[INITIAL_SIZE];
                final T[] newV = (T[]) new Object[INITIAL_SIZE];

                int index = key & (INITIAL_SIZE - 1);
                newK[index] = key;
                newV[index] = value;

                this.entries = new Entries(newK, newV);
                this.size = 1;
                return;
            }

            final int index = probePut(key, k);
            if (index != -1) {
                OAA.setOpaque(v, index, value);
                VarHandle.storeStoreFence();

                switch ((int) IAA.getOpaque(k, index)) {
                    case 0, -1: {
                        IAA.setOpaque(k, index, key);
                        this.size++;
                    }
                }

                if (this.size + 1 >= (int) (k.length * LOAD_FACTOR)) rehash(k.length << 1);
                return;
            }

            // should be unreachable, we always reserve a bit of space even if the load factor is 1
            throw new IllegalStateException("Unable to find space for value");
        }

        @SuppressWarnings("unchecked")
        @Override
        public void remove(int key) {
            final Entries entries = this.entries;
            final int[] k = entries.keys;
            if (k.length == 0) return;

            final T[] v = (T[]) entries.values;

            final int index = probeKey(key + 1, k);
            if (index == -1) return;

            IAA.setOpaque(k, index, -1);
            VarHandle.storeStoreFence();
            OAA.setOpaque(v, index, null);

            if (--this.size == 0) this.entries = EMPTY_ENTRIES;
            else if (this.size + 1 <= (int) ((1F - LOAD_FACTOR) * k.length)) rehash(k.length >> 1);
        }

        @Override
        public void updateContent(StaticIntMap<T> content) {
            if (content instanceof StaticIntMap.Hash<T> other) {
                final Entries otherEntries = other.entries;

                final Entries newEntries = Entries.copy(otherEntries.keys, otherEntries.values);
                final int newSize = computeSize(newEntries.keys);

                this.entries = newEntries;
                this.size = newSize;
            } else throw new IllegalArgumentException("Invalid content type");
        }
    }
}
