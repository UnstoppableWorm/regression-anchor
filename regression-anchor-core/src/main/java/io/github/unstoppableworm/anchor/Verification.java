package io.github.unstoppableworm.anchor;

import java.util.List;
import java.util.stream.Collectors;

public record Verification<T>(T value, Snapshot actual, List<Difference> differences) {
    public boolean matches() { return differences.isEmpty(); }
    public Verification<T> assertMatches() {
        if (!matches()) throw new AssertionError("Regression case '" + actual.caseId() + "' differs at "
                + differences.size() + " path(s):\n" + differences.stream().limit(20)
                .map(d -> d.path() + " expected=" + (d.expectedMissing() ? "<missing>" : d.expected())
                        + " actual=" + (d.actualMissing() ? "<missing>" : d.actual()))
                .collect(Collectors.joining("\n")));
        return this;
    }
}
