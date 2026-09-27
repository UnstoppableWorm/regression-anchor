package io.github.unstoppableworm.anchor.spring;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.Response;
import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class FeignAnchorAutoConfigurationTest {
    @TempDir Path directory;

    @Test void capturesRepeatedFeignCallsAndDetectsChangedClientResults() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, FeignAnchorAutoConfiguration.class))
                .withUserConfiguration(Clients.class)
                .withPropertyValues("regression-anchor.baseline-directory=" + directory.resolve("baselines"),
                        "regression-anchor.report-directory=" + directory.resolve("reports"))
                .run(context -> {
                    var anchor = context.getBean(RegressionAnchor.class);
                    var client = context.getBean(PriceClient.class);
                    anchor.record("feign", "BOOK", sku -> client.price(sku) + client.price(sku));
                    var result = anchor.verify("feign", String.class, sku -> client.price(sku) + client.price(sku)).assertMatches();
                    assertThat(result.actual().calls()).hasSize(2).allMatch(call -> call.kind().equals("FEIGN"));
                    context.getBean(AtomicInteger.class).incrementAndGet();
                    assertThat(anchor.verify("feign", String.class, sku -> client.price(sku) + client.price(sku)).matches()).isFalse();
                });
    }

    interface PriceClient { @RequestLine("GET /prices/{sku}") String price(@Param("sku") String sku); }
    @Configuration(proxyBeanMethods = false) static class Clients {
        @Bean AtomicInteger price() { return new AtomicInteger(100); }
        @Bean PriceClient priceClient(AtomicInteger price) {
            return Feign.builder().client((request, options) -> Response.builder().request(request).status(200).reason("OK")
                    .headers(Map.of()).body(Integer.toString(price.get()), StandardCharsets.UTF_8).build())
                    .target(PriceClient.class, "https://example.test");
        }
    }
}
