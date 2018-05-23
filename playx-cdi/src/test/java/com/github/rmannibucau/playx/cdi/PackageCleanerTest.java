package com.github.rmannibucau.playx.cdi;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PackageCleanerTest {

    @Test
    public void empty() {
        assertTrue(new PackageCleaner().removeOverlaps(emptyList()).isEmpty());
    }

    @Test
    public void single() {
        assertEquals(singletonList("some"), new PackageCleaner().removeOverlaps(singletonList("some")));
    }

    @Test
    public void notOverlapping() {
        assertEquals(asList("a,b,c", "some", "some2"), new PackageCleaner().removeOverlaps(asList("some", "some2", "a,b,c")));
    }

    @Test
    public void overlapping() {
        assertEquals(asList("a,b,c", "some"), new PackageCleaner().removeOverlaps(asList("some", "some.foo", "some.a.b", "a,b,c")));
    }
}
