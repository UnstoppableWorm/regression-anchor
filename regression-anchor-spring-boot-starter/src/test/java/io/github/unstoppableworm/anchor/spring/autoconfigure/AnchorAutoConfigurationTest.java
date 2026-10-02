package io.github.unstoppableworm.anchor.spring.autoconfigure;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.http.AnchorHttpInterceptor;
import io.github.unstoppableworm.anchor.spring.http.HttpAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.mongo.MongoAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.mybatis.MyBatisAnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class AnchorAutoConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("regression")).withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class,
                        MyBatisAnchorAutoConfiguration.class, MongoAnchorAutoConfiguration.class, HttpAnchorAutoConfiguration.class));
    }

    @Test void startsWithoutOptionalIntegrations() {
        runner().withBean(SnapshotStore.class, TestSnapshotStore::new)
                .withClassLoader(new FilteredClassLoader("org.apache.ibatis", "org.springframework.data.mongodb", "org.springframework.web", "org.springframework.boot.restclient"))
                .run(context -> assertThat(context).hasSingleBean(RegressionAnchor.class).hasSingleBean(SnapshotStore.class).hasNotFailed());
    }

    @Test void requiresConsumerStorageInsteadOfSilentlyChoosingABackend() {
        runner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                    .hasStackTraceContaining(SnapshotStore.class.getName());
        });
    }

    @Test void canDisableAllInstrumentation() {
        runner().withPropertyValues("regression-anchor.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(RegressionAnchor.class).doesNotHaveBean(SnapshotStore.class)
                        .doesNotHaveBean(AnchorHttpInterceptor.class).hasNotFailed());
    }

    @Test void capturesOnlyExternalMethodWithoutInstrumentingTheService() {
        runner().withBean(SnapshotStore.class, TestSnapshotStore::new).withUserConfiguration(Services.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var service = context.getBean(Service.class);
            anchor.record("custom", 2, service::twice);
            var result = anchor.replay("custom", Integer.class, service::twice).assertMatches();
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isFalse();
            assertThat(result.actual().calls()).singleElement().satisfies(call -> {
                assertThat(call.kind()).isEqualTo("METHOD");
                assertThat(call.parentSequence()).isNull();
            });
            assertThat(result.value()).isEqualTo(6);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Services {
        @Bean Service service(Child child) { return new Service(child); }
        @Bean Child child() { return new Child(); }
    }
    static class Service {
        private final Child child;
        Service(Child child) { this.child = child; }
        public int twice(int i) { return child.increment(i) * 2; }
    }
    static class Child {
        @Anchor public int increment(int i) { return i + 1; }
    }
}
