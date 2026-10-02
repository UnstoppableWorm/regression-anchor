package io.github.unstoppableworm.anchor.spring.feign;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.ManualEngineConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class FeignAnchorAutoConfigurationTest {

    @Test void capturesRepeatedFeignCallsAndReplaysWithoutTransport() {
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("regression", "regression-record")).withUserConfiguration(ManualEngineConfiguration.class).withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, FeignAnchorAutoConfiguration.class))
                .withUserConfiguration(Clients.class)
                .withBean(io.github.unstoppableworm.anchor.storage.SnapshotStore.class, TestSnapshotStore::new)
                .run(context -> {
                    var anchor = context.getBean(RegressionAnchor.class);
                    var client = context.getBean(PriceClient.class);
                    anchor.record("feign", "BOOK", sku -> client.price(sku) + client.price(sku));
                    context.getBean(AtomicInteger.class).set(-1);
                    var result = anchor.replay("feign", String.class, sku -> client.price(sku) + client.price(sku)).assertMatches();
                    assertThat(result.actual().calls()).hasSize(2).allMatch(call -> call.kind().equals("FEIGN"));
                    var changed = anchor.replay("feign", String.class, sku -> client.price(sku) + ":" + client.price(sku));
                    assertThat(changed.matches()).isFalse();
                });
    }

    @Test void classAndAnchoredMethodsOnFeignAreRecordedOnlyOnceAndReplayedWithoutTransport() {
        var calls = new AtomicInteger();
        var offline = new java.util.concurrent.atomic.AtomicBoolean();
        MarkedClient client = Feign.builder().client((request, options) -> {
            if (offline.get()) throw new AssertionError("Feign transport executed");
            calls.incrementAndGet();
            return Response.builder().request(request).status(200).reason("OK").headers(Map.of())
                    .body("metadata", StandardCharsets.UTF_8).build();
        }).target(MarkedClient.class, "https://example.test");
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("regression", "regression-record")).withUserConfiguration(ManualEngineConfiguration.class).withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, FeignAnchorAutoConfiguration.class))
                .withBean(io.github.unstoppableworm.anchor.storage.SnapshotStore.class, TestSnapshotStore::new)
                .withBean(MarkedClient.class, () -> client)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var anchor = context.getBean(RegressionAnchor.class);
                    var bean = context.getBean(MarkedClient.class);
                    anchor.record("marked-feign", "BOOK", bean::read);
                    offline.set(true);
                    var result = anchor.replay("marked-feign", String.class, bean::read).assertMatches();
                    assertThat(result.actual().calls()).singleElement().satisfies(call -> assertThat(call.kind()).isEqualTo("METHOD"));
                    assertThat(calls).hasValue(1);
                });
    }
    @Anchor interface MarkedClient {
        @Anchor
        @RequestLine("GET /metadata/{key}") String read(@Param("key") String key);
    }

    interface PriceClient { @RequestLine("GET /prices/{sku}") String price(@Param("sku") String sku); }
    @Configuration(proxyBeanMethods = false) static class Clients {
        @Bean AtomicInteger price() { return new AtomicInteger(100); }
        @Bean PriceClient priceClient(AtomicInteger price) {
            return Feign.builder().client((request, options) -> {
                if (price.get() < 0) throw new AssertionError("Feign transport accessed during replay");
                return Response.builder().request(request).status(200).reason("OK")
                    .headers(Map.of()).body(Integer.toString(price.get()), StandardCharsets.UTF_8).build();
            })
                    .target(PriceClient.class, "https://example.test");
        }
    }
}
