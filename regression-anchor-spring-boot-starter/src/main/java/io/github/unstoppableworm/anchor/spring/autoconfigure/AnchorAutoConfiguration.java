package io.github.unstoppableworm.anchor.spring.autoconfigure;

import io.github.unstoppableworm.anchor.execution.ExecutionMode;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.execution.SharedState;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryPostProcessor;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryReplayAdapter;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import io.github.unstoppableworm.anchor.verification.SnapshotComparator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import java.util.List;

/**
 * Spring Boot에서 회귀 엔진과 명시적 메서드 계측을 조립하는 중심 자동 설정이다.
 * regression 프로필과 enabled 조건을 통과했을 때 코덱·엔진·명시적 경계 인터셉터를 등록한다.
 * 소비자의 SnapshotStore와 SharedState 빈을 연결하고, 모드 프로필이 있으면 RECORD 또는 REPLAY로 고정한다.
 * 두 모드 프로필이 동시에 켜지면 일반 singleton 초기화 전에 실패시킨다.
 * MyBatis·MongoDB·HTTP·Feign 연동은 각각의 자동 설정에서 추가한다.
 */
@AutoConfiguration
@Profile("regression")
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AnchorProperties.class)
public class AnchorAutoConfiguration {
    /**
     * 스냅샷 전용 mapper에 업무 타입이나 인프라 타입의 JSON 변환 규칙을 추가하는 확장점이다.
     * 자동 설정이 SnapshotCodec을 만들 때 등록된 customizer들을 적용한다.
     * 저장소 선택이나 애플리케이션 HTTP 응답용 mapper 설정과는 별개의 역할이다.
     */
    @FunctionalInterface public interface CodecCustomizer { void customize(com.fasterxml.jackson.databind.ObjectMapper mapper); }

    // Resolve before ordinary singletons (including application clients) are initialized.
    @Bean static BeanFactoryPostProcessor anchorModeValidation(Environment environment) {
        resolveMode(environment);
        return beanFactory -> {};
    }

    private static ExecutionMode resolveMode(Environment environment) {
        boolean record = environment.acceptsProfiles(Profiles.of("regression-record"));
        boolean replay = environment.acceptsProfiles(Profiles.of("regression-replay"));
        if (record && replay) throw new IllegalStateException(
                "regression-record and regression-replay cannot be active together");
        if (record) return ExecutionMode.RECORD;
        return replay ? ExecutionMode.REPLAY : ExecutionMode.MANUAL;
    }

    @Bean @ConditionalOnMissingBean
    SnapshotCodec anchorSnapshotCodec(List<CodecCustomizer> customizers) {
        var mapper = SnapshotCodec.defaultMapper();
        customizers.forEach(customizer -> customizer.customize(mapper));
        return new SnapshotCodec(mapper);
    }

    @Bean @ConditionalOnMissingBean
    RegressionAnchor regressionAnchor(SnapshotCodec codec, SnapshotStore store, AnchorProperties properties, List<SharedState<?>> sharedStates, Environment environment) {
        return new RegressionAnchor(codec, store, new SnapshotComparator(properties.getIgnoredPaths()), sharedStates, resolveMode(environment));
    }

    @Bean static BeanPostProcessor anchorExplicitBoundaries(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters) {
        return BoundaryPostProcessor.forAnchoredMethods(anchors, adapters);
    }
}
