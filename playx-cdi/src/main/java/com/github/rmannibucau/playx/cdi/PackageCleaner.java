package com.github.rmannibucau.playx.cdi;

import java.util.ArrayList;
import java.util.Collection;

class PackageCleaner {

    Collection<String> removeOverlaps(final Collection<String> packages) {
        return new ArrayList<>(packages).stream().sorted().collect(ArrayList::new, (output, pck) -> {
            if (output.stream().noneMatch(it -> pck.startsWith(it + '.'))) {
                output.add(pck);
            }
        }, Collection::addAll);
    }
}
