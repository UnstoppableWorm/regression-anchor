package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class VirtualThreadInterceptionTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("regression"))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, TestSnapshotStore::new)
                .withBean(Sdk.class).withBean(InnerSdk.class);
    }

    @Test void springAsyncUsingVirtualThreadsAutomaticallyCapturesAndRestoresMutationsAndFailures() {
        runner().withUserConfiguration(AsyncConfiguration.class).withBean(AsyncService.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var service = context.getBean(AsyncService.class);
            var target = (Sdk) BoundaryPostProcessor.target(context.getBean(Sdk.class));
            RegressionAnchor.Trigger<Integer, Integer> work = input -> service.run(input).get(5, TimeUnit.SECONDS);
            assertThat(anchor.record("async", 2, work)).isEqualTo(42);
            target.offline = true;
            assertThat(anchor.replay("async", Integer.class, work).assertMatches().value()).isEqualTo(42);
            assertThat(anchor.loadSnapshot("async").calls()).hasSize(2).allMatch(call -> call.kind().equals("METHOD"));
            assertThat(target.calls).hasValue(2);
        });
    }

    @Test void opaqueGatewayAlsoSuppressesItsInternalCallsOnAChildVirtualThread() {
        runner().withBean(OuterSdk.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var outer = context.getBean(OuterSdk.class);
            var inner = (InnerSdk) BoundaryPostProcessor.target(context.getBean(InnerSdk.class));
            anchor.record("opaque-child", 3, outer::read);
            inner.offline = true;
            assertThat(anchor.replay("opaque-child", Integer.class, outer::read).assertMatches().value()).isEqualTo(6);
            assertThat(anchor.loadSnapshot("opaque-child").calls()).hasSize(1);
            assertThat(inner.calls).hasValue(1);
        });
    }

    @Test void suppressionInOneVirtualThreadDoesNotHideItsSiblingCalls() {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        runner().withBean(BlockingSdk.class, () -> new BlockingSdk(started, release)).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var blocking = context.getBean(BlockingSdk.class);
            var inner = context.getBean(InnerSdk.class);
            anchor.record("siblings", 1, input -> {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = executor.submit(() -> blocking.read(input));
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        var second = executor.submit(() -> inner.read(input));
                        return List.of(second.get(5, TimeUnit.SECONDS), releaseAndGet(release, first));
                    } finally { release.countDown(); }
                }
            });
            var calls = anchor.loadSnapshot("siblings").calls();
            assertThat(calls).hasSize(2).allMatch(call -> call.parentSequence() == null);
            assertThat(calls).extracting(call -> call.operation()).anyMatch(name -> name.contains("BlockingSdk#read"))
                    .anyMatch(name -> name.contains("InnerSdk#read"));
        });
    }

    private static int releaseAndGet(CountDownLatch release, java.util.concurrent.Future<Integer> value) throws Exception {
        release.countDown(); return value.get(5, TimeUnit.SECONDS);
    }
    @Configuration(proxyBeanMethods = false) @EnableAsync
    static class AsyncConfiguration {
        @Bean SimpleAsyncTaskExecutor taskExecutor() {
            var executor = new SimpleAsyncTaskExecutor("virtual-anchor-");
            executor.setVirtualThreads(true);
            return executor;
        }
    }
    static class AsyncService {
        private final Sdk sdk;
        AsyncService(Sdk sdk) { this.sdk = sdk; }
        @Async public CompletableFuture<Integer> run(int input) throws Exception {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            var request = new Mutable(); sdk.save(request);
            try { sdk.fail(); } catch (java.io.IOException expected) { return CompletableFuture.completedFuture(request.id); }
            throw new AssertionError("Expected exception");
        }
    }
    static class Mutable { public int id; }
    static class Sdk {
        boolean offline; final AtomicInteger calls = new AtomicInteger();
        @Anchor public void save(Mutable value) { access(); value.id = 42; }
        @Anchor public void fail() throws java.io.IOException { access(); throw new java.io.IOException("remote failure"); }
        private void access() { if (offline) throw new AssertionError("SDK entered during replay"); calls.incrementAndGet(); }
    }
    static class InnerSdk {
        boolean offline; final AtomicInteger calls = new AtomicInteger();
        @Anchor public int read(int input) {
            if (offline) throw new AssertionError("Inner SDK entered during replay");
            calls.incrementAndGet(); return input * 2;
        }
    }
    static class OuterSdk {
        private final InnerSdk inner;
        OuterSdk(InnerSdk inner) { this.inner = inner; }
        @Anchor public int read(int input) throws Exception {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) { return executor.submit(() -> inner.read(input)).get(); }
        }
    }
    static class BlockingSdk {
        private final CountDownLatch entered, release;
        BlockingSdk(CountDownLatch entered, CountDownLatch release) { this.entered = entered; this.release = release; }
        @Anchor public int read(int input) throws Exception {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return input;
        }
    }
}
