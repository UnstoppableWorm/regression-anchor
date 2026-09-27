package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.apache.ibatis.binding.MapperProxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import java.lang.reflect.Proxy;

@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass(MapperProxy.class)
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisAnchorAutoConfiguration {
    @Bean static BeanPostProcessor anchorMyBatisMappers(ObjectProvider<RegressionAnchor> anchors) {
        return new BoundaryPostProcessor(anchors, "MYBATIS", bean -> Proxy.isProxyClass(bean.getClass())
                && Proxy.getInvocationHandler(bean) instanceof MapperProxy<?>, (bean, method) -> true, false);
    }
}
