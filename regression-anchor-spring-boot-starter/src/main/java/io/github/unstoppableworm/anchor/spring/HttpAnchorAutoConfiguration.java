package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpAnchorAutoConfiguration {
    @Bean AnchorHttpInterceptor anchorHttpInterceptor(RegressionAnchor anchor, AnchorProperties properties) {
        return new AnchorHttpInterceptor(anchor, properties);
    }

    @Bean RestClientCustomizer anchorRestClientCustomizer(AnchorHttpInterceptor interceptor) {
        return builder -> builder.requestInterceptors(interceptors -> {
            if (!interceptors.contains(interceptor)) interceptors.add(interceptor);
        });
    }

    @Bean RestTemplateCustomizer anchorRestTemplateCustomizer(AnchorHttpInterceptor interceptor) {
        return template -> customize(template, interceptor);
    }

    @Bean static BeanPostProcessor anchorRestTemplateBeans(ObjectProvider<AnchorHttpInterceptor> interceptors) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RestTemplate template) customize(template, interceptors.getObject());
                return bean;
            }
        };
    }

    private static void customize(RestTemplate template, AnchorHttpInterceptor interceptor) {
        if (!template.getInterceptors().contains(interceptor)) template.getInterceptors().add(interceptor);
    }
}
