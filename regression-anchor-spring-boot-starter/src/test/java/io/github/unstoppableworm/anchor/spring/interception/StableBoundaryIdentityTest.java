package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.mybatis.MyBatisAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 이름이 다른 실제 프록시의 기록·재생과 충돌 거부를 검증한다. */
class StableBoundaryIdentityTest {
    private final TestSnapshotStore store = new TestSnapshotStore();
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(c -> c.getEnvironment().setActiveProfiles("regression"))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, MyBatisAnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, () -> store);
    }

    @Test void renamedGatewayBeanMethodAndDtoRestoreGeneratedKeysAndReturnValues() {
        var old = new Orders();
        runner().withBean("orders", Orders.class, () -> old).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            anchor.record("rename", 20, input -> c.getBean(Orders.class).insert(new OldRow(input)));
            assertThat(anchor.loadSnapshot("rename").calls()).singleElement().satisfies(call -> {
                assertThat(call.argumentsBefore().isArray()).isTrue();
                assertThat(call.operation()).isEqualTo("anchor:order-store#save-order(@order)");
            });
        });
        byte[] before = store.baselines.get("rename").clone();
        var renamed = new Purchases();
        runner().withBean("purchases", Purchases.class, () -> renamed).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            var result = anchor.replay("rename", Integer.class, input -> {
                var row = new NewRow(input);
                NewRow value = c.getBean(Purchases.class).persist(row);
                assertThat(row.purchaseId).isEqualTo(41);
                return value;
            }).assertMatches();
            assertThat(result.value().payableAmount).isEqualTo(20);
            assertThat(result.value().purchaseId).isEqualTo(41);
            assertThat(result.actual().calls()).hasSize(1);
            assertThatThrownBy(() -> anchor.replay("rename", Integer.class,
                    input -> c.getBean(Purchases.class).persist(new NewRow(input + 1))))
                    .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("argumentsBefore");
        });
        assertThat(old.calls).isEqualTo(1);
        assertThat(renamed.calls).isZero();
        assertThat(store.baselines.get("rename")).isEqualTo(before);
    }

    @Test void namedMapperKeepsMybatisKindAdapterAndGeneratedKeyRestorationAcrossRename() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(OrderMapper.class);
        var session = mock(SqlSession.class);
        when(session.getConfiguration()).thenReturn(configuration);
        when(session.insert(anyString(), any())).thenAnswer(invocation -> {
            OldRow row = invocation.getArgument(1); row.orderId = 71L; return 1;
        });
        OrderMapper mapper = new MapperProxyFactory<>(OrderMapper.class).newInstance(session);
        runner().withBean("orderMapper", OrderMapper.class, () -> mapper).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            anchor.record("mapper", 15, input -> {
                var row = new OldRow(input); c.getBean(OrderMapper.class).insert(row); return row;
            });
            assertThat(anchor.loadSnapshot("mapper").calls()).singleElement().satisfies(call -> assertThat(call.kind()).isEqualTo("MYBATIS"));
        });
        verify(session, times(1)).insert(anyString(), any());
        var replaySession = mock(SqlSession.class, invocation -> { throw new AssertionError("MyBatis accessed during replay"); });
        PurchaseMapper renamed = new MapperProxyFactory<>(PurchaseMapper.class).newInstance(replaySession);
        var adapterCalls = new AtomicInteger();
        runner().withBean("purchaseMapper", PurchaseMapper.class, () -> renamed)
                .withBean(BoundaryReplayAdapter.class, () -> new BoundaryReplayAdapter() {
                    public boolean supports(String kind, java.lang.reflect.Method method) { return kind.equals("MYBATIS"); }
                    public Object restore(String kind, java.lang.reflect.Method method, Snapshot.Call call, Object[] arguments,
                                          io.github.unstoppableworm.anchor.snapshot.SnapshotCodec codec) throws Exception {
                        adapterCalls.incrementAndGet();
                        return MethodReplay.restore(renamed, method, call, arguments, codec, MethodReplay::declaredReturnType);
                    }
                }).run(c -> {
                    var anchor = c.getBean(RegressionAnchor.class);
                    var result = anchor.replay("mapper", Integer.class, input -> {
                        var row = new NewRow(input); c.getBean(PurchaseMapper.class).persist(row); return row;
                    }).assertMatches();
                    assertThat(result.value().purchaseId).isEqualTo(71L);
                    assertThat(adapterCalls).hasValue(1);
                    verifyNoInteractions(replaySession);
                });
    }

    @Test void genericInterfaceAndBridgeAnnotationsWorkOnAnExistingJdkProxy() {
        var target = new GenericGateway();
        var proxy = (GenericPort<?>) new ProxyFactory(target).getProxy();
        runner().withBean("generic", GenericPort.class, () -> proxy).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            @SuppressWarnings("unchecked") var port = (GenericPort<String>) c.getBean(GenericPort.class);
            anchor.record("generic", "a", port::read);
            target.offline = true;
            assertThat(anchor.replay("generic", String.class, port::read).assertMatches().value()).isEqualTo("a!");
            assertThat(target.calls).isEqualTo(1);
        });
    }

    @Test void partialKeysKeepUnspecifiedNamesAndLegacySignaturesStayUnchanged() {
        runner().withBean("partial", Partial.class).withBean("legacy", Legacy.class).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            anchor.record("partial", "a", c.getBean(Partial.class)::read);
            anchor.record("legacy", "a", c.getBean(Legacy.class)::read);
            assertThat(anchor.loadSnapshot("partial").calls().getFirst().operation()).isEqualTo("anchor:partial#lookup(java.lang.String)");
            assertThat(anchor.loadSnapshot("legacy").calls().getFirst().operation()).isEqualTo("legacy#read(java.lang.String)");
            anchor.replay("legacy", String.class, c.getBean(Legacy.class)::read).assertMatches();
        });
        runner().withBean("changedBean", Partial.class).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            assertThatThrownBy(() -> anchor.replay("partial", String.class, c.getBean(Partial.class)::read))
                    .isInstanceOf(ReplayMismatchException.class).hasMessageContaining("identity/order");
        });
    }

    @ParameterizedTest @ValueSource(strings = {"boundary", "method", "parameter"})
    void changingAnyStableKeyFailsWithoutLiveFallback(String component) {
        runner().withBean(Orders.class).run(c -> c.getBean(RegressionAnchor.class)
                .record("keys", 20, input -> c.getBean(Orders.class).insert(new OldRow(input))));
        // Alter one stored key to model a previously recorded contract; replay must never guess by values.
        runner().withBean(Purchases.class).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            var json = anchor.codec().freeze(anchor.loadSnapshot("keys"));
            var call = (com.fasterxml.jackson.databind.node.ObjectNode) json.path("calls").get(0);
            String part = switch (component) { case "boundary" -> "order-store"; case "method" -> "save-order"; default -> "@order"; };
            call.put("operation", call.path("operation").asText().replace(part, "changed"));
            store.baselines.put("keys", anchor.codec().writeJson(json));
            assertThatThrownBy(() -> anchor.replay("keys", Integer.class, input -> {
                try { return c.getBean(Purchases.class).persist(new NewRow(input)); }
                catch (ReplayMismatchException ignored) { return new NewRow(input); }
            })).isInstanceOf(ReplayMismatchException.class).hasMessageContaining("identity/order");
        });
    }

    @Test void duplicateBeansOverloadsAndParameterKeysFailAtStartup() {
        runner().withBean("one", Orders.class).withBean("two", Purchases.class).run(c -> assertThat(c.getStartupFailure())
                .hasStackTraceContaining("Duplicate @Anchor"));
        runner().withBean(Overloaded.class).run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("Duplicate @Anchor"));
        runner().withBean(DuplicateParameters.class).run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("Duplicate @Anchor parameter key"));
        runner().withBean(BlankParameter.class).run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("must not be blank"));
    }

    @Test void conflictingTypeMethodAndParameterDeclarationsFailAtStartup() {
        for (Class<?> type : List.of(ConflictingType.class, ConflictingMethod.class, ConflictingParameter.class))
            runner().withBean(type).run(c -> assertThat(c.getStartupFailure()).hasStackTraceContaining("Conflicting @Anchor"));
    }

    @Test void emptyMarkersOnAutomaticMappersKeepLegacyMethodOwnership() {
        var session = mock(SqlSession.class, invocation -> { throw new AssertionError("should not execute"); });
        var mapper = new MapperProxyFactory<>(LegacyMapper.class).newInstance(session);
        runner().withBean("legacyMapper", LegacyMapper.class, () -> mapper).run(c -> {
            var anchor = c.getBean(RegressionAnchor.class);
            // A default method can record without invoking SqlSession, while keeping the legacy kind.
            anchor.record("bare-mapper", 1, c.getBean(LegacyMapper.class)::read);
            anchor.replay("bare-mapper", Integer.class, c.getBean(LegacyMapper.class)::read).assertMatches();
            assertThat(anchor.loadSnapshot("bare-mapper").calls().getFirst().kind()).isEqualTo("METHOD");
            verifyNoInteractions(session);
        });
    }

    @Anchor("order-store") static class Orders {
        int calls;
        @Anchor("save-order") public OldRow insert(@Anchor("order") OldRow row) { calls++; row.orderId = 41L; return row; }
    }
    @Anchor("order-store") static class Purchases {
        int calls;
        @Anchor("save-order") public NewRow persist(@Anchor("order") NewRow payload) { calls++; throw new AssertionError("live gateway"); }
    }
    static class OldRow {
        @Anchor("order-id") public Long orderId;
        @Anchor("amount") public int total;
        public OldRow() {} OldRow(int total) { this.total = total; }
    }
    static class NewRow {
        @Anchor("order-id") public Long purchaseId;
        @Anchor("amount") public int payableAmount;
        public NewRow() {} NewRow(int amount) { this.payableAmount = amount; }
    }
    interface OldCommands {
        @Anchor("save-order") @Insert("insert into orders(amount) values(#{total})") @Options(useGeneratedKeys=true, keyProperty="orderId")
        int insert(@Anchor("order") OldRow row);
    }
    @Anchor("mapper-store") interface OrderMapper extends OldCommands {}
    @Anchor("mapper-store") interface PurchaseMapper { @Anchor("save-order") int persist(@Anchor("order") NewRow payload); }
    @Anchor interface LegacyMapper { default int read(int input) { return input; } }
    @Anchor("generic-port") interface GenericPort<T> { @Anchor("lookup") T read(@Anchor("key") T key); }
    static class GenericGateway implements GenericPort<String> {
        boolean offline; int calls;
        public String read(String key) { if (offline) throw new AssertionError("live generic gateway"); calls++; return key + "!"; }
    }
    static class Partial { @Anchor("lookup") public String read(String key) { return key; } }
    static class Legacy { @Anchor public String read(String key) { return key; } }
    @Anchor("overload") static class Overloaded {
        @Anchor("read") public String one(@Anchor("key") String key) { return key; }
        @Anchor("read") public String one(@Anchor("key") Long key) { return key.toString(); }
    }
    @Anchor static class DuplicateParameters { public String read(@Anchor("key") String a, @Anchor("key") String b) { return a+b; } }
    @Anchor static class BlankParameter { public String read(@Anchor String a) { return a; } }
    @Anchor("parent") interface Parent { @Anchor("read") String read(@Anchor("key") String key); }
    @Anchor("child") static class ConflictingType implements Parent { public String read(String key) { return key; } }
    static class ConflictingMethod implements Parent { @Anchor("other") public String read(String key) { return key; } }
    static class ConflictingParameter implements Parent { public String read(@Anchor("other") String key) { return key; } }
}
