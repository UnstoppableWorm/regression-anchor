package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AnchorProperties.class)
public class AnchorAutoConfiguration {
    @FunctionalInterface public interface CodecCustomizer { void customize(com.fasterxml.jackson.databind.ObjectMapper mapper); }

    @Bean @ConditionalOnMissingBean
    SnapshotCodec anchorSnapshotCodec(List<CodecCustomizer> customizers) {
        var mapper = SnapshotCodec.defaultMapper();
        customizers.forEach(customizer -> customizer.customize(mapper));
        return new SnapshotCodec(mapper);
    }

    @Bean @ConditionalOnMissingBean
    SnapshotStore anchorSnapshotStore(AnchorProperties properties, SnapshotCodec codec) {
        return new FileSnapshotStore(properties.getBaselineDirectory(), properties.getReportDirectory(), codec);
    }

    @Bean @ConditionalOnMissingBean
    RegressionAnchor regressionAnchor(SnapshotCodec codec, SnapshotStore store, AnchorProperties properties) {
        return new RegressionAnchor(codec, store, new SnapshotComparator(properties.getIgnoredPaths()));
    }

    @Bean static BeanPostProcessor anchorExplicitBoundaries(ObjectProvider<RegressionAnchor> anchors) {
        return new BoundaryPostProcessor(anchors, "CUSTOM", bean -> {
            Class<?> type = bean.getClass();
            if (AnnotatedElementUtils.hasAnnotation(type, AnchorBoundary.class)) return true;
            for (var method : ReflectionUtils.getAllDeclaredMethods(type))
                if (AnnotatedElementUtils.hasAnnotation(method, AnchorBoundary.class)) return true;
            return false;
        }, (bean, method) -> AnnotatedElementUtils.hasAnnotation(bean.getClass(), AnchorBoundary.class)
                || AnnotatedElementUtils.hasAnnotation(method, AnchorBoundary.class)
                || AnnotatedElementUtils.hasAnnotation(org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, bean.getClass()), AnchorBoundary.class), true);
    }
}
