package net.minestom.server.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.L_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

@JCStressTest
@Outcome(id = "tag", expect = ACCEPTABLE)
@Outcome(id = "tag_path", expect = ACCEPTABLE)
@State
public class TagPathTest {
    private static final Tag<Integer> TAG = Tag.Integer("path");
    private static final Tag<Integer> TAG_PATH = Tag.Integer("key").path("path");

    private final TagHandler handler = TagHandler.newHandler();

    @Actor
    public void actor1() {
        handler.setTag(TAG, 1);
    }

    @Actor
    public void actor2() {
        handler.setTag(TAG_PATH, 5);
    }

    @Arbiter
    public void arbiter(L_Result r) {
        var compound = handler.asCompound();

        var tag1 = CompoundBinaryTag.builder().put("path", IntBinaryTag.intBinaryTag(1)).build();
        var tag2 = CompoundBinaryTag.builder().put("path",
                CompoundBinaryTag.builder().put("key", IntBinaryTag.intBinaryTag(5)).build()).build();

        if (compound.equals(tag1)) {
            r.r1 = "tag";
        } else if (compound.equals(tag2)) {
            r.r1 = "tag_path";
        } else {
            r.r1 = compound;
        }
    }
}
