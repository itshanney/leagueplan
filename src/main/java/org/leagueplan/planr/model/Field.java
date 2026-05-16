package org.leagueplan.planr.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public record Field(UUID id, String name, String address, List<AvailabilityWindow> windows) {

    public Field withWindowAdded(AvailabilityWindow window) {
        return new Field(id, name, address,
            Stream.concat(windows.stream(), Stream.of(window)).toList());
    }

    public Field withWindowReplaced(int zeroBasedIndex, AvailabilityWindow replacement) {
        List<AvailabilityWindow> mutable = new ArrayList<>(windows);
        mutable.set(zeroBasedIndex, replacement);
        return new Field(id, name, address, List.copyOf(mutable));
    }

    public Field withWindowRemoved(int zeroBasedIndex) {
        List<AvailabilityWindow> mutable = new ArrayList<>(windows);
        mutable.remove(zeroBasedIndex);
        return new Field(id, name, address, List.copyOf(mutable));
    }
}
