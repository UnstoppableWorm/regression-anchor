package io.github.unstoppableworm.anchor.execution;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.verification.SnapshotComparator;
import io.github.unstoppableworm.anchor.verification.VerificationReport;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;

class RegressionAnchorTest {
    final SnapshotCodec codec = new SnapshotCodec();
    final TestSnapshotStore store = new TestSnapshotStore();
    TestSnapshotStore store() { return store; }
    RegressionAnchor anchor() { return new RegressionAnchor(codec, store()); }

    @Test void loadsTriggerAndRecordsEachRepeatedInvocationInOrder() throws Exception {
        var anchor = anchor();
        var input = List.of(2, 3, 2);
        anchor.record("repeated", input, values -> total(anchor, values));
        var result = anchor.replay("repeated", new TypeReference<List<Integer>>() {}, values -> total(anchor, values)).assertMatches();
        assertThat(result.value()).isEqualTo(14);
        assertThat(result.actual().calls()).extracting(Snapshot.Call::sequence).containsExactly(1L, 2L, 3L);
        assertThat(result.actual().calls()).extracting(c -> c.argumentsBefore().get(0).intValue()).containsExactly(2, 3, 2);
    }

    @Test void persistedNumbersAndRawJsonRemainStableAcrossReloads() throws Exception {
        var anchor = anchor();
        anchor.record("numeric", 1L, i -> call(anchor, "numbers", i,
                () -> new NumericResult(i, new java.math.BigDecimal("1234567890.1234567890123456789"), "{\"value\": 1}")));
        anchor.replay("numeric", Long.class, i -> call(anchor, "numbers", i,
                () -> new NumericResult(i, new java.math.BigDecimal("1234567890.1234567890123456789"), "{\"value\": 1}"))).assertMatches();
    }
    record NumericResult(long id, java.math.BigDecimal amount, @com.fasterxml.jackson.annotation.JsonRawValue String raw) {}

    @Test void rejectsInputAndOrderDriftEvenWithUnchangedFinalValue() throws Exception {
        var anchor = anchor();
        anchor.record("flow", List.of(1, 2), values -> { total(anchor, values); return 0; });
        assertThatThrownBy(() -> anchor.replay("flow", new TypeReference<List<Integer>>() {}, values -> {
            total(anchor, List.of(2, 1)); return 0;
        })).isInstanceOf(io.github.unstoppableworm.anchor.replay.ReplayMismatchException.class)
                .hasMessageContaining("/calls/0/argumentsBefore");
    }

    @Test void detectsMissingAndExtraCalls() throws Exception {
        var anchor = anchor();
        anchor.record("count", 1, i -> { call(anchor, "select", i, () -> i); return 0; });
        assertThatThrownBy(() -> anchor.replay("count", Integer.class, i -> 0))
                .hasMessageContaining("missing calls");
        assertThatThrownBy(() -> anchor.replay("count", Integer.class, i -> {
            call(anchor, "select", i, () -> i); call(anchor, "select", i, () -> i); return 0;
        })).hasMessageContaining("extra call");
    }

    @Test void keepsParentsAndFreezesBeforeAndAfterMutation() throws Exception {
        var anchor = anchor();
        anchor.record("mutation", 1, i -> call(anchor, "parent", i, () -> {
            var mutable = new ArrayList<>(List.of(i));
            return call(anchor, "save", mutable, () -> { mutable.add(9); return mutable; });
        }));
        var calls = anchor.loadSnapshot("mutation").calls();
        assertThat(calls.get(0).parentSequence()).isNull();
        assertThat(calls.get(1).parentSequence()).isEqualTo(1L);
        assertThat(calls.get(1).argumentsBefore().toString()).isEqualTo("[[1]]");
        assertThat(calls.get(1).argumentsAfter().toString()).isEqualTo("[[1,9]]");
    }

    @Test void recordsExceptionsAndComparesTypeAndMessage() throws Exception {
        var anchor = anchor();
        assertThatThrownBy(() -> anchor.record("failure", 1, i -> call(anchor, "broken", i, () -> {
            throw new IllegalStateException("boom", new IllegalArgumentException("cause"));
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(anchor.isActive()).isFalse();
        anchor.replay("failure", Integer.class, i -> call(anchor, "broken", i, () -> {
            throw new IllegalStateException("boom", new IllegalArgumentException("cause"));
        })).assertMatches();
        assertThat(anchor.replay("failure", Integer.class, i -> call(anchor, "broken", i, () -> {
            throw new IllegalStateException("different");
        })).matches()).isFalse();
    }

    @Test void neverOverwritesBaselineAndWritesDiagnosticReports() throws Exception {
        var anchor = anchor();
        anchor.record("stable", 5, i -> i);
        byte[] original = store.baselines.get("stable");
        assertThatThrownBy(() -> anchor.record("stable", 7, i -> i)).isInstanceOf(IllegalStateException.class);
        anchor.replay("stable", Integer.class, i -> i + 1);
        assertThat(store.baselines.get("stable")).isEqualTo(original);
        assertThat(codec.readJson(store.reports.get("stable"), VerificationReport.class).differences()).isNotEmpty();
        assertThatThrownBy(() -> anchor.replay("unknown", Integer.class, i -> i)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test void comparesNullVoidAndCanonicalObjectValues() throws Exception {
        var anchor = anchor();
        anchor.record("null", "x", input -> call(anchor, "null", input, () -> null));
        anchor.replay("null", String.class, input -> call(anchor, "null", input, () -> null)).assertMatches();
        assertThat(anchor.replay("null", String.class, input -> call(anchor, "null", input, () -> "x")).matches()).isFalse();
    }

    @Test void scopesAreIsolatedAcrossConcurrentThreads() throws Exception {
        var anchor = anchor();
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> anchor.record("thread-a", 1, i -> { ready.countDown(); ready.await(); return total(anchor, List.of(i, i)); }));
            var b = executor.submit(() -> anchor.record("thread-b", 2, i -> { ready.countDown(); ready.await(); return total(anchor, List.of(i, i)); }));
            assertThat(a.get()).isEqualTo(4);
            assertThat(b.get()).isEqualTo(8);
        }
        assertThat(anchor.loadSnapshot("thread-a").calls()).hasSize(2);
        assertThat(anchor.loadSnapshot("thread-b").calls()).hasSize(2);
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void captureErrorsCannotBeSwallowedToProducePassingBaseline() {
        var anchor = anchor();
        assertThatThrownBy(() -> anchor.record("bad", 1, i -> {
            try { call(anchor, "unsupported", new Object(), () -> i); } catch (Exception ignored) {}
            return i;
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot snapshot");
        assertThat(store().exists("bad")).isFalse();
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void onlyExplicitIgnoredPathsAreExcluded() throws Exception {
        var anchor = new RegressionAnchor(codec, store(), new SnapshotComparator(Set.of("/calls/*/outcome/value")));
        anchor.record("ignored", 1, i -> { call(anchor, "select", i, () -> 100); return 0; });
        anchor.replay("ignored", Integer.class, i -> { call(anchor, "select", i, () -> 200); return 0; }).assertMatches();
        assertThatThrownBy(() -> anchor.replay("ignored", Integer.class, i -> {
            call(anchor, "select", i + 1, () -> 200); return 0;
        })).hasMessageContaining("Replay input differs");
    }

    @Test void rejectsDeferredResultsAndCleansUpAfterErrors() {
        var anchor = anchor();
        assertThatThrownBy(() -> anchor.record("future", 1, i -> java.util.concurrent.CompletableFuture.completedFuture(i)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Deferred");
        assertThatThrownBy(() -> anchor.record("error", 1, i -> { throw new AssertionError("fatal"); })).isInstanceOf(AssertionError.class);
        assertThat(anchor.isActive()).isFalse();
    }

    private int total(RegressionAnchor anchor, List<Integer> values) throws Exception {
        int total = 0;
        for (Integer i : values) total += call(anchor, "select", i, () -> i * 2);
        return total;
    }

    private <T> T call(RegressionAnchor anchor, String operation, Object argument, RegressionAnchor.Invocation<T> invocation) throws Exception {
        try { return anchor.capture("TEST", operation, new Object[]{argument}, invocation); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable e) { throw new RuntimeException(e); }
    }
}
