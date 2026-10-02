package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayAdapter;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MethodReplayTest {
    @Test void restoresInheritedGenericReturnTypesWithoutCallingTheGateway() throws Exception {
        var codec = new SnapshotCodec();
        var anchor = new RegressionAnchor(codec, new TestSnapshotStore());
        var method = Gateway.class.getMethod("find");
        var gateway = new People();
        ReplayAdapter<List<Person>> adapter = (call, args, c) -> {
            @SuppressWarnings("unchecked") var value = (List<Person>) MethodReplay.restore(gateway, method, call, args, c, MethodReplay::declaredReturnType);
            return value;
        };
        anchor.record("generic", 1, i -> invoke(anchor, new Object[0], adapter, gateway::find));
        var result = anchor.replay("generic", Integer.class, i -> invoke(anchor, new Object[0], adapter,
                () -> { fail("gateway executed"); return null; })).assertMatches();
        assertThat(result.value()).containsExactly(new Person("Ada"));
    }

    @Test void restoresChangedGenericCollectionElementsAndRemovalsInPlace() throws Exception {
        var codec = new SnapshotCodec();
        var anchor = new RegressionAnchor(codec, new TestSnapshotStore());
        var method = People.class.getMethod("replace", List.class);
        var gateway = new People();
        ReplayAdapter<Object> adapter = (call, args, c) -> MethodReplay.restore(gateway, method, call, args, c, MethodReplay::declaredReturnType);
        RegressionAnchor.Trigger<List<Person>, List<Person>> work = input -> {
            invoke(anchor, new Object[]{input}, adapter, () -> {
                if (anchor.isReplaying()) fail("gateway executed");
                gateway.replace(input); return null;
            });
            return input;
        };
        anchor.record("mutation", new ArrayList<>(List.of(new Person("Old"), new Person("Remove"))), work);
        var result = anchor.replay("mutation", new TypeReference<List<Person>>() {}, work).assertMatches();
        assertThat(result.value()).containsExactly(new Person("New"));
    }

    @Test void usesAnInjectedReturnTypeResolverForAnArgumentDefinedTypeWithoutKnowingTheTechnology() throws Exception {
        var codec = new SnapshotCodec();
        var anchor = new RegressionAnchor(codec, new TestSnapshotStore());
        var method = DynamicGateway.class.getMethod("read", Class.class);
        var resolved = new java.util.concurrent.atomic.AtomicInteger();
        MethodReturnTypeResolver types = (target, invoked, arguments) -> {
            assertThat(invoked).isEqualTo(method);
            resolved.incrementAndGet();
            return org.springframework.core.ResolvableType.forClass((Class<?>) arguments[0]);
        };
        ReplayAdapter<Object> adapter = (call, args, c) -> MethodReplay.restore(new DynamicGateway(), method, call, args, c, types);
        anchor.record("dynamic", 1, i -> invoke(anchor, new Object[]{Person.class}, adapter, () -> new Person("Ada")));
        assertThat(resolved).hasValue(0);
        var replayed = anchor.replay("dynamic", Integer.class, i -> invoke(anchor, new Object[]{Person.class}, adapter,
                () -> { throw new AssertionError("SDK entered during replay"); })).assertMatches();
        assertThat(replayed.value()).isEqualTo(new Person("Ada"));
        assertThat(resolved).hasValue(1);
    }

    static class DynamicGateway { public <T> T read(Class<T> type) { throw new AssertionError("SDK entered"); } }

    interface Gateway<T> { List<T> find(); }
    static class People implements Gateway<Person> {
        @Override public List<Person> find() { return List.of(new Person("Ada")); }
        public void replace(List<Person> input) { input.clear(); input.add(new Person("New")); }
    }
    record Person(String name) {}
    private <T> T invoke(RegressionAnchor anchor, Object[] args, ReplayAdapter<T> adapter, RegressionAnchor.Invocation<T> body) throws Exception {
        try { return anchor.captureBoundary("TEST", "method", args, adapter, body); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable e) { throw new RuntimeException(e); }
    }
}
