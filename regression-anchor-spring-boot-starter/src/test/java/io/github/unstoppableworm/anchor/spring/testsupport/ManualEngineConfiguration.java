package io.github.unstoppableworm.anchor.spring.testsupport;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Low-level interceptor contract tests intentionally exercise all operations in one context. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ManualEngineConfiguration {
    @Bean RegressionAnchor manualAnchor(SnapshotCodec codec, SnapshotStore store) {
        return new RegressionAnchor(codec, store);
    }
}
