package net.minestom.server.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TagWriteBenchmark {
    static final Tag<String> TAG = Tag.String("key");

    TagHandler tagHandler;
    Tag<String> secondTag;

    CompoundBinaryTag compound;

    @Setup
    public void setup() {
        // Tag benchmark
        this.tagHandler = TagHandler.newHandler();
        tagHandler.setTag(TAG, "value");
        secondTag = Tag.String("key");

        this.compound = CompoundBinaryTag.builder().put("key",
                StringBinaryTag.stringBinaryTag("value")).build();
    }

    @Benchmark
    public void writeConstantTag() {
        tagHandler.setTag(TAG, "value");
    }

    @Benchmark
    public void writeDifferentTag() {
        tagHandler.setTag(secondTag, "value");
    }

    @Benchmark
    public void writeNewTag() {
        tagHandler.setTag(Tag.String("key"), "value");
    }
}
