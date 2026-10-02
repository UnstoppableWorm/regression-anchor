package io.github.unstoppableworm.anchor.execution;

import io.github.unstoppableworm.anchor.replay.ReplayAdapter;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.testsupport.TestSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.unstoppableworm.anchor.execution.ExecutionMode.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class VirtualThreadCaptureTest {
    private final TestSnapshotStore store = new TestSnapshotStore();
    private final RegressionAnchor anchor = new RegressionAnchor(new SnapshotCodec(), store);
    private final AtomicInteger backend = new AtomicInteger();
    private volatile boolean offline;

    @Test void existingVirtualExecutorNeedsNoTaskWrapperAndReplaysWithoutLiveIo() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RegressionAnchor.Trigger<Integer, List<Integer>> work = input -> {
                var result = new ArrayList<Integer>();
                for (int i = 0; i < 3; i++) result.add(executor.submit(() -> read(input)).get());
                return result;
            };
            assertThat(anchor.run(RECORD, "executor", 4, work)).containsExactly(8, 8, 8);
            offline = true;
            assertThat(anchor.run(REPLAY, "executor", 4, work)).containsExactly(8, 8, 8);
            assertThat(backend).hasValue(3);
            assertThat(anchor.loadSnapshot("executor").calls()).hasSize(3).allSatisfy(call -> {
                assertThat(call.parentSequence()).isNull();
                assertThat(call.callerPath()).isEmpty();
            });
        }
    }

    @Test void directVirtualThreadStartAndNestedChildrenInheritAutomatically() throws Exception {
        RegressionAnchor.Trigger<Integer, Integer> work = input -> {
            var outer = new FutureTask<>(() -> {
                var inner = new FutureTask<>(() -> read(input));
                Thread.startVirtualThread(inner).join();
                return inner.get();
            });
            Thread.startVirtualThread(outer).join();
            return outer.get();
        };
        anchor.run(RECORD, "nested", 3, work);
        offline = true;
        assertThat(anchor.run(REPLAY, "nested", 3, work)).isEqualTo(6);
        assertThat(backend).hasValue(1);
    }

    @Test void childBoundaryKeepsItsCapturedParentWithoutHoldingALockAcrossJoin() throws Exception {
        RegressionAnchor.Trigger<Integer, Integer> work = input -> capture("parent", input, null, () -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                return executor.submit(() -> read(input)).get();
            }
        });
        anchor.run(RECORD, "parent", 2, work);
        offline = true;
        anchor.run(REPLAY, "parent", 2, work);
        assertThat(anchor.loadSnapshot("parent").calls()).extracting(Snapshot.Call::parentSequence)
                .containsExactly(null, 1L);
        assertThat(backend).hasValue(1);
    }

    @Test void parallelCallsHaveUniqueSequencesAndNeverUseASiblingsParentStack() throws Exception {
        RegressionAnchor.Trigger<Integer, List<Integer>> work = input -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var release = new CountDownLatch(1);
                var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
                for (int i = 0; i < 64; i++) tasks.add(executor.submit(() -> { await(release); return read(input); }));
                release.countDown();
                var results = new ArrayList<Integer>();
                for (var task : tasks) results.add(task.get());
                return results;
            }
        };
        anchor.run(RECORD, "parallel", 1, work);
        offline = true;
        anchor.run(REPLAY, "parallel", 1, work);
        var calls = anchor.loadSnapshot("parallel").calls();
        assertThat(calls).hasSize(64).allMatch(call -> call.parentSequence() == null);
        assertThat(calls).extracting(Snapshot.Call::sequence).doesNotHaveDuplicates();
        assertThat(backend).hasValue(64);
    }

    @Test void allowsChangedSubmittersButRejectsReorderedExternalInputs() throws Exception {
        var work = new Branches();
        anchor.run(RECORD, "branches", 1, work::ab);
        var calls = anchor.loadSnapshot("branches").calls();
        assertThat(calls).extracting(call -> call.argumentsBefore().get(0).asInt()).containsExactly(1, 2);
        offline = true;
        anchor.run(REPLAY, "branches", 1, work::ab);
        anchor.run(REPLAY, "branches", 1, work::extracted);
        assertThatThrownBy(() -> anchor.run(REPLAY, "branches", 1, work::ba))
                .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("/calls/0");
        assertThat(backend).hasValue(2);
    }

    @Test void childMismatchCannotBeSwallowedByTheParent() throws Exception {
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        RegressionAnchor.Trigger<Integer, Integer> work = input -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try { return executor.submit(() -> read(changed.get() ? input + 1 : input)).get(); }
                catch (java.util.concurrent.ExecutionException ignored) { return input * 2; }
            }
        };
        anchor.run(RECORD, "swallowed", 1, work);
        offline = true; changed.set(true);
        assertThatThrownBy(() -> anchor.run(REPLAY, "swallowed", 1, work))
                .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("argumentsBefore");
        assertThat(backend).hasValue(1);
    }

    @Test void disabledInheritanceCannotReachTheBackendEvenWithPerCallReplayMode() throws Exception {
        var disabled = new java.util.concurrent.atomic.AtomicBoolean();
        RegressionAnchor.Trigger<Integer, Integer> work = input -> {
            try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                    .inheritInheritableThreadLocals(!disabled.get()).factory())) {
                try { return executor.submit(() -> read(input)).get(); }
                catch (java.util.concurrent.ExecutionException ignored) { return input * 2; }
            }
        };
        anchor.run(RECORD, "no-inheritance", 1, work);
        offline = true; disabled.set(true);
        assertThatThrownBy(() -> anchor.run(REPLAY, "no-inheritance", 1, work))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("context was not propagated");
        assertThat(backend).hasValue(1);
    }

    @Test void inheritedExpiredScopeRejectsLateIoAfterTheRootHasReturned() throws Exception {
        var thread = new AtomicReference<Thread>();
        var task = new AtomicReference<FutureTask<Integer>>();
        anchor.run(RECORD, "late", 1, input -> {
            var future = new FutureTask<>(() -> read(input));
            task.set(future);
            thread.set(Thread.ofVirtual().unstarted(future));
            return input;
        });
        thread.get().start(); thread.get().join();
        assertThatThrownBy(() -> task.get().get()).hasRootCauseMessage("Regression scope has already ended; dependency access is forbidden");
        assertThat(backend).hasValue(0);
    }

    @Test void unfinishedCapturedWorkCannotSaveAPartialBaseline() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var task = new AtomicReference<FutureTask<Integer>>();
        try {
            assertThatThrownBy(() -> anchor.run(RECORD, "unfinished", 1, input -> {
                var future = new FutureTask<>(() -> capture("blocked", input, null, () -> {
                    entered.countDown(); await(release); return input;
                }));
                task.set(future); Thread.startVirtualThread(future); await(entered);
                return input;
            })).hasMessageContaining("unfinished dependency calls");
            assertThat(store.exists("unfinished")).isFalse();
        } finally { release.countDown(); if (task.get() != null) task.get().get(5, TimeUnit.SECONDS); }
    }

    @Test void independentRootExecutionsAndTheirChildrenStayIsolated() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RegressionAnchor.Trigger<Integer, Integer> work = input -> {
                try (var children = Executors.newVirtualThreadPerTaskExecutor()) { return children.submit(() -> read(input)).get(); }
            };
            var first = executor.submit(() -> anchor.run(RECORD, "one", 1, work));
            var second = executor.submit(() -> anchor.run(RECORD, "two", 2, work));
            assertThat(first.get()).isEqualTo(2); assertThat(second.get()).isEqualTo(4);
            offline = true;
            assertThat(executor.submit(() -> anchor.run(REPLAY, "one", 1, work)).get()).isEqualTo(2);
            assertThat(executor.submit(() -> anchor.run(REPLAY, "two", 2, work)).get()).isEqualTo(4);
            assertThat(backend).hasValue(2);
        }
    }

    @Test void sequenceFollowsAdmissionEvenWhenCompletionOrderIsReversed() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var secondFinished = new CountDownLatch(1);
        anchor.run(RECORD, "completion", 1, input -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> capture("read", input,
                        (call, args, codec) -> call.outcome().value().asInt(), () -> {
                            firstEntered.countDown(); await(secondFinished); return 10;
                        }));
                await(firstEntered);
                var second = executor.submit(() -> capture("read", input + 1,
                        (call, args, codec) -> call.outcome().value().asInt(), () -> 20));
                int secondValue = second.get();
                secondFinished.countDown();
                return List.of(first.get(), secondValue);
            }
        });
        var calls = anchor.loadSnapshot("completion").calls();
        assertThat(calls).extracting(call -> call.argumentsBefore().get(0).asInt()).containsExactly(1, 2);
        assertThat(calls).extracting(call -> call.outcome().value().asInt()).containsExactly(10, 20);
        anchor.run(REPLAY, "completion", 1, input -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> read(input)).get();
                var second = executor.submit(() -> read(input + 1)).get();
                return List.of(first, second);
            }
        });
        assertThat(backend).hasValue(0);
    }

    private int read(int input) throws Exception {
        assertThat(Thread.currentThread().isVirtual()).isTrue();
        return capture("read", input, (call, args, codec) -> call.outcome().value().asInt(), () -> {
            if (offline) throw new AssertionError("Backend entered during replay");
            backend.incrementAndGet(); return input * 2;
        });
    }
    private <T> T capture(String operation, Object input, ReplayAdapter<T> adapter, RegressionAnchor.Invocation<T> action) throws Exception {
        try {
            return adapter == null ? anchor.capture("TEST", operation, new Object[]{input}, action)
                    : anchor.captureBoundary("TEST", operation, new Object[]{input}, adapter, action);
        } catch (Exception | Error e) { throw e; } catch (Throwable e) { throw new RuntimeException(e); }
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }
    private class Branches {
        int ab(int input) throws Exception { return a(input) + b(input); }
        int extracted(int input) throws Exception { return child(input) + child(input + 1); }
        int ba(int input) throws Exception { return b(input) + a(input); }
        private int a(int input) throws Exception { return child(input); }
        private int b(int input) throws Exception { return child(input + 1); }
        private int child(int input) throws Exception {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) { return executor.submit(() -> read(input)).get(); }
        }
    }
}
