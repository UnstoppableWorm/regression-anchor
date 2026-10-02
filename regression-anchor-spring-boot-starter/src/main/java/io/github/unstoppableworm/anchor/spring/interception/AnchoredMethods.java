package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;

import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * 클래스 전체 또는 개별 메서드의 Anchor를 찾고 프록시로 가로챌 수 있는지 검증한다.
 * 브리지 메서드·구현·상속·인터페이스 지정을 해석하며 클래스 지정에서는 Object·static 메서드를 제외한다.
 * 레코드 컴포넌트에서 접근자로 전파된 필드 키 지정은 캡처 어노테이션으로 취급하지 않는다.
 * public·인스턴스 메서드 조건과 프록시 방식에 따른 final 제한·인터페이스 노출 여부를 검사한다.
 * 가로챌 수 없는 메서드에 붙은 어노테이션이 조용히 무시되는 것을 방지한다.
 */
final class AnchoredMethods {
    static boolean matches(Class<?> target, Method method) {
        Method specific = AopUtils.getMostSpecificMethod(method, target);
        if (methodAnchor(specific, method) != null) return true;
        return typeAnchor(target) != null && Modifier.isPublic(specific.getModifiers())
                && !Modifier.isStatic(specific.getModifiers()) && !ReflectionUtils.isObjectMethod(specific);
    }

    private static Anchor typeAnchor(Class<?> target) {
        return AnnotatedElementUtils.findMergedAnnotation(target, Anchor.class);
    }

    private static Anchor methodAnchor(Method specific, Method original) {
        if (isAnchoredRecordAccessor(specific)) return null;
        Anchor annotation = AnnotatedElementUtils.findMergedAnnotation(specific, Anchor.class);
        return annotation != null ? annotation : AnnotatedElementUtils.findMergedAnnotation(original, Anchor.class);
    }

    // javac copies a record component's JSON key to its accessor; it is not a method identity.
    static boolean isAnchoredRecordAccessor(Method method) {
        if (!method.getDeclaringClass().isRecord()) return false;
        for (var component : method.getDeclaringClass().getRecordComponents()) {
            if (component.getAccessor().equals(method) && component.isAnnotationPresent(Anchor.class)) return true;
        }
        return false;
    }

    static Set<Method> methods(Class<?> type) {
        return MethodIntrospector.selectMethods(type, (ReflectionUtils.MethodFilter) method -> matches(type, method));
    }

    static void validate(Class<?> target, Class<?> exposed, boolean classProxy) {
        for (Method method : methods(target)) {
            Method specific = AopUtils.getMostSpecificMethod(method, target);
            int modifiers = specific.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || ReflectionUtils.isObjectMethod(specific))
                throw invalid(specific, "requires a public instance method");
            if (classProxy && (Modifier.isFinal(target.getModifiers()) || Modifier.isFinal(modifiers)))
                throw invalid(specific, "cannot intercept a final class/method with a class proxy; use an exposed interface or remove final");
            if (!classProxy) {
                boolean exposedMethod = ClassUtils.getAllInterfacesForClassAsSet(exposed).stream()
                        .flatMap(contract -> java.util.Arrays.stream(contract.getMethods()))
                        .anyMatch(candidate -> AopUtils.getMostSpecificMethod(candidate, target).equals(specific));
                if (!exposedMethod) throw invalid(specific, "is not exposed through the bean's proxy interfaces");
            }
        }
    }

    private static IllegalStateException invalid(Method method, String reason) {
        return new IllegalStateException("@Anchor " + method.toGenericString() + " " + reason);
    }
}
