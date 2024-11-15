package net.minestom.server.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TagReadBenchmark {
    static final Tag<String> TAG = Tag.String("key");

    @Param({"false", "true"})
    public boolean present;

    TagHandler tagHandler;
    Tag<String> secondTag;

    CompoundBinaryTag compound;

    @Setup
    public void setup() {
        // Tag benchmark
        this.tagHandler = TagHandler.newHandler();
        if (present) tagHandler.setTag(TAG, "value");
        secondTag = Tag.String("key");

        this.compound = present ? CompoundBinaryTag.builder().put("key", StringBinaryTag
                .stringBinaryTag("value")).build() : CompoundBinaryTag.empty();
    }

    @Benchmark
    public void readConstantTag(Blackhole blackhole) {
        blackhole.consume(tagHandler.getTag(TAG));
    }

    @Benchmark
    public void readDifferentTag(Blackhole blackhole) {
        blackhole.consume(tagHandler.getTag(secondTag));
    }

    @Benchmark
    public void readNewTag(Blackhole blackhole) {
        blackhole.consume(tagHandler.getTag(Tag.String("key")));
    }

    @Benchmark
    public void readCompound(Blackhole blackhole) {
        blackhole.consume(compound.getString("key"));
    }
}
