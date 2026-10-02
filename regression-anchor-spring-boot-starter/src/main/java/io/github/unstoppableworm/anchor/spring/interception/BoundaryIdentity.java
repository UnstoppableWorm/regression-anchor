package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 경계의 클래스·메서드·파라미터 앵커를 해석해 기존 operation 문자열의 이름 구성 요소만 대체한다.
 * 인터페이스·상속·브리지 선언을 함께 검사하여 충돌을 거부하며 인자 배열이나 DTO 직렬화는 바꾸지 않는다.
 * 무값 캡처 지정은 기존 METHOD/opaque 동작을 유지하도록 별도로 표시한다.
 */
record BoundaryIdentity(String operation, String boundaryKey, boolean legacyCapture) {
    static BoundaryIdentity resolve(Class<?> target, String beanName, Method method) {
        Set<Class<?>> hierarchy = new LinkedHashSet<>(ClassUtils.getAllInterfacesForClassAsSet(target));
        for (Class<?> type = target; type != null && type != Object.class; type = type.getSuperclass()) hierarchy.add(type);
        List<Anchor> typeAnchors = new ArrayList<>();
        for (Class<?> type : hierarchy) add(typeAnchors, AnnotatedElementUtils.getMergedAnnotation(type, Anchor.class));
        String boundaryKey = resolveKey(typeAnchors, "boundary on " + target.getName(), false);

        Set<Method> declarations = methodDeclarations(target, method, hierarchy);
        List<Anchor> methodAnchors = new ArrayList<>();
        for (Method declaration : declarations) {
            if (!AnchoredMethods.isAnchoredRecordAccessor(declaration)) add(methodAnchors, AnnotatedElementUtils.getMergedAnnotation(declaration, Anchor.class));
        }
        String methodKey = resolveKey(methodAnchors, "method " + method.toGenericString(), false);
        boolean legacyCapture = typeAnchors.stream().anyMatch(a -> a.value().isEmpty()) || methodAnchors.stream().anyMatch(a -> a.value().isEmpty());
        List<String> parameters = new ArrayList<>();
        Set<String> parameterKeys = new LinkedHashSet<>();
        boolean hasNamedAnchor = boundaryKey != null || methodKey != null;
        for (int i = 0; i < method.getParameterCount(); i++) {
            List<Anchor> annotations = new ArrayList<>();
            for (Method declaration : declarations) add(annotations, declaration.getParameters()[i].getAnnotation(Anchor.class));
            String parameter = resolveKey(annotations, "parameter " + i + " of " + method.toGenericString(), true);
            if (parameter != null && !parameterKeys.add(parameter))
                throw new IllegalStateException("Duplicate @Anchor parameter key '" + parameter + "' on " + method.toGenericString());
            hasNamedAnchor |= parameter != null;
            parameters.add(parameter == null ? encode(method.getParameterTypes()[i].getName()) : "@" + encode(parameter));
        }
        if (!hasNamedAnchor) return new BoundaryIdentity(beanName + "#" + method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                .map(Class::getName).collect(Collectors.joining(",")) + ")", null, legacyCapture);
        // Escape components so punctuation in a user key cannot alias another signature.
        String operation = "anchor:" + encode(boundaryKey == null ? beanName : boundaryKey) + "#"
                + encode(methodKey == null ? method.getName() : methodKey) + "(" + String.join(",", parameters) + ")";
        return new BoundaryIdentity(operation, boundaryKey, legacyCapture);
    }

    private static String resolveKey(List<Anchor> annotations, String location, boolean requireValue) {
        Set<String> keys = new LinkedHashSet<>();
        for (Anchor annotation : annotations) {
            String value = annotation.value();
            if (value.isEmpty() && !requireValue) continue;
            if (value.isBlank()) throw new IllegalStateException("@Anchor value must not be blank: " + location);
            keys.add(value);
        }
        if (keys.size() > 1) throw new IllegalStateException("Conflicting @Anchor values " + keys + " for " + location);
        return keys.isEmpty() ? null : keys.iterator().next();
    }

    private static Set<Method> methodDeclarations(Class<?> target, Method method, Set<Class<?>> hierarchy) {
        Method specific = AopUtils.getMostSpecificMethod(method, target);
        Set<Method> declarations = new LinkedHashSet<>();
        declarations.add(method);
        declarations.add(specific);
        for (Class<?> type : hierarchy) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(method.getName())
                        && AopUtils.getMostSpecificMethod(candidate, target).equals(specific)) declarations.add(candidate);
            }
        }
        return declarations;
    }

    private static void add(List<Anchor> annotations, Anchor annotation) { if (annotation != null) annotations.add(annotation); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
