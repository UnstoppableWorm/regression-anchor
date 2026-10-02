package io.github.unstoppableworm.anchor.spring.autoconfigure;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.ExecutionMode;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.spring.http.HttpAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ExecutionModeProfileTest {
    private final TestSnapshotStore store = new TestSnapshotStore();
    private final AtomicInteger calls = new AtomicInteger();

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, HttpAnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, () -> store)
                .withBean(Gateway.class, () -> new Gateway(calls))
                .withBean(RestTemplate.class, () -> new RestTemplate((uri, method) -> {
                    calls.incrementAndGet(); throw new AssertionError("HTTP backend reached");
                }));
    }

    @Test void recordsAndReplaysAcrossSeparateContextsUsingTheSameEntryPoint() {
        runner("regression", "regression-record", "staging").run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var gateway = context.getBean(Gateway.class);
            assertThat(anchor.mode()).isEqualTo(ExecutionMode.RECORD);
            int result = anchor.run("case", "key", key -> gateway.get(key) + gateway.get(key));
            assertThat(result).isEqualTo(3);
            assertThat(calls).hasValue(2);
            assertThatThrownBy(() -> anchor.replay("case", String.class, gateway::get)).hasMessageContaining("RECORD mode");
            assertThatThrownBy(() -> anchor.run(ExecutionMode.REPLAY, "case", "key", gateway::get)).hasMessageContaining("RECORD mode");
        });
        runner("regression", "regression-replay").run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var gateway = context.getBean(Gateway.class);
            assertThat(anchor.mode()).isEqualTo(ExecutionMode.REPLAY);
            int result = anchor.run("case", "key", key -> gateway.get(key) + gateway.get(key));
            assertThat(result).isEqualTo(3);
            assertThat(calls).hasValue(2);
            assertThatThrownBy(() -> anchor.record("other", "key", gateway::get)).hasMessageContaining("REPLAY mode");
            assertThatThrownBy(() -> anchor.run(ExecutionMode.RECORD, "other", "key", gateway::get)).hasMessageContaining("REPLAY mode");
            assertThatThrownBy(() -> gateway.get("key")).hasMessageContaining("active replay scope");
            assertThatThrownBy(() -> context.getBean(RestTemplate.class).getForObject("https://unused.test", String.class))
                    .hasMessageContaining("active replay scope");
            assertThat(calls).hasValue(2);
        });
    }

    @Test void replayRejectsInitialAndBoundaryInputDriftAndMissingBaselinesWithoutFallback() {
        runner("regression", "regression-record").run(context -> {
            context.getBean(RegressionAnchor.class).run("case", "key", context.getBean(Gateway.class)::get);
        });
        runner("regression", "regression-replay").run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var gateway = context.getBean(Gateway.class);
            var triggered = new AtomicInteger();
            assertThatThrownBy(() -> anchor.run("case", "different", key -> { triggered.incrementAndGet(); return gateway.get(key); }))
                    .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("/input");
            assertThat(triggered).hasValue(0);
            assertThatThrownBy(() -> anchor.run("missing", "key", gateway::get)).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> anchor.run("case", "key", key -> gateway.get("different")))
                    .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("argumentsBefore");
            assertThat(calls).hasValue(1);
        });
    }

    @Test void selectsModePerInvocationWithOnlyTheRegressionProfile() {
        runner("regression").run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var gateway = context.getBean(Gateway.class);
            assertThat(anchor.mode()).isEqualTo(ExecutionMode.MANUAL);
            assertThatThrownBy(() -> anchor.run("case", "key", gateway::get)).hasMessageContaining("run(mode");
            assertThat(calls).hasValue(0);
            int recorded = anchor.run(ExecutionMode.RECORD, "case", "key", key -> gateway.get(key) + gateway.get(key));
            int replayed = anchor.run(ExecutionMode.REPLAY, "case", "key", key -> gateway.get(key) + gateway.get(key));
            assertThat(replayed).isEqualTo(recorded);
            assertThat(calls).hasValue(2);
            assertThat(anchor.mode()).isEqualTo(ExecutionMode.MANUAL);
        });
    }

    @Test void rejectsConflictingModesBeforeApplicationSingletonsStart() {
        var started = new AtomicInteger();
        runner("regression", "regression-record", "regression-replay")
                .withBean("sideEffect", Object.class, () -> { started.incrementAndGet(); return new Object(); })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("cannot be active together");
                });
        assertThat(started).hasValue(0);
    }

    @Test void modeProfilesAloneCannotEnableTheStarter() {
        for (String mode : new String[]{"regression-record", "regression-replay"}) {
            runner(mode).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(RegressionAnchor.class);
                assertThat(context.getBean(Gateway.class).get("key")).isPositive();
            });
        }
    }

    static class Gateway {
        private final AtomicInteger calls;
        Gateway(AtomicInteger calls) { this.calls = calls; }
        @Anchor public int get(String key) { return calls.incrementAndGet(); }
    }
}
