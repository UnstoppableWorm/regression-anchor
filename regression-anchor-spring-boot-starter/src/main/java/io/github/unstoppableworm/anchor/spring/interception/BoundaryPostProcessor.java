package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayAdapter;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 각 기술별 자동 설정에서 공통으로 사용하는 내부 계측 구현이다.
 * 대상 Spring 빈에 AOP 인터셉터를 붙여 메서드 호출을 RegressionAnchor로 전달한다.
 * 통합 종류별 대상 판별 규칙을 받아 MyBatis·MongoDB·Feign·명시적 경계 계측에 공통으로 쓰인다.
 * 기존 프록시에는 advice를 추가하고, 필요한 경우 새 프록시를 만든다.
 * 빈 초기화 시 프록시를 설치하는 처리와 호출 시 대상을 검사하고 재생 어댑터를 선택하는 처리를 구분한다.
 * 호출 식별자의 빈 이름·메서드 이름·파라미터 타입은 해당 위치의 비어 있지 않은 Anchor 값으로 대체할 수 있다.
 * 실행 중에는 호출을 기록하거나 재생하고, 고정 REPLAY 모드에서는 범위 밖 의존성 접근도 검사한다.
 * 일반 gateway와 무값 Anchor는 중첩 계측을 억제한다. 이름만 지정한 자동 경계는 기술별 종류·복원기를 유지한다.
 * 사용자 BoundaryReplayAdapter가 하나 일치하면 이를 쓰고, 없으면 주입된 타입 해석기와 MethodReplay로 복원한다.
 */
public final class BoundaryPostProcessor implements BeanPostProcessor, BeanFactoryAware {
    private final ObjectProvider<RegressionAnchor> anchors;
    private final String kind;
    private final ObjectProvider<BoundaryReplayAdapter> adapters;
    private final Predicate<Object> accepts;
    private final BiPredicate<Object, Method> methods;
    private final boolean classProxy;
    private final Predicate<Method> localMethods;
    private final MethodReturnTypeResolver returnTypes;
    private ListableBeanFactory beanFactory;
    private final Map<String, Owner> operations = new ConcurrentHashMap<>();
    private final Map<String, Owner> boundaries = new ConcurrentHashMap<>();

    @Override public void setBeanFactory(BeanFactory factory) { this.beanFactory = (ListableBeanFactory) factory; }

    public BoundaryPostProcessor(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters, String kind, Predicate<Object> accepts,
                          BiPredicate<Object, Method> methods, boolean classProxy) {
        this(anchors, adapters, kind, accepts, methods, classProxy, method -> false);
    }

    public BoundaryPostProcessor(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters, String kind, Predicate<Object> accepts,
                          BiPredicate<Object, Method> methods, boolean classProxy, Predicate<Method> localMethods) {
        this(anchors, adapters, kind, accepts, methods, classProxy, localMethods, MethodReplay::declaredReturnType);
    }

    /** 기술별 자동 설정이 반환 타입 해석만 교체하는 내부 구성 지점이다. */
    public BoundaryPostProcessor(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters, String kind, Predicate<Object> accepts,
                          BiPredicate<Object, Method> methods, boolean classProxy, Predicate<Method> localMethods,
                          MethodReturnTypeResolver returnTypes) {
        this.anchors = anchors;
        this.adapters = adapters;
        this.kind = kind;
        this.accepts = accepts;
        this.methods = methods;
        this.classProxy = classProxy;
        this.localMethods = localMethods;
        this.returnTypes = Objects.requireNonNull(returnTypes, "returnTypes");
    }

    /** 기본 자동 설정에서 클래스 전체 및 개별 메서드의 Anchor 대상 판별을 연결하는 내부 팩토리다. */
    public static BeanPostProcessor forAnchoredMethods(ObjectProvider<RegressionAnchor> anchors,
                                                    ObjectProvider<BoundaryReplayAdapter> adapters) {
        return new BoundaryPostProcessor(anchors, adapters, "METHOD",
                bean -> !AnchoredMethods.methods(bean.getClass()).isEmpty(),
                (bean, method) -> AnchoredMethods.matches(bean.getClass(), method), true);
    }

    static Object target(Object bean) {
        Object target;
        while ((target = AopProxyUtils.getSingletonTarget(bean)) != null) bean = target;
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Object target = target(bean);
        if (!accepts.test(target)) return bean;
        boolean hasAnchoredMethods = kind.equals("METHOD") && !AnchoredMethods.methods(target.getClass()).isEmpty();
        boolean useClassProxy = classProxy && !Proxy.isProxyClass(bean.getClass());
        if (hasAnchoredMethods && Modifier.isFinal(target.getClass().getModifiers()))
            useClassProxy = false;
        if (hasAnchoredMethods) AnchoredMethods.validate(target.getClass(), bean.getClass(),
                bean instanceof Advised advised ? advised.isProxyTargetClass() : useClassProxy);
        boolean automaticBoundary = hasAutomaticProcessor(target);
        Map<Method, BoundaryIdentity> identities = registerIdentities(target, beanName, automaticBoundary);
        MethodInterceptor advice = invocation -> intercept(target, beanName, identities, automaticBoundary, invocation);
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            if (hasAnchoredMethods) advised.addAdvice(0, advice);
            else advised.addAdvice(advice);
            return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(useClassProxy);
        proxy.addAdvice(advice);
        return proxy.getProxy();
    }

    private boolean hasAutomaticProcessor(Object target) {
        // All processors are available here; do not duplicate each integration's bean detection rules.
        return kind.equals("METHOD") && beanFactory != null
                && beanFactory.getBeansOfType(BoundaryPostProcessor.class).values().stream()
                    .anyMatch(processor -> !processor.kind.equals("METHOD") && processor.accepts.test(target));
    }

    private Map<Method, BoundaryIdentity> registerIdentities(Object target, String beanName, boolean automaticBoundary) {
        var identities = new ConcurrentHashMap<Method, BoundaryIdentity>();
        for (Method method : target.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || ReflectionUtils.isObjectMethod(method) || localMethods.test(method)) continue;
            // Automatic method predicates can reject unsupported I/O. Evaluate them only on invocation.
            if (kind.equals("METHOD") && !methods.test(target, method)) continue;
            var identity = identities.computeIfAbsent(method, candidate -> BoundaryIdentity.resolve(target.getClass(), beanName, candidate));
            if (ownsCapture(identity, automaticBoundary)) register(target, beanName, method, identity);
        }
        return identities;
    }

    private Object intercept(Object target, String beanName, Map<Method, BoundaryIdentity> identities,
                             boolean automaticBoundary, MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        RegressionAnchor anchor = anchors.getObject();
        if (method.getDeclaringClass() == Object.class) return invocation.proceed();
        // Framework wiring can read local metadata without opening a dependency execution scope.
        if (localMethods.test(method)) return invocation.proceed();
        BoundaryIdentity identity = identities.computeIfAbsent(method,
                candidate -> BoundaryIdentity.resolve(target.getClass(), beanName, candidate));
        if (!ownsCapture(identity, automaticBoundary)) {
            // Automatic boundaries retain their unscoped I/O guard even when a legacy marker owns capture.
            if (!kind.equals("METHOD") && !anchor.isActive()) anchor.requireReplayScope();
            return invocation.proceed();
        }
        if (!anchor.isActive()) {
            if (!kind.equals("METHOD") || methods.test(target, method)) anchor.requireReplayScope();
            return invocation.proceed();
        }
        if (!methods.test(target, method)) {
            if (anchor.isReplaying() && !kind.equals("METHOD"))
                throw anchor.unsupported("Uninstrumented dependency method in replay: " + method.getName());
            return invocation.proceed();
        }
        String operation = identity.operation();
        ReplayAdapter<Object> replay = replayAdapterFor(target, method, operation);
        return kind.equals("METHOD")
                ? anchor.captureOpaqueBoundary(kind, operation, invocation.getArguments(), replay, invocation::proceed)
                : anchor.captureBoundary(kind, operation, invocation.getArguments(), replay, invocation::proceed);
    }

    private boolean ownsCapture(BoundaryIdentity identity, boolean automaticBoundary) {
        // Empty markers keep METHOD/opaque ownership; names alone preserve automatic integration behavior.
        if (identity.legacyCapture()) return kind.equals("METHOD");
        return !kind.equals("METHOD") || !automaticBoundary;
    }

    private void register(Object target, String beanName, Method method, BoundaryIdentity identity) {
        var owner = new Owner(target, beanName, AopUtils.getMostSpecificMethod(method, target.getClass()));
        if (identity.boundaryKey() != null) {
            Owner previous = boundaries.putIfAbsent(identity.boundaryKey(), owner);
            if (previous != null && !previous.sameBean(owner)) throw duplicate(identity.boundaryKey(), previous, owner);
        }
        Owner previous = operations.putIfAbsent(identity.operation(), owner);
        if (previous != null && (!previous.sameBean(owner) || !previous.method().equals(owner.method())))
            throw duplicate(identity.operation(), previous, owner);
    }

    private IllegalStateException duplicate(String key, Owner before, Owner after) {
        return new IllegalStateException("Duplicate @Anchor boundary/operation '" + key + "' for " + kind + ": "
                + before.beanName() + "/" + before.method() + " and " + after.beanName() + "/" + after.method());
    }

    /** 같은 Spring 빈의 브리지·별칭과 서로 다른 경계의 식별자 충돌을 구분한다. */
    private record Owner(Object target, String beanName, Method method) {
        boolean sameBean(Owner other) { return target == other.target || beanName.equals(other.beanName); }
    }

    private ReplayAdapter<Object> replayAdapterFor(Object target, Method method, String operation) {
        return (recorded, arguments, codec) -> {
            var matches = adapters.orderedStream().filter(adapter -> adapter.supports(kind, method)).toList();
            if (matches.size() > 1) throw new IllegalStateException("Multiple replay adapters for " + operation);
            if (!matches.isEmpty())
                return matches.getFirst().restore(kind, method, recorded, arguments, codec);
            return MethodReplay.restore(target, method, recorded, arguments, codec, returnTypes);
        };
    }
}
