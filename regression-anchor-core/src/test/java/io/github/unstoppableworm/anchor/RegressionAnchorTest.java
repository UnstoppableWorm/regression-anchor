package io.github.unstoppableworm.anchor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;

class RegressionAnchorTest {
    @TempDir Path directory;
    final SnapshotCodec codec = new SnapshotCodec();
    FileSnapshotStore store() { return new FileSnapshotStore(directory.resolve("baselines"), directory.resolve("reports"), codec); }
    RegressionAnchor anchor() { return new RegressionAnchor(codec, store()); }

    @Test void loadsTriggerAndRecordsEachRepeatedInvocationInOrder() throws Exception {
        var anchor = anchor();
        var input = List.of(2, 3, 2);
        anchor.record("repeated", input, values -> total(anchor, values));
        var result = anchor.verify("repeated", new TypeReference<List<Integer>>() {}, values -> total(anchor, values)).assertMatches();
        assertThat(result.value()).isEqualTo(14);
        assertThat(result.actual().calls()).extracting(Snapshot.Call::sequence).containsExactly(1L, 2L, 3L);
        assertThat(result.actual().calls()).extracting(c -> c.argumentsBefore().get(0).intValue()).containsExactly(2, 3, 2);
    }

    @Test void persistedNumbersAndRawJsonRemainStableAcrossReloads() throws Exception {
        var anchor = anchor();
        anchor.record("numeric", 1L, i -> call(anchor, "numbers", i,
                () -> new NumericResult(i, new java.math.BigDecimal("1234567890.1234567890123456789"), "{\"value\": 1}")));
        anchor.verify("numeric", Long.class, i -> call(anchor, "numbers", i,
                () -> new NumericResult(i, new java.math.BigDecimal("1234567890.1234567890123456789"), "{\"value\": 1}"))).assertMatches();
    }
    record NumericResult(long id, java.math.BigDecimal amount, @com.fasterxml.jackson.annotation.JsonRawValue String raw) {}

    @Test void detectsInputOutputAndOrderDriftEvenWithUnchangedFinalValue() throws Exception {
        var anchor = anchor();
        anchor.record("flow", List.of(1, 2), values -> { total(anchor, values); return 0; });
        var changed = anchor.verify("flow", new TypeReference<List<Integer>>() {}, values -> { total(anchor, List.of(2, 1)); return 0; });
        assertThat(changed.matches()).isFalse();
        assertThat(changed.differences()).anyMatch(d -> d.path().equals("/calls/0/argumentsBefore/0"))
                .anyMatch(d -> d.path().equals("/calls/0/outcome/value"));
        assertThatThrownBy(changed::assertMatches).isInstanceOf(AssertionError.class).hasMessageContaining("/calls/0");
    }

    @Test void detectsMissingAndExtraCalls() throws Exception {
        var anchor = anchor();
        anchor.record("count", 1, i -> { call(anchor, "select", i, () -> i); return 0; });
        assertThat(anchor.verify("count", Integer.class, i -> 0).differences()).anyMatch(d -> d.path().equals("/calls/0"));
        assertThat(anchor.verify("count", Integer.class, i -> { total(anchor, List.of(i, i)); return 0; }).differences())
                .anyMatch(d -> d.path().equals("/calls/1"));
    }

    @Test void keepsParentsAndFreezesBeforeAndAfterMutation() throws Exception {
        var anchor = anchor();
        anchor.record("mutation", 1, i -> call(anchor, "parent", i, () -> {
            var mutable = new ArrayList<>(List.of(i));
            return call(anchor, "save", mutable, () -> { mutable.add(9); return mutable; });
        }));
        var calls = store().load("mutation").calls();
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
        anchor.verify("failure", Integer.class, i -> call(anchor, "broken", i, () -> {
            throw new IllegalStateException("boom", new IllegalArgumentException("cause"));
        })).assertMatches();
        assertThat(anchor.verify("failure", Integer.class, i -> call(anchor, "broken", i, () -> {
            throw new IllegalStateException("different");
        })).matches()).isFalse();
    }

    @Test void neverOverwritesBaselineAndWritesDiagnosticReports() throws Exception {
        var anchor = anchor();
        anchor.record("stable", 5, i -> i);
        byte[] original = Files.readAllBytes(directory.resolve("baselines/stable.json"));
        assertThatThrownBy(() -> anchor.record("stable", 7, i -> i)).isInstanceOf(IllegalStateException.class);
        anchor.verify("stable", Integer.class, i -> i + 1);
        assertThat(Files.readAllBytes(directory.resolve("baselines/stable.json"))).isEqualTo(original);
        assertThat(directory.resolve("reports/stable.diff.json")).exists();
        assertThatThrownBy(() -> anchor.verify("unknown", Integer.class, i -> i)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> anchor.record("../escape", 1, i -> i)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void comparesNullVoidAndCanonicalObjectValues() throws Exception {
        var anchor = anchor();
        anchor.record("null", "x", input -> call(anchor, "null", input, () -> null));
        anchor.verify("null", String.class, input -> call(anchor, "null", input, () -> null)).assertMatches();
        assertThat(anchor.verify("null", String.class, input -> call(anchor, "null", input, () -> "x")).matches()).isFalse();
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
        assertThat(store().load("thread-a").calls()).hasSize(2);
        assertThat(store().load("thread-b").calls()).hasSize(2);
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
        anchor.verify("ignored", Integer.class, i -> { call(anchor, "select", i, () -> 200); return 0; }).assertMatches();
        assertThat(anchor.verify("ignored", Integer.class, i -> { call(anchor, "select", i + 1, () -> 200); return 0; }).matches()).isFalse();
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
