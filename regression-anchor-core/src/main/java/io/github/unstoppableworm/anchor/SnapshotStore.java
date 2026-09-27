package io.github.unstoppableworm.anchor;

import java.io.IOException;
import java.util.List;

public interface SnapshotStore {
    Snapshot load(String caseId) throws IOException;
    boolean exists(String caseId) throws IOException;
    void create(Snapshot snapshot) throws IOException;
    default void report(Snapshot actual, List<Difference> differences) throws IOException {}
}
