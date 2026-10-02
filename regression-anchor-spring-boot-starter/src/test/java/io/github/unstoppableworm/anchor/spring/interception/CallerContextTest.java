package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 업무 경로 변경을 허용하되 외부 호출 순서·인자와 최종 결과의 차이를 검출한다. */
class CallerContextTest {
    private ApplicationContextRunner runner(Gateway gateway) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("regression"))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, TestSnapshotStore::new)
                .withBean(Gateway.class, () -> gateway).withBean(Work.class);
    }

    @Test void detectsReassignedResultsInTheFinalMapEvenWhenExternalRequestsAreIdentical() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            assertThat(AopUtils.isAopProxy(work)).isFalse();
            anchor.record("routes", 1, work::abc);
            var baseline = anchor.loadSnapshot("routes");
            assertThat(baseline.formatVersion()).isEqualTo(3);
            gateway.offline = true;
            anchor.replay("routes", Integer.class, work::abc).assertMatches();
            var changed = anchor.replay("routes", Integer.class, work::bac);
            assertThat(changed.differences()).extracting(difference -> difference.path())
                    .containsExactlyInAnyOrder("/outcome/value/a", "/outcome/value/b");
            assertThatThrownBy(changed::assertMatches).isInstanceOf(AssertionError.class);
            assertThat(gateway.calls).isEqualTo(3);
        });
    }

    @Test void repeatedIdenticalRequestsReplayInOrderAndChangedResultOwnershipIsCompared() {
        var gateway = new Gateway();
        gateway.reset("A1", "A2", "B1", "B2");
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("repeat", 1, work::repeatedAB);
            gateway.offline = true;
            var result = anchor.replay("repeat", Integer.class, work::repeatedAB).assertMatches();
            assertThat(result.value()).containsExactlyInAnyOrderEntriesOf(Map.of("a", List.of("A1", "A2"), "b", List.of("B1", "B2")));
            var changed = anchor.replay("repeat", Integer.class, work::repeatedBA);
            assertThat(changed.differences()).extracting(difference -> difference.path())
                    .containsExactlyInAnyOrder("/outcome/value/a/0", "/outcome/value/a/1",
                            "/outcome/value/b/0", "/outcome/value/b/1");
            assertThat(gateway.calls).isEqualTo(4);
        });
    }

    @Test void allowsRenamingAnInternalCallerWithIdenticalExternalRequestsAndResults() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("unknown", 1, work::abc);
            gateway.offline = true;
            anchor.replay("unknown", Integer.class, work::abd).assertMatches();
            assertThat(gateway.calls).isEqualTo(3);
        });
    }

    @Test void rejectsReorderedExternalMethodsWithinTheSameCaller() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("operations", 1, input -> work.twoOperations(input, false));
            gateway.offline = true;
            anchor.replay("operations", Integer.class, input -> work.twoOperations(input, false)).assertMatches();
            assertThatThrownBy(() -> anchor.replay("operations", Integer.class, input -> work.twoOperations(input, true)))
                    .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("/calls/0");
            assertThat(gateway.calls).isEqualTo(1);
        });
    }

    @Test void reorderedArgumentsInTheSameMethodFailEvenWhenBusinessCodeCatchesTheMismatch() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("inputs", 1, input -> work.inputOrder(false));
            gateway.offline = true;
            anchor.replay("inputs", Integer.class, input -> work.inputOrder(false)).assertMatches();
            assertThatThrownBy(() -> anchor.replay("inputs", Integer.class, input -> {
                try { return work.inputOrder(true); }
                catch (ReplayMismatchException ignored) { return List.of("A", "B"); }
            })).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("/calls/0/argumentsBefore");
            assertThat(gateway.calls).isEqualTo(2);
        });
    }

    @Test void invalidSequenceFailsBeforeExecutingBusinessCode() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("corrupt", 1, work::abc);
            var tree = anchor.codec().freeze(anchor.loadSnapshot("corrupt"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("calls").get(0)).put("sequence", 9);
            ((TestSnapshotStore) context.getBean(SnapshotStore.class)).baselines.put("corrupt", anchor.codec().writeJson(tree));
            var executed = new java.util.concurrent.atomic.AtomicBoolean();
            assertThatThrownBy(() -> anchor.replay("corrupt", Integer.class, input -> {
                executed.set(true); return work.abc(input);
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("call sequence");
            assertThat(executed).isFalse();
            assertThat(gateway.calls).isEqualTo(3);
        });
    }

    @Test void rejectsMovingAnExternalOperationPastAnotherCapturedCall() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("moved", 1, input -> work.optionalDependency(input, false));
            gateway.offline = true;
            assertThatThrownBy(() -> anchor.replay("moved", Integer.class, input -> work.optionalDependency(input, true)))
                    .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("identity/order");
        });
    }

    @Test void allowsReplacingASingleInternalCaller() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            anchor.record("single", 1, work::onlyA);
            gateway.offline = true;
            anchor.replay("single", Integer.class, work::onlyB).assertMatches();
            assertThat(gateway.calls).isEqualTo(1);
        });
    }

    @Test void movedClassAndExtractedOrInlinedHelpersKeepTheSameExternalContract() {
        var gateway = new Gateway();
        runner(gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var work = context.getBean(Work.class);
            var moved = new MovedWork(context.getBean(Gateway.class));
            anchor.record("move", 1, work::abc);
            gateway.offline = true;
            anchor.replay("move", Integer.class, moved::quote).assertMatches();
            anchor.replay("move", Integer.class, input -> Map.of("a", moved.gateway.method1(input),
                    "b", moved.gateway.method1(input), "c", moved.gateway.method1(input))).assertMatches();
            assertThat(gateway.calls).isEqualTo(3);
        });
    }

    /** 같은 외부 빈을 사용하는 새 업무 클래스이며 중간 메서드의 이름과 인자 타입도 다르다. */
    static class MovedWork {
        final Gateway gateway;
        MovedWork(Gateway gateway) { this.gateway = gateway; }
        Map<String, String> quote(int input) {
            return Map.of("a", fetch(Integer.toString(input)), "b", fetch(Integer.toString(input)),
                    "c", fetch(Integer.toString(input)));
        }
        private String fetch(String input) { return gateway.method1(Integer.parseInt(input)); }
    }

    /** 동일 인자를 받는 외부 메서드를 흉내 내며 replay에서 실제 실행되면 바로 실패한다. */
    static class Gateway {
        List<String> responses = List.of("A", "B", "C");
        int calls;
        boolean offline;
        void reset(String... values) { responses = List.of(values); calls = 0; }
        @Anchor public String method1(int input) {
            if (offline) throw new AssertionError("External call during replay");
            return responses.get(calls++);
        }
        @Anchor public String method2(int input) {
            if (offline) throw new AssertionError("External call during replay");
            return "X";
        }
    }

    /** 상위 진입점·private 분기·공통 위임 메서드 모두 별도 관찰 어노테이션이 없는 업무 코드다. */
    static class Work {
        private final Gateway gateway;
        Work(Gateway gateway) { this.gateway = gateway; }
        public Map<String, String> abc(int input) {
            var result = new LinkedHashMap<String, String>();
            result.put("a", a(input)); result.put("b", b(input)); result.put("c", c(input)); return result;
        }
        public Map<String, String> bac(int input) {
            var result = new LinkedHashMap<String, String>();
            result.put("b", b(input)); result.put("a", a(input)); result.put("c", c(input)); return result;
        }
        public Map<String, String> abd(int input) { return Map.of("a", a(input), "b", b(input), "c", d(input)); }
        public String onlyA(int input) { return a(input); }
        public String onlyB(int input) { return b(input); }
        public Map<String, List<String>> repeatedAB(int input) { return Map.of("a", twiceA(input), "b", twiceB(input)); }
        public Map<String, List<String>> repeatedBA(int input) { return Map.of("b", twiceB(input), "a", twiceA(input)); }
        public Map<String, String> twoOperations(int input, boolean reverse) {
            var result = new LinkedHashMap<String, String>();
            if (reverse) { result.put("second", gateway.method2(input)); result.put("first", gateway.method1(input)); }
            else { result.put("first", gateway.method1(input)); result.put("second", gateway.method2(input)); }
            return result;
        }
        public List<String> inputOrder(boolean reverse) {
            var result = new java.util.ArrayList<String>();
            for (int value : reverse ? List.of(2, 1) : List.of(1, 2)) result.add(gateway.method1(value));
            return result;
        }
        public int optionalDependency(int input, boolean moveToB) {
            optionalA(input, !moveToB); optionalB(input, moveToB); return 0;
        }
        private void optionalA(int input, boolean second) { shared(input); if (second) gateway.method2(input); }
        private void optionalB(int input, boolean second) { shared(input); if (second) gateway.method2(input); }
        private List<String> twiceA(int input) { return List.of(a(input), a(input)); }
        private List<String> twiceB(int input) { return List.of(b(input), b(input)); }
        private String a(int input) { return shared(input); }
        private String b(int input) { return shared(input); }
        private String c(int input) { return shared(input); }
        private String d(int input) { return shared(input); }
        private String shared(int input) { return gateway.method1(input); }
    }
}
