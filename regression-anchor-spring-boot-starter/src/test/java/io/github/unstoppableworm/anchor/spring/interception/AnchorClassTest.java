package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.ManualEngineConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AnchorClassTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("regression"))
                .withUserConfiguration(ManualEngineConfiguration.class)
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, TestSnapshotStore::new);
    }

    @Test void capturesAllPublicMethodsIncludingInheritedOnesWithoutDuplicateMethodMarkers() {
        var sdk = new ClassSdk();
        runner().withBean(ClassSdk.class, () -> sdk).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(ClassSdk.class);
            RegressionAnchor.Trigger<String, List<String>> work = input -> {
                assertThat(bean.toString()).isEqualTo("local metadata");
                bean.hashCode(); bean.equals(bean);
                return List.of(bean.read(input), bean.write(input), bean.inherited(input));
            };
            anchor.record("class", "a", work);
            assertThat(sdk.calls).isEqualTo(3);
            sdk.offline = true;
            var replay = anchor.replay("class", String.class, work).assertMatches();
            assertThat(sdk.calls).isEqualTo(3);
            assertThat(replay.value()).containsExactly("read:a", "write:a", "inherited:a");
            assertThat(replay.actual().calls()).extracting(Snapshot.Call::kind).containsExactly("METHOD", "METHOD", "METHOD");
            assertThat(ClassSdk.localStatic()).isEqualTo("static");
        });
    }

    @Test void inheritsClassMarkerWithoutRepeatingItOnTheSubclass() {
        runner().withBean(ChildSdk.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(ChildSdk.class);
            anchor.record("subclass", "a", input -> List.of(bean.read(input), bean.extra(input)));
            assertThat(anchor.replay("subclass", String.class, input -> List.of(bean.read(input), bean.extra(input)))
                    .assertMatches().actual().calls()).hasSize(2);
        });
    }

    @Test void supportsAnAnnotatedGenericInterfaceOnAnExistingJdkProxy() {
        var sdk = new InterfaceSdk();
        var adviceRuns = new AtomicInteger();
        var factory = new ProxyFactory(sdk);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            if (sdk.offline) throw new AssertionError("infrastructure advice must not execute");
            adviceRuns.incrementAndGet(); return invocation.proceed();
        });
        var proxy = (Port<?>) factory.getProxy();
        runner().withBean("sdk", Port.class, () -> proxy).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(Port.class);
            anchor.record("interface", "a", input -> List.of(bean.read(input), bean.label()));
            sdk.offline = true;
            assertThat(anchor.replay("interface", String.class, input -> List.of(bean.read(input), bean.label()))
                    .assertMatches().actual().calls()).hasSize(2);
            assertThat(adviceRuns).hasValue(2);
        });
    }

    @Test void supportsFinalClassesThroughAnInterfaceButRejectsUnexposedPublicMethods() {
        runner().withBean("sdk", PlainPort.class, FinalSdk::new).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(PlainPort.class);
            assertThat(AopUtils.isJdkDynamicProxy(bean)).isTrue();
            anchor.record("final", "a", bean::read);
            anchor.replay("final", String.class, bean::read).assertMatches();
        });
        runner().withBean(UnexposedSdk.class).run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("not exposed through the bean's proxy interfaces"));
        runner().withBean(FinalMethodSdk.class).run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("cannot intercept a final class/method"));
    }

    @Test void rejectsWhitespaceKeysOnClassAndMethodMarkers() {
        runner().withBean(NamedClass.class).run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("must not be blank"));
        runner().withBean(NamedMethod.class).run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("must not be blank"));
    }

    @Test void fieldAndRecordComponentMarkersDoNotSelectBeansForCapture() {
        runner().withBean(PropertyOnly.class).withBean(KeyedRecord.class, () -> new KeyedRecord("a")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isAopProxy(context.getBean(PropertyOnly.class))).isFalse();
            assertThat(AopUtils.isAopProxy(context.getBean(KeyedRecord.class))).isFalse();
            var codec = context.getBean(RegressionAnchor.class).codec();
            assertThat(codec.freeze(context.getBean(KeyedRecord.class)).toString()).isEqualTo("{\"id\":\"a\"}");
        });
    }

    @Test void recordComponentKeyDoesNotReplaceTheClassAnchoredAccessorIdentity() {
        var record = new RecordBoundary("a");
        var backendCalls = new AtomicInteger();
        var factory = new ProxyFactory(record);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            backendCalls.incrementAndGet();
            return invocation.proceed();
        });
        var proxy = (RecordPort) factory.getProxy();
        runner().withBean("recordBoundary", RecordPort.class, () -> proxy).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(RecordPort.class);
            assertThat(anchor.codec().freeze(record).toString()).isEqualTo("{\"id\":\"a\"}");
            anchor.record("record-boundary", 1, input -> bean.renamed());
            var result = anchor.replay("record-boundary", Integer.class, input -> bean.renamed()).assertMatches();
            assertThat(result.value()).isEqualTo("a");
            assertThat(result.actual().calls()).singleElement().satisfies(call -> {
                assertThat(call.kind()).isEqualTo("METHOD");
                assertThat(call.operation()).isEqualTo("anchor:record-store#renamed()");
            });
            assertThat(backendCalls).hasValue(1);
        });
    }

    @Test void changedArgumentsFailWithoutEnteringAClassAnchoredDependency() {
        var sdk = new ClassSdk();
        runner().withBean(ClassSdk.class, () -> sdk).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(ClassSdk.class);
            anchor.record("changed", "a", bean::read);
            sdk.offline = true;
            assertThatThrownBy(() -> anchor.replay("changed", String.class, input -> bean.read("b")))
                    .isInstanceOf(ReplayMismatchException.class);
            assertThat(sdk.calls).isEqualTo(1);
        });
    }

    @Test void productionAndDisabledConfigurationsLeaveTheClassUntouched() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class))
                .withBean(ClassSdk.class).run(context -> {
                    assertThat(context).doesNotHaveBean(RegressionAnchor.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(ClassSdk.class))).isFalse();
                    assertThat(context.getBean(ClassSdk.class).read("a")).isEqualTo("read:a");
                });
        runner().withPropertyValues("regression-anchor.enabled=false").withBean(ClassSdk.class)
                .run(context -> assertThat(AopUtils.isAopProxy(context.getBean(ClassSdk.class))).isFalse());
    }

    static class ParentSdk {
        int calls; boolean offline;
        public String inherited(String value) { return invoke("inherited:", value); }
        protected String invoke(String prefix, String value) {
            if (offline) throw new AssertionError("SDK entered during replay");
            calls++; return prefix + value;
        }
    }
    @Anchor static class ClassSdk extends ParentSdk {
        public String read(String value) { return helper(value); }
        private String helper(String value) { return invoke("read:", value); }
        @Anchor public String write(String value) { return invoke("write:", value); }
        public static String localStatic() { return "static"; }
        @Override public String toString() { return "local metadata"; }
    }
    static class ChildSdk extends ClassSdk { public String extra(String value) { return invoke("extra:", value); } }
    @Anchor interface Port<T> { T read(String value); default String label() { return "label"; } }
    static class InterfaceSdk implements Port<String> {
        boolean offline;
        public String read(String value) { if (offline) throw new AssertionError("SDK entered"); return value; }
    }
    interface PlainPort { String read(String value); }
    @Anchor static final class FinalSdk implements PlainPort { public String read(String value) { return value; } }
    @Anchor static final class UnexposedSdk implements PlainPort {
        public String read(String value) { return value; }
        public String extra() { return "extra"; }
    }
    @Anchor static class FinalMethodSdk { public final String read() { return "x"; } }
    @Anchor(" ") static class NamedClass { public String read() { return "x"; } }
    static class NamedMethod { @Anchor(" ") public String read() { return "x"; } }
    static class PropertyOnly { @Anchor("id") public String renamed = "a"; }
    record KeyedRecord(@Anchor("id") String renamed) {}
    interface RecordPort { String renamed(); }
    @Anchor("record-store") record RecordBoundary(@Anchor("id") String renamed) implements RecordPort {}
}
