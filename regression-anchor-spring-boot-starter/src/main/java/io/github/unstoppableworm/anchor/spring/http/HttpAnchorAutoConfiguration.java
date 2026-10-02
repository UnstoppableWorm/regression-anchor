package io.github.unstoppableworm.anchor.spring.http;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Spring HTTP 클라이언트에 AnchorHttpInterceptor를 연결하는 자동 설정이다.
 * Boot의 RestClient·RestTemplate builder customizer와 RestTemplate 빈 후처리를 제공한다.
 * 이미 같은 인터셉터가 있으면 중복 등록하지 않으며 regression 활성화 조건 안에서만 동작한다.
 * Boot customizer를 거치지 않고 직접 만든 RestClient 등은 별도 연결이 필요하다.
 */
@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
@Profile("regression")
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
