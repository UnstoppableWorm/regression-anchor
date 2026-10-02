package io.github.unstoppableworm.anchor.execution;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.replay.ReplayAdapter;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.replay.ReplayValues;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.verification.SnapshotComparator;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ReplayTest {
    final SnapshotCodec codec = new SnapshotCodec();
    final TestSnapshotStore store = new TestSnapshotStore();
    final Cache cache = new Cache();
    RegressionAnchor anchor() { return new RegressionAnchor(codec, store, new SnapshotComparator(), List.of(cache)); }

    @Test void replaysCacheMissesAndHitsAndRestoresAmbientState() throws Exception {
        var anchor = anchor();
        var reads = new AtomicInteger();
        anchor.record("cache", List.of("A", "B", "A"), input -> sum(anchor, input, reads));
        Snapshot baseline = anchor.loadSnapshot("cache");
        assertThat(baseline.sharedInputs().get("prices").isEmpty()).isTrue();
        assertThat(baseline.sharedOutputs().get("prices").toString()).isEqualTo("{\"A\":10,\"B\":20}");
        assertThat(baseline.calls()).hasSize(2);
        cache.values.clear(); cache.values.put("unrelated", 99);
        reads.set(0);
        var result = anchor.replay("cache", new TypeReference<List<String>>() {}, input -> sum(anchor, input, reads)).assertMatches();
        assertThat(result.value()).isEqualTo(40);
        assertThat(reads).hasValue(0);
        assertThat(cache.values).containsExactlyEntriesOf(Map.of("unrelated", 99));
    }

    @Test void comparesSharedOutputsEvenWhenReturnValueIsUnchanged() throws Exception {
        var anchor = anchor();
        anchor.record("output", 1, i -> { cache.values.put("A", 10); return 0; });
        cache.values.put("ambient", 99);
        var ambient = Map.copyOf(cache.values);
        var result = anchor.replay("output", Integer.class, i -> { cache.values.put("A", 11); return 0; });
        assertThat(result.differences()).singleElement().satisfies(d -> assertThat(d.path()).isEqualTo("/sharedOutputs/prices/A"));
        assertThat(cache.values).isEqualTo(ambient);
    }

    @Test void replayRestoresRecordedSharedInputsBeforeReadingAWarmCache() throws Exception {
        var anchor = anchor(); cache.values.put("A", 10);
        anchor.record("warm", "A", cache.values::get);
        cache.values.clear(); cache.values.put("A", 500);
        anchor.replay("warm", String.class, cache.values::get).assertMatches();
        assertThat(cache.values.get("A")).isEqualTo(500);
    }

    @Test void repeatedBoundariesKeepOrderAndLocalParentsStillExecute() throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        var localRuns = new AtomicInteger();
        RegressionAnchor.Trigger<Integer,Integer> work = input -> local(anchor, "service", input, () -> {
            localRuns.incrementAndGet();
            int first = boundary(anchor, "read", new Object[]{input}, ReplayValues.returning(Integer.class), () -> 10);
            int second = boundary(anchor, "read", new Object[]{input}, ReplayValues.returning(Integer.class), () -> 20);
            return first + second;
        });
        anchor.record("repeat", 1, work);
        anchor.replay("repeat", Integer.class, work).assertMatches();
        assertThat(localRuns).hasValue(2);
        assertThat(anchor.loadSnapshot("repeat").calls()).extracting(Snapshot.Call::parentSequence).containsExactly(null, 1L, 1L);
    }

    @Test void wrongInputExtraAndMissingCallsFailWithoutFallbackEvenIfCaught() throws Exception {
        var anchor = anchor();
        anchor.record("strict", 1, i -> boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 10));
        cache.values.put("ambient", 99);
        assertThatThrownBy(() -> anchor.replay("strict", Integer.class, i -> {
            try { boundary(anchor, "read", new Object[]{i + 1}, ReplayValues.returning(Integer.class), () -> { fail("live fallback"); return 10; }); }
            catch (IllegalStateException ignored) {}
            return 10;
        })).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("/calls/0/argumentsBefore");
        assertThatThrownBy(() -> anchor.replay("strict", Integer.class, i -> 10)).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("missing calls");
        assertThatThrownBy(() -> anchor.replay("strict", Integer.class, i -> {
            boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> { fail("live fallback"); return 10; });
            return boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> { fail("live fallback"); return 10; });
        })).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("extra call");
        assertThat(cache.values).containsExactlyEntriesOf(Map.of("ambient", 99));
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void operationDriftAndNestedBoundaryCannotBeSilentlySkipped() throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        anchor.record("operation", 1, i -> boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 10));
        assertThatThrownBy(() -> anchor.replay("operation", Integer.class, i -> boundary(anchor, "delete", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 0)))
                .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("identity/order");
        anchor.record("nested", 1, i -> boundary(anchor, "outer", new Object[]{i}, ReplayValues.returning(Integer.class),
                () -> boundary(anchor, "inner", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 10)));
        assertThatThrownBy(() -> anchor.replay("nested", Integer.class, i -> boundary(anchor, "outer", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 0)))
                .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("nested recorded calls");
    }

    @Test void restoresGeneratedKeysIntoExistingArgumentsAcrossFieldRename() throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        anchor.record("mutation", 1, i -> {
            var row = new BeforeRow();
            boundary(anchor, "insert", new Object[]{row}, ReplayValues.returning(Integer.class), () -> { row.id = 7L; return 1; });
            return row.id;
        });
        anchor.replay("mutation", Integer.class, i -> {
            var row = new AfterRow();
            boundary(anchor, "insert", new Object[]{row}, ReplayValues.returning(Integer.class), () -> { fail("must not insert"); return 1; });
            return row.databaseKey;
        }).assertMatches();
    }
    static class BeforeRow { @Anchor("id") public Long id; }
    static class AfterRow { @Anchor("id") public Long databaseKey; }

    @Test void restoresExceptionTypeAndCauseSoCatchBranchesStillRun() throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        RegressionAnchor.Trigger<Integer,Integer> work = i -> {
            try { return boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> {
                if (anchor.isReplaying()) fail("must not run dependency");
                throw new IllegalArgumentException("invalid", new IllegalStateException("reason"));
            }); }
            catch (IllegalArgumentException e) { assertThat(e.getCause()).isInstanceOf(IllegalStateException.class); return -1; }
        };
        anchor.record("failure", 1, work);
        anchor.replay("failure", Integer.class, work).assertMatches();
    }

    @Test void lossyAdapterCannotProduceAPassingReplay() throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        anchor.record("loss", 1, i -> boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 10));
        assertThatThrownBy(() -> anchor.replay("loss", Integer.class, i -> {
            try { return boundary(anchor, "read", new Object[]{i}, (call,args,c) -> 20, () -> 10); }
            catch (IllegalStateException e) { return 10; }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("reconstruction differs");
    }

    @Test void sharedProviderMismatchFailsBeforeRunningAndDuplicateKeysAreRejected() throws Exception {
        var anchor = anchor(); anchor.record("state", 1, i -> i);
        var without = new RegressionAnchor(codec, store);
        assertThatThrownBy(() -> without.replay("state", Integer.class, i -> { fail("must not run"); return i; }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("providers");
        assertThatThrownBy(() -> new RegressionAnchor(codec, store, new SnapshotComparator(), List.of(cache, new Cache())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
    }

    @Test void restoresAmbientStateAfterBusinessErrorAndPartialSetupFailure() throws Exception {
        var anchor = anchor(); anchor.record("cleanup", 1, i -> i);
        cache.values.put("ambient", 99);
        assertThatThrownBy(() -> anchor.replay("cleanup", Integer.class, i -> { cache.values.clear(); throw new AssertionError("business"); }))
                .isInstanceOf(AssertionError.class).hasMessage("business");
        assertThat(cache.values).containsExactlyEntriesOf(Map.of("ambient", 99));
        cache.failOnEmptyRestore = true;
        assertThatThrownBy(() -> anchor.replay("cleanup", Integer.class, i -> { fail("setup failed"); return i; }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("restore failed");
        assertThat(cache.values).containsExactlyEntriesOf(Map.of("ambient", 99));
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void sharedStateRestorationUsesStableAnchorsAcrossRenames() throws Exception {
        var original = new java.util.concurrent.atomic.AtomicReference<>(new BeforeValue(10));
        SharedState<BeforeValue> before = new SharedState<>() {
            public String key() { return "config"; }
            public TypeReference<BeforeValue> type() { return new TypeReference<>() {}; }
            public BeforeValue capture() { return original.get(); }
            public void restore(BeforeValue value) { original.set(value); }
        };
        new RegressionAnchor(codec, store, new SnapshotComparator(), List.of(before))
                .record("state-rename", 1, i -> original.get().total());
        var renamed = new java.util.concurrent.atomic.AtomicReference<>(new AfterValue(999));
        SharedState<AfterValue> after = new SharedState<>() {
            public String key() { return "config"; }
            public TypeReference<AfterValue> type() { return new TypeReference<>() {}; }
            public AfterValue capture() { return renamed.get(); }
            public void restore(AfterValue value) { renamed.set(value); }
        };
        new RegressionAnchor(codec, store, new SnapshotComparator(), List.of(after))
                .replay("state-rename", Integer.class, i -> renamed.get().payableAmount()).assertMatches();
        assertThat(renamed.get().payableAmount()).isEqualTo(999);
    }
    record BeforeValue(@Anchor("total") int total) {}
    record AfterValue(@Anchor("total") int payableAmount) {}

    @Test void cleanupFailureCannotAppearAsAPassingReplay() throws Exception {
        var anchor = anchor();
        anchor.record("cleanup-error", 1, i -> i);
        cache.failOnEmptyRestore = false;
        assertThatThrownBy(() -> anchor.replay("cleanup-error", Integer.class, i -> {
            cache.failOnEmptyRestore = true;
            return i;
        })).isInstanceOf(IllegalStateException.class).hasMessage("restore failed");
        assertThat(anchor.isActive()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void snapshotsWithoutCallerPathsReplayWithoutBeingRewritten(int version) throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        anchor.record("legacy", 2, i -> boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 4));
        Snapshot current = anchor.loadSnapshot("legacy");
        var tree = codec.freeze(current).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("formatVersion", version);
        if (version == 1) ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove(List.of("sharedInputs", "sharedOutputs"));
        tree.path("calls").forEach(call -> ((com.fasterxml.jackson.databind.node.ObjectNode) call).remove("callerPath"));
        store.baselines.put("legacy", codec.mapper().writeValueAsBytes(tree));
        byte[] before = store.baselines.get("legacy");
        anchor.replay("legacy", Integer.class, i -> boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> { fail("dependency called"); return 0; })).assertMatches();
        assertThat(store.baselines.get("legacy")).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void oldCallPathsAndChangedParentsDoNotRequireRerecording(int version) throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        anchor.record("structure", 1, input -> local(anchor, "local", input, () -> {
            boundary(anchor, "read", new Object[]{input}, ReplayValues.returning(Integer.class), () -> 10);
            return 0;
        }));
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) codec.freeze(anchor.loadSnapshot("structure"));
        tree.put("formatVersion", version);
        for (var call : tree.path("calls")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) call;
            if (version == 3) node.putArray("callerPath").add("legacy.OldService#oldHelper(I)I");
            else node.remove("callerPath");
        }
        byte[] original = codec.writeJson(tree);
        store.baselines.put("structure", original);
        var result = anchor.replay("structure", Integer.class, input -> {
            local(anchor, "local", input, () -> 0);
            boundary(anchor, "read", new Object[]{input}, ReplayValues.returning(Integer.class), () -> {
                fail("live fallback"); return 10;
            });
            return 0;
        }).assertMatches();
        assertThat(anchor.loadSnapshot("structure").calls().get(1).parentSequence()).isEqualTo(1L);
        assertThat(result.actual().calls().get(1).parentSequence()).isNull();
        assertThat(store.baselines.get("structure")).isEqualTo(original);
    }

    @Test void kindDriftFailsWithoutCallingTheDependencyEvenIfCaught() throws Exception {
        var anchor = new RegressionAnchor(codec, store);
        anchor.record("kind", 1, i -> boundary(anchor, "read", new Object[]{i}, ReplayValues.returning(Integer.class), () -> 10));
        var replayBackendCalls = new AtomicInteger();
        assertThatThrownBy(() -> anchor.replay("kind", Integer.class, i -> {
            try {
                anchor.captureBoundary("OTHER", "read", new Object[]{i}, ReplayValues.returning(Integer.class),
                        () -> { replayBackendCalls.incrementAndGet(); return 10; });
            } catch (Throwable ignored) { }
            return 10;
        })).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("identity/order");
        assertThat(replayBackendCalls).hasValue(0);
    }

    private int sum(RegressionAnchor anchor, List<String> input, AtomicInteger reads) throws Exception {
        int sum = 0;
        for (String key : input) {
            Integer value = cache.values.get(key);
            if (value == null) {
                value = boundary(anchor, "price", new Object[]{key}, ReplayValues.returning(Integer.class), () -> { reads.incrementAndGet(); return key.equals("A") ? 10 : 20; });
                cache.values.put(key, value);
            }
            sum += value;
        }
        return sum;
    }

    static class Cache implements SharedState<Map<String,Integer>> {
        final Map<String,Integer> values = new LinkedHashMap<>();
        boolean failOnEmptyRestore;
        @Override public String key() { return "prices"; }
        @Override public TypeReference<Map<String,Integer>> type() { return new TypeReference<>() {}; }
        @Override public Map<String,Integer> capture() { return values; }
        @Override public void restore(Map<String,Integer> value) {
            values.clear();
            if (failOnEmptyRestore && value.isEmpty()) throw new IllegalStateException("restore failed");
            values.putAll(value);
        }
    }
    private <T> T boundary(RegressionAnchor anchor, String operation, Object[] args, ReplayAdapter<T> adapter, RegressionAnchor.Invocation<T> invoke) throws Exception {
        try { return anchor.captureBoundary("TEST", operation, args, adapter, invoke); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable e) { throw new RuntimeException(e); }
    }
    private <T> T local(RegressionAnchor anchor, String operation, Object arg, RegressionAnchor.Invocation<T> invoke) throws Exception {
        try { return anchor.capture("LOCAL", operation, new Object[]{arg}, invoke); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable e) { throw new RuntimeException(e); }
    }
}
