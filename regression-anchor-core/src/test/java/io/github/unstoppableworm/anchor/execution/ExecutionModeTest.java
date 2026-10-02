package io.github.unstoppableworm.anchor.execution;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import io.github.unstoppableworm.anchor.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.verification.SnapshotComparator;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ExecutionModeTest {
    private final SnapshotCodec codec = new SnapshotCodec();
    private final TestSnapshotStore store = new TestSnapshotStore();
    private RegressionAnchor engine(ExecutionMode mode) {
        return new RegressionAnchor(codec, store, new SnapshotComparator(), List.of(), mode);
    }

    @Test void explicitModeRejectsMissingSelectionBeforeAnyWork() {
        var anchor = engine(ExecutionMode.MANUAL);
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> anchor.run("case", 1, input -> calls.incrementAndGet()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("run(mode");
        for (ExecutionMode mode : new ExecutionMode[]{null, ExecutionMode.MANUAL}) {
            assertThatThrownBy(() -> anchor.run(mode, "case", 1, input -> calls.incrementAndGet()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RECORD or REPLAY");
        }
        assertThat(calls).hasValue(0);
        assertThat(store.baselines).isEmpty();
    }

    @Test void explicitModeCannotOverrideAFixedEngineBeforeStorageAccess() {
        SnapshotStore inaccessible = new SnapshotStore() {
            public byte[] load(String id) { throw new AssertionError("Store accessed"); }
            public boolean exists(String id) { throw new AssertionError("Store accessed"); }
            public void create(String caseId, byte[] snapshotJson) { throw new AssertionError("Store accessed"); }
        };
        for (ExecutionMode pinned : List.of(ExecutionMode.RECORD, ExecutionMode.REPLAY)) {
            var anchor = new RegressionAnchor(codec, inaccessible, new SnapshotComparator(), List.of(), pinned);
            var opposite = pinned == ExecutionMode.RECORD ? ExecutionMode.REPLAY : ExecutionMode.RECORD;
            assertThatThrownBy(() -> anchor.run(opposite, "case", 1, input -> { throw new AssertionError("Body executed"); }))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(pinned + " mode");
        }
    }

    @Test void simultaneousRecordAndReplayDoNotChangeEachOthersMode() throws Exception {
        var anchor = engine(ExecutionMode.MANUAL);
        anchor.run(ExecutionMode.RECORD, "baseline", 2, input -> input * 2);
        var entered = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var record = executor.submit(() -> anchor.run(ExecutionMode.RECORD, "new", 3, input -> {
                entered.countDown();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(anchor.isReplaying()).isFalse();
                return input * 2;
            }));
            var replay = executor.submit(() -> anchor.run(ExecutionMode.REPLAY, "baseline", 2, input -> {
                entered.countDown();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(anchor.isReplaying()).isTrue();
                return input * 2;
            }));
            assertThat(record.get(10, TimeUnit.SECONDS)).isEqualTo(6);
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(4);
        }
        assertThat(anchor.mode()).isEqualTo(ExecutionMode.MANUAL);
        assertThat(anchor.isActive()).isFalse();
        assertThat(store.baselines).containsOnlyKeys("baseline", "new");
    }

    @Test void runPreservesTheRecordedRootException() {
        var record = engine(ExecutionMode.RECORD);
        assertThatThrownBy(() -> record.run("failure", 1, input -> { throw new IOException("business failure"); }))
                .isInstanceOf(IOException.class).hasMessage("business failure");
        var replay = engine(ExecutionMode.REPLAY);
        assertThatThrownBy(() -> replay.run("failure", 1, input -> { throw new IOException("business failure"); }))
                .isInstanceOf(IOException.class).hasMessage("business failure");
    }

    @Test void runDoesNotReturnTheBaselineWhenBusinessLogicChanges() throws Exception {
        engine(ExecutionMode.RECORD).run("logic", 2, input -> input * 2);
        assertThatThrownBy(() -> engine(ExecutionMode.REPLAY).run("logic", 2, input -> input * 3))
                .isInstanceOf(AssertionError.class);
    }

    @Test void replayMatchesInitialInputByAnchorIncludingNull() throws Exception {
        engine(ExecutionMode.RECORD).run("rename", new Before(5), input -> input.amount());
        int result = engine(ExecutionMode.REPLAY).run("rename", new After(5), input -> input.renamed());
        assertThat(result).isEqualTo(5);
        engine(ExecutionMode.RECORD).run("null", null, input -> "ok");
        String replayed = engine(ExecutionMode.REPLAY).run("null", null, input -> "ok");
        assertThat(replayed).isEqualTo("ok");
    }

    @Test void unscopedCoreCaptureCannotReachInfrastructureInReplayMode() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> engine(ExecutionMode.REPLAY).capture("METHOD", "dependency", new Object[0], calls::incrementAndGet))
                .hasMessageContaining("active replay scope");
        assertThat(calls).hasValue(0);
    }

    record Before(@Anchor("value") int amount) {}
    record After(@Anchor("value") int renamed) {}
}
