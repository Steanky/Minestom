package net.minestom.server.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.ServerFlag;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

//import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.function.UnaryOperator;

final class TagHandlerImpl implements TagHandler {
    static final Serializers.Entry<Node, CompoundBinaryTag> NODE_SERIALIZER = new Serializers.Entry<>(BinaryTagTypes.COMPOUND, entries -> fromCompound(entries).root, Node::compound, true);

    private final Node root;
    private volatile Node copy;

    TagHandlerImpl(Node root) {
        this.root = root;
    }

    TagHandlerImpl() {
        this.root = new Node();
    }

    static TagHandlerImpl fromCompound(CompoundBinaryTag compound) {
        TagHandlerImpl handler = new TagHandlerImpl();
        TagNbtSeparator.separate(compound, entry -> handler.setTag(entry.tag(), entry.value()));
        handler.root.compound = compound;
        return handler;
    }

    @Override
    public <T> @UnknownNullability T getTag(@NotNull Tag<T> tag) {
        //VarHandle.fullFence();
        return root.getTag(tag);
    }

    @Override
    public <T> void setTag(@NotNull Tag<T> tag, @Nullable T value) {
        // Handle view tags
        if (tag.isView()) {
            synchronized (this) {
                Node syncNode = traversePathWrite(root, tag, value != null);
                if (syncNode != null) {
                    syncNode.updateContent(value != null ? (CompoundBinaryTag) tag.entry.write(value) : CompoundBinaryTag.empty());
                    syncNode.invalidate();
                }
            }
            return;
        }
        // Normal tag
        final int tagIndex = tag.index;
        //VarHandle.fullFence();
        Node node = traversePathWrite(root, tag, value != null);
        if (node == null)
            return; // Tried to remove an absent tag. Do nothing
        final StaticIntMap<Entry<?>> entries = node.entries;
        if (value != null) {
            Entry previous = entries.get(tagIndex);
            if (previous != null && previous.tag.shareValue(tag)) {
                previous.updateValue(tag.copyValue(value));
            } else {
                synchronized (this) {
                    node = traversePathWrite(root, tag, true);
                    node.entries.put(tagIndex, valueToEntry(node, tag, value));
                }
            }
        } else {
            synchronized (this) {
                node = traversePathWrite(root, tag, false);
                if (node == null) return;
                node.entries.remove(tagIndex);
            }
        }
        node.invalidate();
    }

    @Override
    public <T> @Nullable T getAndSetTag(@NotNull Tag<T> tag, @Nullable T value) {
        return updateTag0(tag, t -> value, true);
    }

    @Override
    public <T> void updateTag(@NotNull Tag<T> tag, @NotNull UnaryOperator<@UnknownNullability T> value) {
        updateTag0(tag, value, false);
    }

    @Override
    public <T> @UnknownNullability T updateAndGetTag(@NotNull Tag<T> tag, @NotNull UnaryOperator<@UnknownNullability T> value) {
        return updateTag0(tag, value, false);
    }

    @Override
    public <T> @UnknownNullability T getAndUpdateTag(@NotNull Tag<T> tag, @NotNull UnaryOperator<@UnknownNullability T> value) {
        return updateTag0(tag, value, true);
    }

    private synchronized <T> T updateTag0(@NotNull Tag<T> tag, @NotNull UnaryOperator<T> value, boolean returnPrevious) {
        final Node node = traversePathWrite(root, tag, true);
        if (tag.isView()) {
            final T previousValue = tag.read(node.compound());
            final T newValue = value.apply(previousValue);
            node.updateContent((CompoundBinaryTag) tag.entry.write(newValue));
            node.invalidate();
            return returnPrevious ? previousValue : newValue;
        }

        final int tagIndex = tag.index;
        final StaticIntMap<Entry<?>> entries = node.entries;

        final Entry previousEntry = entries.get(tagIndex);
        final T previousValue;
        if (previousEntry != null) {
            final Object previousTmp = previousEntry.getValue();
            if (previousTmp instanceof Node n) {
                final CompoundBinaryTag compound = CompoundBinaryTag.from(Map.of(tag.getKey(), n.compound()));
                previousValue = tag.read(compound);
            } else {
                previousValue = (T) previousTmp;
            }
        } else {
            previousValue = tag.createDefault();
        }
        final T newValue = value.apply(previousValue);
        if (newValue != null) entries.put(tagIndex, valueToEntry(node, tag, newValue));
        else entries.remove(tagIndex);

        node.invalidate();
        return returnPrevious ? previousValue : newValue;
    }

    @Override
    public @NotNull TagReadable readableCopy() {
        Node copy = this.copy;
        if (copy == null) {
            synchronized (this) {
                this.copy = copy = root.copy(null);
            }
        }
        return copy;
    }

    @Override
    public synchronized @NotNull TagHandler copy() {
        return new TagHandlerImpl(root.copy(null));
    }

    @Override
    public synchronized void updateContent(@NotNull CompoundBinaryTag compound) {
        this.root.updateContent(compound);
    }

    @Override
    public @NotNull CompoundBinaryTag asCompound() {
        //VarHandle.fullFence();
        return root.compound();
    }

    @Override
    public synchronized void clearTags() {
        this.root.entries.clear();
        this.root.invalidate();
    }

    private static Node traversePathRead(Node node, Tag<?> tag) {
        final Tag.PathEntry[] paths = tag.path;
        if (paths == null) return node;
        for (var path : paths) {
            final Entry<?> entry = node.entries.get(path.index());
            if (entry == null || (node = entry.toNode()) == null)
                return null;
        }
        return node;
    }

    @Contract("_, _, true -> !null")
    private Node traversePathWrite(Node root, Tag<?> tag,
                                   boolean present) {
        final Tag.PathEntry[] paths = tag.path;
        if (paths == null) return root;
        Node local = root;
        for (Tag.PathEntry path : paths) {
            final int pathIndex = path.index();
            final Entry<?> entry = local.entries.get(pathIndex);
            if (entry != null && entry.tag.entry.isPath()) {
                // Existing path, continue navigating
                final Node tmp = (Node) entry.getValue();
                assert tmp.parent == local : "Path parent is invalid: " + tmp.parent + " != " + local;
                local = tmp;
            } else {
                if (!present) return null;
                synchronized (this) {
                    var synEntry = local.entries.get(pathIndex);
                    if (synEntry != null && synEntry.tag.entry.isPath()) {
                        // Existing path, continue navigating
                        final Node tmp = (Node) synEntry.getValue();
                        assert tmp.parent == local : "Path parent is invalid: " + tmp.parent + " != " + local;
                        local = tmp;
                        continue;
                    }

                    // Empty path, create a new handler.
                    // Slow path is taken if the entry comes from a Structure tag, requiring conversion from NBT
                    Node tmp = local;
                    local = new Node(tmp);
                    if (synEntry != null && synEntry.updatedNbt() instanceof CompoundBinaryTag compound) {
                        local.updateContent(compound);
                    }
                    tmp.entries.put(pathIndex, Entry.makePathEntry(path.name(), local));
                }
            }
        }
        return local;
    }

    private <T> Entry<?> valueToEntry(Node parent, Tag<T> tag, @NotNull T value) {
        if (value instanceof BinaryTag nbt) {
            if (nbt instanceof CompoundBinaryTag compound) {
                final TagHandlerImpl handler = fromCompound(compound);
                return Entry.makePathEntry(tag, new Node(parent, handler.root.entries));
            } else {
                final var nbtEntry = TagNbtSeparator.separateSingle(tag.getKey(), nbt);
                return new Entry<>(nbtEntry.tag(), nbtEntry.value());
            }
        } else {
            return new Entry<>(tag, tag.copyValue(value));
        }
    }

    final class Node implements TagReadable {
        private static final CompoundBinaryTag UPDATING_SENTINEL = CompoundBinaryTag
                .from(Map.of("SENTINEL", CompoundBinaryTag.empty()));

        private static final VarHandle COMPOUND_ACCESS;

        static {
            try {
                COMPOUND_ACCESS = MethodHandles.lookup().findVarHandle(Node.class, "compound", CompoundBinaryTag.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        final Node parent;
        final StaticIntMap<Entry<?>> entries;
        CompoundBinaryTag compound;

        public Node(Node parent, StaticIntMap<Entry<?>> entries) {
            this.parent = parent;
            this.entries = entries;
        }

        Node(Node parent) {
            this(parent, new StaticIntMap.Hash<>());
        }

        Node() {
            this(null);
        }

        @Override
        public <T> @UnknownNullability T getTag(@NotNull Tag<T> tag) {
            final Node node = traversePathRead(this, tag);
            if (node == null)
                return tag.createDefault(); // Must be a path-able entry, but not present
            if (tag.isView()) return tag.read(node.compound());

            final StaticIntMap<Entry<?>> entries = node.entries;
            final Entry<?> entry = entries.get(tag.index);
            if (entry == null)
                return tag.createDefault(); // Not present
            if (entry.tag.shareValue(tag)) {
                // The tag used to write the entry is compatible with the one used to get
                // return the value directly
                //noinspection unchecked
                return (T) entry.getValue();
            }
            // Value must be parsed from nbt if the tag is different
            final BinaryTag nbt = entry.updatedNbt();
            final Serializers.Entry<T, BinaryTag> serializerEntry = tag.entry;
            final BinaryTagType<BinaryTag> type = serializerEntry.nbtType();
            return type == null || type.equals(nbt.type()) ? serializerEntry.read(nbt) : tag.createDefault();
        }

        void updateContent(@NotNull CompoundBinaryTag compound) {
            final TagHandlerImpl converted = fromCompound(compound);
            this.entries.updateContent(converted.root.entries);
            COMPOUND_ACCESS.setOpaque(this, compound);
        }

        @SuppressWarnings("rawtypes")
        private CompoundBinaryTag computeCompound() {
            CompoundBinaryTag.Builder tmp = CompoundBinaryTag.builder();
            this.entries.forValues(entry -> {
                final Tag tag = entry.tag;
                final BinaryTag nbt = entry.updatedNbt();
                if (nbt != null && (!tag.entry.isPath() || (!ServerFlag.SERIALIZE_EMPTY_COMPOUND) &&
                        ((CompoundBinaryTag) nbt).size() > 0)) {
                    tmp.put(tag.getKey(), nbt);
                }
            });

            return tmp.build();
        }

        @NotNull CompoundBinaryTag compound() {
            if (!ServerFlag.TAG_HANDLER_CACHE_ENABLED) return computeCompound();

            CompoundBinaryTag compound = (CompoundBinaryTag) COMPOUND_ACCESS.compareAndExchange(this, null,
                    UPDATING_SENTINEL);
            if (compound == null) {
                try {
                    compound = computeCompound();
                }
                finally {
                    COMPOUND_ACCESS.compareAndSet(this, UPDATING_SENTINEL, compound);
                }
            }
            else if (compound == UPDATING_SENTINEL) {
                do compound = (CompoundBinaryTag) COMPOUND_ACCESS.getOpaque(this);
                while (compound == UPDATING_SENTINEL);

                if (compound == null) compound = computeCompound();
            }

            return compound;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Contract("null -> !null")
        Node copy(Node parent) {
            final CompoundBinaryTag.Builder tmp = CompoundBinaryTag.builder();
            final Node result = new Node(parent, new StaticIntMap.Hash<>());
            final StaticIntMap<Entry<?>> resultEntries = result.entries;

            this.entries.forValues(entry -> {
                final Tag tag = entry.tag;

                Object value = entry.getValue();
                BinaryTag nbt;
                if (value instanceof Node node) {
                    Node copy = node.copy(result);
                    if (copy == null)
                        return; // Empty node
                    value = copy;
                    nbt = copy.compound();
                } else {
                    nbt = entry.updatedNbt();
                }

                if (nbt != null)
                    tmp.put(tag.getKey(), nbt);
                resultEntries.put(tag.index, valueToEntry(result, tag, value));
            });

            final var compound = tmp.build();
            if ((!ServerFlag.SERIALIZE_EMPTY_COMPOUND) && compound.size() == 0 && parent != null)
                return null; // Empty child node

            // plain access is OK: `result` has not been made visible to other threads yet
            result.compound = compound;
            return result;
        }

        void invalidate() {
            Node tmp = this;
            do COMPOUND_ACCESS.setOpaque(tmp, null);
            while ((tmp = tmp.parent) != null);
            TagHandlerImpl.this.copy = null;
        }
    }

    private static final class Entry<T> {
        // used to enable CAS/CAE operations when updating cached nbt value
        private static final BinaryTag UPDATING_SENTINEL =
                new BinaryTag() {
                    @Override
                    public @NotNull BinaryTagType<? extends BinaryTag> type() {
                        return BinaryTagTypes.BYTE;
                    }
                };

        private static final VarHandle VALUE_ACCESS;
        private static final VarHandle NBT_ACCESS;

        static {
            try {
                VALUE_ACCESS = MethodHandles.lookup().findVarHandle(Entry.class, "value", Object.class);
                NBT_ACCESS = MethodHandles.lookup().findVarHandle(Entry.class, "nbt", BinaryTag.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        private final Tag<T> tag;

        /**
         * Accessed through {@link Entry#VALUE_ACCESS}
         */
        @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
        private T value;

        /**
         * Accessed through {@link Entry#NBT_ACCESS}
         */
        private BinaryTag nbt;

        Entry(Tag<T> tag, T value) {
            this.tag = tag;
            this.value = value;
        }

        static Entry<?> makePathEntry(String path, Node node) {
            return new Entry<>(Tag.tag(path, NODE_SERIALIZER), node);
        }

        static Entry<?> makePathEntry(Tag<?> tag, Node node) {
            return makePathEntry(tag.getKey(), node);
        }

        @SuppressWarnings("unchecked")
        BinaryTag updatedNbt() {
            if (tag.entry.isPath()) return ((Node) getValue()).compound();

            BinaryTag nbt = (BinaryTag) NBT_ACCESS.compareAndExchange(this, null, UPDATING_SENTINEL);

            // if null: we need to update the cached nbt
            if (nbt == null) {
                try {
                    // serialize our value
                    nbt = tag.entry.write((T) ((Object) VALUE_ACCESS.getAcquire(this)));
                }
                finally {
                    // out of threads calling updatedNbt, only the thread that initially reads nbt as null may update it
                    // if another thread writes nbt = null (ex. one calling updateValue), we won't update the cache
                    NBT_ACCESS.compareAndSet(this, UPDATING_SENTINEL, nbt);
                }
            }
            else if (nbt == UPDATING_SENTINEL) { // another thread is serializing the new value
                // do a quick spin-wait to see if we can get the serialized result from the other thread
                do nbt = (BinaryTag) NBT_ACCESS.getOpaque(this);
                while (nbt == UPDATING_SENTINEL);

                // the other thread's update failed: maybe it was interrupted by updateValue?
                // serialize the value directly, but don't update
                if (nbt == null) nbt = tag.entry.write((T) ((Object) VALUE_ACCESS.getAcquire(this)));
            }

            // no update needed, return the cached value
            return nbt;
        }

        void updateValue(T value) {
            assert !tag.entry.isPath();

            // release mode to ensure if nbt is seen as null, the new value that caused it to be null is also seen
            // updateValue can be called without any synchronization!
            VALUE_ACCESS.setRelease(this, value);
            NBT_ACCESS.setRelease(this, null);
        }

        @SuppressWarnings("unchecked")
        T getValue() {
            return (T) ((Object) VALUE_ACCESS.getOpaque(this));
        }

        Node toNode() {
            if (tag.entry.isPath()) return (Node) getValue();
            if (updatedNbt() instanceof CompoundBinaryTag compound) {
                // Slow path forcing a conversion of the structure to NBTCompound
                // TODO should the handler be cached inside the entry?
                return fromCompound(compound).root;
            }
            // Entry is not path-able
            return null;
        }
    }
}
