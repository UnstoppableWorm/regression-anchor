package io.github.unstoppableworm.anchor.storage;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.verification.VerificationReport;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SnapshotStoreContractTest {
    private final SnapshotCodec codec = new SnapshotCodec();
    private final TestSnapshotStore backing = new TestSnapshotStore();

    // An application can persist only baselines and use Verification directly, without reports.
    private class ConsumerStore implements SnapshotStore {
        @Override public byte[] load(String id) throws Exception { return backing.load(id); }
        @Override public boolean exists(String id) throws Exception { return backing.exists(id); }
        @Override public void create(String id, byte[] json) throws Exception { backing.create(id, json); }
    }

    @Test void acceptsConsumerKeysAndAllowsReportsToRemainUnpersisted() throws Exception {
        var anchor = new RegressionAnchor(codec, new ConsumerStore());
        String id = "주문/2026:고객 A";
        anchor.record(id, 3, i -> i * 2);
        anchor.replay(id, Integer.class, i -> i * 2).assertMatches();
        var changed = anchor.replay(id, Integer.class, i -> i * 3);
        assertThat(changed.differences()).isNotEmpty();
        assertThat(changed.actual().outcome().value().intValue()).isEqualTo(9);
        assertThat(anchor.loadSnapshot(id).outcome().value().intValue()).isEqualTo(6);
        assertThat(backing.reports).isEmpty();
    }

    @Test void validatesLoadedIdentityAndVersionBeforeExecutingRegardlessOfBackend() throws Exception {
        new RegressionAnchor(codec, backing).record("case", 1, i -> i);
        Snapshot valid = new RegressionAnchor(codec, backing).loadSnapshot("case");
        Snapshot[] invalid = {null,
                new Snapshot(valid.formatVersion() + 1, "case", valid.input(), valid.outcome(), valid.calls()),
                new Snapshot(valid.formatVersion(), "another", valid.input(), valid.outcome(), valid.calls())};
        for (Snapshot snapshot : invalid) {
            byte[] json = codec.writeJson(snapshot);
            var anchor = new RegressionAnchor(codec, new ConsumerStore() {
                @Override public byte[] load(String id) { return json; }
            });
            var executed = new AtomicBoolean();
            assertThatThrownBy(() -> anchor.replay("case", Integer.class, i -> { executed.set(true); return i; }))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("inconsistent snapshot");
            assertThat(executed).isFalse();
            assertThat(anchor.isActive()).isFalse();
        }
    }

    @Test void lookupFailurePropagatesWithoutExecutingTheTrigger() {
        var failure = new BackendUnavailable();
        var anchor = new RegressionAnchor(codec, new ConsumerStore() {
            @Override public byte[] load(String id) throws BackendUnavailable { throw failure; }
        });
        assertThatThrownBy(() -> anchor.replay("case", Integer.class, i -> { fail("must not execute"); return i; }))
                .isSameAs(failure);
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void existenceFailurePropagatesWithoutExecutingTheTrigger() {
        var failure = new BackendUnavailable();
        var anchor = new RegressionAnchor(codec, new ConsumerStore() {
            @Override public boolean exists(String id) throws BackendUnavailable { throw failure; }
        });
        assertThatThrownBy(() -> anchor.record("case", 1, i -> { fail("must not execute"); return i; }))
                .isSameAs(failure);
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void createFailureCannotAppearAsSuccessfulRecordingAndScopeIsCleared() {
        var failure = new BackendUnavailable();
        var anchor = new RegressionAnchor(codec, new ConsumerStore() {
            @Override public void create(String id, byte[] json) throws BackendUnavailable { throw failure; }
        });
        var executed = new AtomicBoolean();
        assertThatThrownBy(() -> anchor.record("case", 1, i -> { executed.set(true); return i; })).isSameAs(failure);
        assertThat(executed).isTrue();
        assertThat(backing.exists("case")).isFalse();
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void reportFailureIsVisibleWithoutChangingBaseline() throws Exception {
        var failure = new BackendUnavailable();
        var anchor = new RegressionAnchor(codec, new ConsumerStore() {
            @Override public void saveReport(String id, byte[] json) throws BackendUnavailable { throw failure; }
        });
        anchor.record("case", 1, i -> i);
        byte[] original = backing.baselines.get("case");
        assertThatThrownBy(() -> anchor.replay("case", Integer.class, i -> i + 1)).isSameAs(failure);
        assertThat(backing.baselines.get("case")).isEqualTo(original);
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void opaqueBackendReceivesLibraryJsonIncludingAnchorsAndUtf8() throws Exception {
        var anchor = new RegressionAnchor(codec, backing);
        anchor.record("고객", new Before("한글 고객"), Before::oldName);
        byte[] original = backing.load("고객");
        String json = new String(original, StandardCharsets.UTF_8);
        assertThat(json).contains("한글 고객", "\"customer\"").doesNotContain("oldName");
        var replayed = anchor.replayInput("고객", new After("한글 고객"), After::renamed).assertMatches();
        assertThat(replayed.value()).isEqualTo("한글 고객");
        var report = codec.readJson(backing.reports.get("고객"), VerificationReport.class);
        assertThat(report.matches()).isTrue();
        assertThat(report.callCount()).isZero();
        assertThat(report.actual()).isEqualTo(replayed.actual());
        assertThat(backing.load("고객")).isEqualTo(original);
    }

    @Test void rejectsCorruptJsonBeforeBusinessExecutionWithoutBackendParsing() throws Exception {
        var original = new RegressionAnchor(codec, backing);
        original.record("case", 1, i -> i);
        String valid = new String(backing.load("case"), StandardCharsets.UTF_8);
        for (byte[] json : new byte[][]{null, new byte[0], "{broken".getBytes(StandardCharsets.UTF_8),
                (valid + " {}").getBytes(StandardCharsets.UTF_8)}) {
            var anchor = new RegressionAnchor(codec, new ConsumerStore() {
                @Override public byte[] load(String id) { return json; }
            });
            var executed = new AtomicBoolean();
            assertThatThrownBy(() -> anchor.replay("case", Integer.class, i -> { executed.set(true); return i; }))
                    .isInstanceOfAny(java.io.IOException.class, IllegalStateException.class);
            assertThat(executed).isFalse();
            assertThat(anchor.isActive()).isFalse();
        }
        assertThat(backing.reports).isEmpty();
    }

    @Test void reportRoundTripKeepsMissingFieldsDifferentFromExplicitNull() throws Exception {
        var anchor = new RegressionAnchor(codec, backing);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("removed", null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("added", null);
        anchor.record("nullable", 1, i -> before);
        var result = anchor.replay("nullable", Integer.class, i -> after);
        var report = codec.readJson(backing.reports.get("nullable"), VerificationReport.class);
        assertThat(report.matches()).isFalse();
        assertThat(report.differences()).isEqualTo(result.differences()).hasSize(2);
        assertThat(report.differences()).anySatisfy(difference -> {
            assertThat(difference.path()).endsWith("/removed");
            assertThat(difference.expected().isNull()).isTrue();
            assertThat(difference.actualMissing()).isTrue();
        }).anySatisfy(difference -> {
            assertThat(difference.path()).endsWith("/added");
            assertThat(difference.expectedMissing()).isTrue();
            assertThat(difference.actual().isNull()).isTrue();
        });
    }

    @Test void libraryExportsTheSameReportToAConsumerSelectedDestination() throws Exception {
        var anchor = new RegressionAnchor(codec, backing);
        anchor.record("case", 2, i -> i * 2);
        var result = anchor.replay("case", Integer.class, i -> i * 3);
        var destination = new TestSnapshotStore();
        anchor.saveReport(result, destination);
        assertThat(destination.baselines).isEmpty();
        assertThat(destination.reports.get("case")).isEqualTo(backing.reports.get("case"));
        assertThat(codec.readJson(destination.reports.get("case"), VerificationReport.class)).isEqualTo(result.report());
    }

    record Before(@Anchor("customer") String oldName) {}
    record After(@Anchor("customer") String renamed) {}
    private static class BackendUnavailable extends Exception {}
}
