package io.github.unstoppableworm.anchor.spring.mongo;

import io.github.unstoppableworm.anchor.spring.interception.MethodReturnTypeResolver;

import org.springframework.core.ResolvableType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.Collection;

/**
 * Mongo 자동 설정이 공통 복원기의 MethodReturnTypeResolver 포트에 주입하는 내부 어댑터다.
 * MongoOperations의 제네릭 반환값을 기록 JSON에서 어떤 타입으로 복원할지 결정한다.
 * 메서드의 Class 인자나 문서 인자로 반환 요소 타입을 찾고, 컬렉션 반환은 해당 요소의 목록으로 구성한다.
 * 예를 들어 findDistinct의 조회 문서 타입과 실제 반환 값 타입을 구분한다.
 * 타입을 확정하지 못하면 실패하며, 실제 MongoDB 호출이나 JSON 변환 자체는 수행하지 않는다.
 */
final class MongoReturnTypeResolver implements MethodReturnTypeResolver {
    /** 메서드 타입 변수가 있는 Mongo 호출의 반환 요소 타입을 인자에서 결정한다. */
    @Override public ResolvableType resolve(Object target, Method method, Object[] arguments) {
        if (method.getTypeParameters().length == 0) return ResolvableType.forMethodReturnType(method);
        Class<?> element = null;
        var parameters = method.getGenericParameterTypes();
        // Class<T>, not Class<?> (e.g. findDistinct's entity class), binds the returned value type.
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i] instanceof Class<?> candidate && parameters[i] instanceof ParameterizedType parameter
                    && parameter.getRawType() == Class.class && parameter.getActualTypeArguments()[0] instanceof TypeVariable<?>) element = candidate;
            if (parameters[i] instanceof TypeVariable<?> && arguments[i] != null) element = arguments[i].getClass();
        }
        if (element == null) throw new IllegalStateException("Unresolved Mongo replay type for " + method.getName() + "; register a BoundaryReplayAdapter");
        if (Collection.class.isAssignableFrom(method.getReturnType()))
            return ResolvableType.forClassWithGenerics(java.util.ArrayList.class, element);
        return ResolvableType.forClass(element);
    }
}
