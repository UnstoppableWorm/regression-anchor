package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class AnchorAutoConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class,
                        MyBatisAnchorAutoConfiguration.class, MongoAnchorAutoConfiguration.class, HttpAnchorAutoConfiguration.class))
                .withPropertyValues("regression-anchor.baseline-directory=" + directory.resolve("baselines"),
                        "regression-anchor.report-directory=" + directory.resolve("reports"));
    }

    @Test void startsWithoutOptionalIntegrations() {
        runner().withClassLoader(new FilteredClassLoader("org.apache.ibatis", "org.springframework.data.mongodb", "org.springframework.web", "org.springframework.boot.restclient"))
                .run(context -> assertThat(context).hasSingleBean(RegressionAnchor.class).hasNotFailed());
    }

    @Test void canDisableAllInstrumentation() {
        runner().withPropertyValues("regression-anchor.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(RegressionAnchor.class).doesNotHaveBean(AnchorHttpInterceptor.class).hasNotFailed());
    }

    @Test void capturesAnnotatedBeanWithParentAndMethodScope() {
        runner().withUserConfiguration(Services.class).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var service = context.getBean(Service.class);
            anchor.record("custom", 2, service::twice);
            var result = anchor.verify("custom", Integer.class, service::twice).assertMatches();
            assertThat(result.actual().calls()).hasSize(2);
            assertThat(result.actual().calls().get(1).parentSequence()).isEqualTo(1);
            assertThat(result.value()).isEqualTo(6);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Services {
        @Bean Service service(Child child) { return new Service(child); }
        @Bean Child child() { return new Child(); }
    }
    @AnchorBoundary static class Service {
        private final Child child;
        Service(Child child) { this.child = child; }
        public int twice(int i) { return child.increment(i) * 2; }
    }
    static class Child {
        @AnchorBoundary public int increment(int i) { return i + 1; }
    }
}
