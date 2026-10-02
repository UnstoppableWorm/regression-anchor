package io.github.unstoppableworm.anchor.testsupport;

import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only backend: stores opaque bytes without a codec or knowledge of the JSON schema. */
public final class TestSnapshotStore implements SnapshotStore {
    public final ConcurrentHashMap<String, byte[]> baselines = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, byte[]> reports = new ConcurrentHashMap<>();

    @Override public byte[] load(String caseId) {
        byte[] value = baselines.get(caseId);
        if (value == null) throw new NoSuchElementException(caseId);
        return value.clone();
    }
    @Override public boolean exists(String caseId) { return baselines.containsKey(caseId); }
    @Override public void create(String caseId, byte[] snapshotJson) {
        if (baselines.putIfAbsent(caseId, snapshotJson.clone()) != null)
            throw new IllegalStateException("Baseline already exists: " + caseId);
    }
    @Override public void saveReport(String caseId, byte[] reportJson) { reports.put(caseId, reportJson.clone()); }
}
