package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import java.lang.reflect.Proxy;

/** Records logical Feign client method arguments/results, including Spring Cloud proxies. */
@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass(name = "feign.Feign")
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeignAnchorAutoConfiguration {
    @Bean static BeanPostProcessor anchorFeignClients(ObjectProvider<RegressionAnchor> anchors) {
        return new BoundaryPostProcessor(anchors, "FEIGN", bean -> {
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
