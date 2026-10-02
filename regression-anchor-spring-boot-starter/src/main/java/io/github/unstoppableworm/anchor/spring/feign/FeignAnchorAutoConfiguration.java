package io.github.unstoppableworm.anchor.spring.feign;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryPostProcessor;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryReplayAdapter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import java.lang.reflect.Proxy;

/**
 * Feign 프록시 또는 FeignClient 인터페이스를 가진 Spring 빈의 메서드 호출을 자동 계측한다.
 * Feign에 대한 필수 컴파일 의존성 없이 런타임 클래스와 프록시를 확인한다.
 * 기록 단위는 HTTP 내부 재시도 한 번씩이 아니라 Feign 메서드의 인자·반환값·예외다.
 * replay에서는 해당 메서드의 실제 호출을 생략하고 기록된 결과를 복원한다.
 */
@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass(name = "feign.Feign")
@Profile("regression")
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeignAnchorAutoConfiguration {
    @Bean static BeanPostProcessor anchorFeignClients(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters) {
        return new BoundaryPostProcessor(anchors, adapters, "FEIGN", bean -> {
            if (Proxy.isProxyClass(bean.getClass()) && Proxy.getInvocationHandler(bean).getClass().getName().startsWith("feign.")) return true;
            for (Class<?> contract : bean.getClass().getInterfaces()) if (isFeignClient(contract)) return true;
            return false;
        }, (bean, method) -> true, false);
    }

    private static boolean isFeignClient(Class<?> contract) {
        for (var annotation : contract.getAnnotations())
            if (annotation.annotationType().getName().equals("org.springframework.cloud.openfeign.FeignClient")) return true;
        for (Class<?> parent : contract.getInterfaces()) if (isFeignClient(parent)) return true;
        return false;
    }
}
