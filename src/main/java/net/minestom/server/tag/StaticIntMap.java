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
     * Writes (i.e. calls to {@code put} or {@code remove}) must be synchronized externally. Reads, however, do not
     * require synchronization -- any number of reads can occur concurrently alongside a write.
     *
     * @param <T> the type of object stored in the map
     */
    final class Hash<T> implements StaticIntMap<T> {
        private static final VarHandle OAA = MethodHandles.arrayElementVarHandle(Object[].class);

        private static final Entry[] EMPTY_ENTRIES = new Entry[0];
        private static final Entry TOMBSTONE = new Entry(-1, null);
        private static final float LOAD_FACTOR = 0.7F;

        /**
         * Initial size of the table. Must be a power of 2.
         */
        private static final int INITIAL_SIZE = 4;

        private record Entry(int key, Object value) {}

        private volatile Entry[] entries;

        /**
         * Number of used elements in the table. Only used for determining when a rehash is needed.
         * <p>
         * It is not necessary to mark this variable as {@code volatile}: it is only accessed through write methods
         * {@code put} or {@code remove}. These methods must be synchronized externally.
         */
        private int size;

        private Hash(Entry[] entries) {
            this.entries = entries;
            this.size = computeSize(entries);
        }

        public Hash() {
            this(EMPTY_ENTRIES);
        }

        private static Entry[] copy(Entry[] other) {
            Entry[] newEntries = new Entry[other.length];
            for (int i = 0; i < other.length; i++) {
                newEntries[i] = (Entry) OAA.getOpaque(other, i);
            }

            return newEntries;
        }

        private static int computeSize(Entry[] entries) {
            int size = 0;
            for (Entry entry : entries) {
                if (entry == null || entry.key == -1) continue;
                size++;
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

        private static Entry probeKey(int key, Entry[] k) {
            final int mask = k.length - 1;

            for (int i = 0; ; i++) {
                final Entry sample = (Entry) OAA.getOpaque(k, probeIndex(key, i) & mask);

                if (sample == null) return null;
                else if (sample.key == key) return sample;
            }
        }

        private static int probeRemove(int key, Entry[] k) {
            final int mask = k.length - 1;

            for (int i = 0; ; i++) {
                final int probeIndex = probeIndex(key, i) & mask;
                final Entry sample = (Entry) OAA.getOpaque(k, probeIndex);

                if (sample == null) return -1;
                else if (sample.key == key) return probeIndex;
            }
        }

        private static int probeEmpty(int key, Entry[] k) {
            final int mask = k.length - 1;

            for (int i = 0; ; i++) {
                final int probeIndex = probeIndex(key, i) & mask;
                if ((Entry) OAA.getOpaque(k, probeIndex) == null) return probeIndex;
            }
        }

        private static int probePut(int key, Entry[] k) {
            final int mask = k.length - 1;

            int tombstoneIndex = -1;
            for (int i = 0; ; i++) {
                final int probeIndex = probeIndex(key, i) & mask;
                final Entry sample = (Entry) OAA.getOpaque(k, probeIndex);

                if (sample == null) return tombstoneIndex == -1 ? probeIndex : tombstoneIndex;
                else if (sample.key == key) return probeIndex;
                else if (tombstoneIndex == -1 && sample.key == -1) tombstoneIndex = probeIndex;
            }
        }

        /**
         * Resizes the table. Can either increase or decrease the table size.
         * <p>
         * This method does not check if the new size of the table is actually sufficient to hold all of its items.
         *
         * @param newSize the new size of the table; must be a power of 2
         */
        private void rehash(int newSize) {
            final Entry[] entries = this.entries;
            final Entry[] newEntries = new Entry[newSize];

            for (int i = 0; i < entries.length; i++) {
                final Entry oldEntry = (Entry) OAA.getOpaque(entries, i);
                if (oldEntry == null || oldEntry.key == -1) continue;

                newEntries[probeEmpty(oldEntry.key, newEntries)] = oldEntry;
            }

            this.entries = newEntries;
        }

        @SuppressWarnings("unchecked")
        @Override
        public T get(int key) {
            final Entry[] entries = this.entries;
            if (entries.length == 0) return null;

            final Entry entry = probeKey(key, entries);
            if (entry == null) return null;

            return (T) entry.value;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void forValues(@NotNull Consumer<T> consumer) {
            final Entry[] entries = this.entries;

            for (int i = 0; i < entries.length; i++) {
                final Entry entry = (Entry) OAA.getOpaque(entries, i);
                if (entry == null || entry.key == -1) continue;

                consumer.accept((T) entry.value);
            }
        }

        @Override
        public StaticIntMap<T> copy() {
            final Entry[] entries = this.entries;
            return new Hash<>(copy(entries));
        }

        @Override
        public void put(int key, T value) {
            final Entry[] entries = this.entries;

            if (entries.length == 0) {
                final Entry[] newEntries = new Entry[INITIAL_SIZE];
                newEntries[key & (INITIAL_SIZE - 1)] = new Entry(key, value);

                this.entries = newEntries;
                this.size = 1;
                return;
            }

            final int index = probePut(key, entries);
            if (index != -1) {
                final Entry oldEntry = (Entry) OAA.getOpaque(entries, index);
                OAA.setOpaque(entries, index, new Entry(key, value));

                if (oldEntry == null || oldEntry.key == -1) this.size++;

                if (this.size + 1 >= (int) (entries.length * LOAD_FACTOR)) rehash(entries.length << 1);
                return;
            }

            // should be unreachable, we always reserve a bit of space even if the load factor is 1
            throw new IllegalStateException("Unable to find space for value");
        }

        @Override
        public void remove(int key) {
            final Entry[] entries = this.entries;
            if (entries.length == 0) return;

            final int entryIndex = probeRemove(key, entries);
            if (entryIndex == -1) return;

            OAA.setOpaque(entries, entryIndex, TOMBSTONE);

            if (--this.size == 0) this.entries = EMPTY_ENTRIES;
            else if (this.size + 1 <= (int) ((1F - LOAD_FACTOR) * entries.length)) rehash(entries.length >> 1);
        }

        @Override
        public void updateContent(StaticIntMap<T> content) {
            if (content instanceof StaticIntMap.Hash<T> other) {
                final Entry[] otherEntries = other.entries;
                final Entry[] newEntries = copy(otherEntries);

                final int newSize = computeSize(newEntries);

                this.entries = newEntries;
                this.size = newSize;
            } else throw new IllegalArgumentException("Invalid content type");
        }
    }
}
