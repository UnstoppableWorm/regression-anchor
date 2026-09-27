package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class BoundaryPostProcessor implements BeanPostProcessor {
    private final ObjectProvider<RegressionAnchor> anchors;
    private final String kind;
    private final Predicate<Object> accepts;
    private final BiPredicate<Object, Method> methods;
    private final boolean classProxy;

    BoundaryPostProcessor(ObjectProvider<RegressionAnchor> anchors, String kind, Predicate<Object> accepts,
                          BiPredicate<Object, Method> methods, boolean classProxy) {
        this.anchors = anchors; this.kind = kind; this.accepts = accepts; this.methods = methods; this.classProxy = classProxy;
    }

    static Object target(Object bean) {
        Object target;
        while ((target = AopProxyUtils.getSingletonTarget(bean)) != null) bean = target;
        return bean;
    }

    @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
        Object target = target(bean);
        if (!accepts.test(target)) return bean;
        MethodInterceptor advice = invocation -> {
            Method method = invocation.getMethod();
            RegressionAnchor anchor = anchors.getObject();
            if (!anchor.isActive() || method.getDeclaringClass() == Object.class || !methods.test(target, method)) {
                return invocation.proceed();
            }
            String signature = beanName + "#" + method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                    .map(Class::getName).collect(Collectors.joining(",")) + ")";
            return anchor.capture(kind, signature, invocation.getArguments(), invocation::proceed);
        };
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            advised.addAdvice(advice);
            return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(classProxy);
        proxy.addAdvice(advice);
        return proxy.getProxy();
    }
}
