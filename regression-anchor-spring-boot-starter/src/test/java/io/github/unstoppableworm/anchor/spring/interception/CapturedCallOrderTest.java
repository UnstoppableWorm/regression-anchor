package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import io.github.unstoppableworm.anchor.verification.Difference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 상위 업무 메서드를 계측하지 않아도 캡처된 동일 메서드의 순번별 입출력을 검증한다. */
class CapturedCallOrderTest {
    private ApplicationContextRunner runner(Gateway gateway) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("regression"))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, TestSnapshotStore::new)
                .withBean(Gateway.class, () -> gateway)
                .withBean(Work.class);
    }

    @Test void replaysRootLevelCallsAfterExtractingAHelper() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("refactored", 1, work::rootCalls);
            gateway.offline = true;

            var result = anchor.replay("refactored", Integer.class, work::refactored).assertMatches();
            assertThat(result.value()).containsExactly("A", "B", "C");
            assertThat(result.actual().calls()).extracting(Snapshot.Call::sequence).containsExactly(1L, 2L, 3L);
            assertThat(gateway.calls).isEqualTo(3);
        });
    }

    @Test void detectsReorderedBusinessResultsDuringReplayWithoutCallingInfrastructure() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("swapped", 1, work::original);
            gateway.offline = true;

            var result = anchor.replay("swapped", Integer.class, work::broken);
            assertThat(result.value()).containsExactly("B", "A", "C");
            assertThat(result.actual().calls()).extracting(call -> List.of(call.operation(), call.argumentsBefore(), call.outcome()))
                    .isEqualTo(anchor.loadSnapshot("swapped").calls().stream()
                            .map(call -> List.of(call.operation(), call.argumentsBefore(), call.outcome())).toList());
            assertThat(result.differences()).extracting(Difference::path)
                    .containsExactly("/outcome/value/0", "/outcome/value/1");
            assertThatThrownBy(result::assertMatches).isInstanceOf(AssertionError.class);
            assertThat(gateway.calls).isEqualTo(3);
        });
    }

    /** 실제 외부 응답은 동일 입력에도 각 호출에서 A, B, C로 달라질 수 있다. */
    static class Gateway {
        List<String> responses = List.of("A", "B", "C");
        int calls;
        boolean offline;
        @Anchor public String method1(int input) {
            if (offline) throw new AssertionError("External dependency was called during replay");
            return responses.get(calls++);
        }
    }

    /** a/b/c와 리팩터링된 위임 메서드에는 계측용 어노테이션이 없다. */
    static class Work {
        private final Gateway gateway;
        Work(Gateway gateway) { this.gateway = gateway; }
        public List<String> original(int input) { return List.of(a(input), b(input), c(input)); }
        public List<String> rootCalls(int input) { return List.of(gateway.method1(input), gateway.method1(input), gateway.method1(input)); }
        public List<String> refactored(int input) {
            var results = new ArrayList<String>();
            for (int i = 0; i < 3; i++) results.add(delegated(input));
            return results;
        }
        public List<String> broken(int input) {
            var results = new ArrayList<>(original(input));
            Collections.swap(results, 0, 1);
            return results;
        }
        private String a(int input) { return gateway.method1(input); }
        private String b(int input) { return gateway.method1(input); }
        private String c(int input) { return gateway.method1(input); }
        private String delegated(int input) { return gateway.method1(input); }
    }
}
