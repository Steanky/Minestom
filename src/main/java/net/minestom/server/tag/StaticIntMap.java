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
        private static final float LOAD_FACTOR = 0.75F;

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
                    if (k == -1 || k > 0) newValues[i] = v;
                }

                return new Entries(newKeys, newValues);
            }
        }

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
                if (key == -1 || key > 0) size++;
            }
            return size;
        }

        private static int probeIndex(int start, int i, int mask) {
            return (((start << 1) + i + (i * i)) >> 1) & mask;
        }

        private static int probeKey(int key, int[] k) {
            final int mask = k.length - 1;
            final int start = key & mask;

            for (int i = 0; i < k.length; i++) {
                final int probeIndex = probeIndex(start, i, mask);
                final int sample = (int) IAA.getOpaque(k, probeIndex);

                if (sample == key) return probeIndex;
                else if (sample == 0) return -1;
            }

            return -1;
        }

        private static int probeEmpty(int key, int[] k) {
            final int mask = k.length - 1;
            final int start = key & mask;

            for (int i = 0; i < k.length; i++) {
                final int probeIndex = probeIndex(start, i, mask);
                if ((int) IAA.getOpaque(k, probeIndex) == 0) return probeIndex;
            }

            return -1;
        }

        private static int probePut(int key, int[] k) {
            final int mask = k.length - 1;
            final int start = key & mask;

            int tombstoneIndex = -1;
            for (int i = 0; i < k.length; i++) {
                final int probeIndex = probeIndex(start, i, mask);
                final int sample = (int) IAA.getOpaque(k, probeIndex);

                if (tombstoneIndex == -1 && sample == -2) tombstoneIndex = probeIndex;
                else if (sample == key) return probeIndex;
                else if (sample == 0) return tombstoneIndex == -1 ? probeIndex : tombstoneIndex;
            }

            return tombstoneIndex;
        }

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

                if (oldKey == 0 || oldKey == -2) continue;

                final int newIndex = probeEmpty(oldKey, newK);

                // shouldn't happen unless rehashing to a newSize that can't fit all elements
                assert newIndex != -1 : "Could not find space for rehashed element";

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
            final T[] v = (T[]) entries.values;

            if (k.length == 0) return null;

            if (key == 0) key--;
            final int index = probeKey(key, k);
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

                if (key == 0 || key == -2) continue;
                consumer.accept(value);
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
            if (key == 0) key--;

            if (k.length == 0) {
                final int[] newK = new int[4];
                final T[] newV = (T[]) new Object[4];

                int index = key & 3;
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

                final int oldKey = (int) IAA.getOpaque(k, index);
                if (oldKey == 0 || oldKey == -2) {
                    IAA.setOpaque(k, index, key);
                    this.size++;
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
            final T[] v = (T[]) entries.values;

            if (k.length == 0) return;

            if (key == 0) key--;
            final int index = probeKey(key, k);
            if (index == -1) return;

            IAA.setOpaque(k, index, -2);
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
