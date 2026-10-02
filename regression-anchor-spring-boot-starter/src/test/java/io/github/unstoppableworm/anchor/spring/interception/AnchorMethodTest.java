package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorProperties;
import io.github.unstoppableworm.anchor.spring.http.AnchorHttpInterceptor;
import io.github.unstoppableworm.anchor.spring.testsupport.ManualEngineConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnchorMethodTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("regression", "regression-record")).withUserConfiguration(ManualEngineConfiguration.class).withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, TestSnapshotStore::new);
    }

    @Test void recordsRepeatedCustomInfrastructureCallsAndReplaysWithoutEnteringTheSdk() {
        var sdk = new Sdk();
        runner().withBean(Sdk.class, () -> sdk).withBean(Service.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var service = context.getBean(Service.class);
            anchor.record("custom-infra", "bucket", service::twice);
            assertThat(sdk.calls).isEqualTo(2);
            sdk.offline = true;
            var result = anchor.replay("custom-infra", String.class, service::twice).assertMatches();
            assertThat(sdk.calls).isEqualTo(2);
            assertThat(result.value()).containsExactly(new Reply("bucket", 1), new Reply("bucket", 2));
            assertThat(result.actual().calls()).extracting(Snapshot.Call::kind).containsExactly("METHOD", "METHOD");
            assertThat(result.actual().calls()).allMatch(call -> call.parentSequence() == null);
            assertThat(context.getBean(Sdk.class).localHelper()).isEqualTo("local");
        });
    }

    @Test void methodAnchorLeavesUnannotatedMethodsLocal() {
        var gateway = new MixedGateway();
        runner().withBean(MixedGateway.class, () -> gateway).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(MixedGateway.class);
            anchor.record("mixed", 1, i -> bean.remote(i) + bean.local(i));
            gateway.offline = true;
            var result = anchor.replay("mixed", Integer.class, i -> bean.remote(i) + bean.local(i)).assertMatches();
            assertThat(result.actual().calls()).extracting(Snapshot.Call::kind).containsExactly("METHOD");
            assertThat(gateway.localRuns).isEqualTo(2);
        });
    }

    @Test void recognizesGenericInterfaceAnnotationOnAnExistingJdkProxyBeforeItsOtherAdvice() {
        var target = new GenericSdk();
        var adviceRuns = new AtomicInteger();
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            if (target.offline) throw new AssertionError("Existing infrastructure advice executed in replay");
            adviceRuns.incrementAndGet(); return invocation.proceed();
        });
        GenericPort<?> proxy = (GenericPort<?>) factory.getProxy();
        runner().withBean("genericSdk", GenericPort.class, () -> proxy).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(GenericPort.class);
            anchor.record("interface", "a", bean::read);
            target.offline = true;
            var result = anchor.replay("interface", String.class, bean::read).assertMatches();
            assertThat(result.value()).isEqualTo(new Reply("a", 1));
            assertThat(adviceRuns).hasValue(1);
            assertThat(result.actual().calls()).singleElement().satisfies(call -> assertThat(call.kind()).isEqualTo("METHOD"));
        });
    }

    @Test void findsImplementationOnlyAndInheritedAnnotations() {
        runner().withBean(ImplementationOnly.class).withBean(InheritedSdk.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var implementation = context.getBean(ImplementationOnly.class);
            var inherited = context.getBean(InheritedSdk.class);
            anchor.record("inherited", "a", i -> List.of(implementation.read(i), inherited.read(i)));
            var result = anchor.replay("inherited", String.class, i -> List.of(implementation.read(i), inherited.read(i))).assertMatches();
            assertThat(result.actual().calls()).extracting(Snapshot.Call::kind).containsExactly("METHOD", "METHOD");
        });
    }

    @Test void supportsFinalImplementationsExposedThroughAnInterface() {
        runner().withBean("finalSdk", PlainPort.class, FinalSdk::new).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(PlainPort.class);
            anchor.record("final-interface", "a", bean::read);
            anchor.replay("final-interface", String.class, bean::read).assertMatches();
        });
    }

    @Test void recordsVoidMutationsAndExceptionsAndRestoresThemWithoutInfrastructure() {
        var sdk = new MutationSdk();
        runner().withBean(MutationSdk.class, () -> sdk).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(MutationSdk.class);
            RegressionAnchor.Trigger<Integer,Long> work = i -> {
                var request = new MutableRequest();
                bean.save(request);
                try { bean.fail(); } catch (IOException expected) { return request.id; }
                throw new AssertionError("expected IOException");
            };
            anchor.record("mutation", 1, work);
            sdk.offline = true;
            var result = anchor.replay("mutation", Integer.class, work).assertMatches();
            assertThat(result.value()).isEqualTo(42);
            assertThat(result.actual().calls()).hasSize(2);
            assertThat(result.actual().calls().getFirst().argumentsAfter().at("/0/id").asLong()).isEqualTo(42);
        });
    }

    @Test void treatsNestedHttpImplementationAsOneBoundaryAndRestoresInstrumentationAfterward() {
        runner().run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var template = new RestTemplate();
            template.getInterceptors().add(new AnchorHttpInterceptor(anchor, new AnchorProperties()));
            var server = MockRestServiceServer.bindTo(template).build();
            var target = new HttpSdk(template);
            // Register through the existing post processor, as a real consumer bean would be.
            var bean = (HttpSdk) context.getAutowireCapableBeanFactory().initializeBean(target, "httpSdk");
            server.expect(requestTo("https://example.test/sdk")).andRespond(withSuccess("hello", MediaType.TEXT_PLAIN));
            anchor.record("opaque", 1, i -> bean.read() + bean.after());
            server.verify(); server.reset();
            var result = anchor.replay("opaque", Integer.class, i -> bean.read() + bean.after()).assertMatches();
            assertThat(result.actual().calls()).extracting(Snapshot.Call::kind).containsExactly("METHOD", "METHOD");
            assertThat(result.value()).isEqualTo("hello!");
        });
    }

    @Test void changedInputsFailWithoutExecutingTheMethodEvenIfApplicationCatchesIt() {
        var sdk = new Sdk();
        runner().withBean(Sdk.class, () -> sdk).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(Sdk.class);
            anchor.record("strict", "a", bean::read);
            sdk.offline = true;
            assertThatThrownBy(() -> anchor.replay("strict", String.class, i -> {
                try { return bean.read("b"); } catch (IllegalStateException ignored) { return new Reply("a", 1); }
            })).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("argumentsBefore");
            assertThat(sdk.calls).isEqualTo(1);
        });
    }

    @Test void rejectsMethodsThatTheSelectedProxyCannotIntercept() {
        runner().withBean(PrivateSdk.class).run(context -> assertThat(context).hasFailed());
        runner().withBean(StaticSdk.class).run(context -> assertThat(context).hasFailed());
        runner().withBean(FinalMethodSdk.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("@Anchor").hasStackTraceContaining("final");
        });
    }

    @Test void opaqueMethodsCannotOpenNestedScopesAndFailureDoesNotSuppressTheNextCall() {
        runner().withBean(NestingSdk.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var bean = context.getBean(NestingSdk.class);
            assertThatThrownBy(() -> anchor.record("outer", 1, bean::nested)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Nested");
            assertThat(context.getBean(SnapshotStore.class).exists("inner")).isFalse();
            assertThat(anchor.isActive()).isFalse();
            anchor.record("next", 1, bean::after);
            assertThat(anchor.loadSnapshot("next").calls()).hasSize(1);
        });
    }

    @Test void disabledInstrumentationLeavesAnnotatedMethodsAlone() {
        runner().withPropertyValues("regression-anchor.enabled=false").withBean(Sdk.class).run(context -> {
            assertThat(context).doesNotHaveBean(RegressionAnchor.class);
            assertThat(context.getBean(Sdk.class).read("a")).isEqualTo(new Reply("a", 1));
        });
    }

    static class Sdk {
        boolean offline; int calls;
        @Anchor public Reply read(String key) { if (offline) throw new AssertionError("SDK called"); return new Reply(key, ++calls); }
        public String localHelper() { return "local"; }
    }
    static class Service {
        private final Sdk sdk;
        Service(Sdk sdk) { this.sdk = sdk; }
        public List<Reply> twice(String input) { return List.of(sdk.read(input), sdk.read(input)); }
    }
    record Reply(String key, int revision) {}
    static class MixedGateway {
        boolean offline; int localRuns;
        @Anchor public int remote(int i) { if (offline) throw new AssertionError("SDK called"); return i; }
        public int local(int i) { localRuns++; return i; }
    }
    interface GenericPort<T> { @Anchor T read(String key); }
    static class GenericSdk implements GenericPort<Reply> {
        boolean offline;
        public Reply read(String key) { if (offline) throw new AssertionError("SDK called"); return new Reply(key, 1); }
    }
    interface PlainPort { String read(String key); }
    static class ImplementationOnly implements PlainPort { @Anchor public String read(String key) { return key; } }
    static class ParentSdk { @Anchor public String read(String key) { return key; } }
    static class InheritedSdk extends ParentSdk { @Override public String read(String key) { return super.read(key); } }
    static final class FinalSdk implements PlainPort { @Anchor public String read(String key) { return key; } }
    static class MutableRequest { public Long id; }
    static class MutationSdk {
        boolean offline;
        @Anchor public void save(MutableRequest request) { if (offline) throw new AssertionError("SDK called"); request.id = 42L; }
        @Anchor public void fail() throws IOException { if (offline) throw new AssertionError("SDK called"); throw new IOException("remote failed"); }
    }
    static class HttpSdk {
        private final RestTemplate client;
        HttpSdk(RestTemplate client) { this.client = client; }
        @Anchor public String read() { return client.getForObject("https://example.test/sdk", String.class); }
        @Anchor public String after() { return "!"; }
    }
    static class PrivateSdk { @Anchor private String read() { return "x"; } }
    static class StaticSdk { @Anchor public static String read() { return "x"; } }
    static class FinalMethodSdk { @Anchor public final String read() { return "x"; } }
    static class NestingSdk {
        private final RegressionAnchor anchor;
        NestingSdk(RegressionAnchor anchor) { this.anchor = anchor; }
        @Anchor public int nested(int i) throws Exception { return anchor.record("inner", i, value -> value); }
        @Anchor public int after(int i) { return i; }
    }
}
