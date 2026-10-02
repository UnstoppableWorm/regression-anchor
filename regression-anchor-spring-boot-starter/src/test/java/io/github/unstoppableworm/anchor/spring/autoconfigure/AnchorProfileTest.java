package io.github.unstoppableworm.anchor.spring.autoconfigure;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.spring.feign.FeignAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.http.HttpAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.mongo.MongoAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.mybatis.MyBatisAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorProfileTest {
    private static final String[] INSTRUMENTATION_BEANS = {
            "anchorExplicitBoundaries", "anchorMyBatisMappers", "anchorMongoTemplate", "anchorMongoCodec",
            "anchorFeignClients", "anchorHttpInterceptor", "anchorRestClientCustomizer",
            "anchorRestTemplateCustomizer", "anchorRestTemplateBeans"
    };

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class,
                        MyBatisAnchorAutoConfiguration.class, MongoAnchorAutoConfiguration.class,
                        HttpAnchorAutoConfiguration.class, FeignAnchorAutoConfiguration.class))
                .withBean(Gateway.class, Gateway::new)
                .withBean(RestTemplate.class, RestTemplate::new);
    }

    private void assertDormant(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed().doesNotHaveBean(RegressionAnchor.class)
                .doesNotHaveBean(SnapshotCodec.class).doesNotHaveBean(SnapshotStore.class)
                .doesNotHaveBean(AnchorProperties.class);
        for (String name : INSTRUMENTATION_BEANS) assertThat(context).doesNotHaveBean(name);
        Gateway gateway = context.getBean(Gateway.class);
        assertThat(AopUtils.isAopProxy(gateway)).isFalse();
        assertThat(gateway.fetch()).isEqualTo(1);
        assertThat(gateway.fetch()).isEqualTo(2);
        assertThat(context.getBean(RestTemplate.class).getInterceptors()).isEmpty();
    }

    @Test void defaultProfileDoesNotRequireStorageOrInstallInstrumentation() {
        runner().run(this::assertDormant);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "test", "local", "regression-test", "Regression"})
    void enabledPropertyCannotBypassProfile(String profile) {
        runner(profile).withPropertyValues("regression-anchor.enabled=true").run(this::assertDormant);
    }

    @Test void enabledPropertyCannotActivateDefaultProfile() {
        runner().withPropertyValues("regression-anchor.enabled=true").run(this::assertDormant);
    }

    @Test void disabledPropertyWinsEvenWithRegressionProfile() {
        runner("regression").withPropertyValues("regression-anchor.enabled=false").run(this::assertDormant);
    }

    @Test void recordProfileInstallsEveryIntegrationAndRecords() {
        runner("regression", "regression-record").withBean(SnapshotStore.class, TestSnapshotStore::new).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RegressionAnchor.class);
            for (String name : INSTRUMENTATION_BEANS) assertThat(context).hasBean(name);
            Gateway gateway = context.getBean(Gateway.class);
            assertThat(AopUtils.isAopProxy(gateway)).isTrue();
            var anchor = context.getBean(RegressionAnchor.class);
            int recorded = anchor.record("profile", "trigger", ignored -> gateway.fetch());
            assertThat(recorded).isEqualTo(1);
            assertThat(gateway.fetch()).isEqualTo(2);
        });
    }

    static class Gateway {
        private int calls;
        @Anchor public int fetch() { return ++calls; }
    }
}
