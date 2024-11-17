package net.minestom.server.tag;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TagComponentTest {

    @Test
    void test() {
        int scope = 4;
        TagHandler tagHandler = TagHandler.newHandler();

        List<String> path = new ArrayList<>(scope);
        for (int i = 0; i < scope; i++) path.add("key" + i);
        Tag<String> tag = Tag.String("key").path(path.toArray(String[]::new));

        tagHandler.setTag(tag, "value");

        tagHandler.getTag(tag);
    }

    @Test
    public void get() {
        var component = Component.text("Hey");
        var tag = Tag.Component("component");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, component);
        assertEquals(component, handler.getTag(tag));
    }

    @Test
    public void empty() {
        var tag = Tag.Component("component");
        var handler = TagHandler.newHandler();
        assertNull(handler.getTag(tag));
    }

    @Test
    public void invalidTag() {
        var tag = Tag.Component("entry");
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.Integer("entry"), 1);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void nbtFallback() {
        var component = Component.text("Hey");
        var tag = Tag.Component("component");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, component);
        handler = TagHandler.fromCompound(handler.asCompound());
        assertEquals(component, handler.getTag(tag));
    }
}
