package io.github.unstoppableworm.anchor.spring.mybatis;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryPostProcessor;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryReplayAdapter;

import org.apache.ibatis.binding.MapperProxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.lang.reflect.Proxy;

/**
 * regression에서 실제 MyBatis MapperProxy로 만들어진 매퍼 빈을 자동 계측한다.
 * 매퍼마다 회귀 전용 어노테이션을 붙이지 않아도 호출 입력·반환값·예외·인자 변경을 기록한다.
 * replay에서는 매퍼 본문을 호출하지 않고 기본 메서드 복원기로 결과와 생성 키 등의 변경을 재현한다.
 * MyBatis 클래스가 있고 라이브러리가 활성화된 경우에만 적용된다.
 */
@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass(MapperProxy.class)
@Profile("regression")
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisAnchorAutoConfiguration {
    @Bean
    static BeanPostProcessor anchorMyBatisMappers(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters) {
        return new BoundaryPostProcessor(anchors, adapters, "MYBATIS", bean -> Proxy.isProxyClass(bean.getClass())
                && Proxy.getInvocationHandler(bean) instanceof MapperProxy<?>, (bean, method) -> true, false);
    }
}
