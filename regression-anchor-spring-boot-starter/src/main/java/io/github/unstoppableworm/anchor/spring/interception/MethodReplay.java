package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.replay.ReplayValues;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.core.ResolvableType;
import java.lang.reflect.Method;

/**
 * 메서드 시그니처와 대상 빈의 타입 정보를 이용하는 기본 외부 호출 복원기다.
 * 먼저 기록된 인자 변경과 예외를 복원한 뒤, JSON 반환값을 실제 반환 타입으로 변환한다.
 * 반환 타입은 주입된 MethodReturnTypeResolver 포트에 맡기고, 공통 코드는 특정 저장 기술을 알지 않는다.
 * 반환 타입을 확정할 수 없으면 임의의 Map으로 대체하지 않고 BoundaryReplayAdapter 등록을 요구한다.
 */
final class MethodReplay {
    static Object restore(Object target, Method method, Snapshot.Call call, Object[] arguments, SnapshotCodec codec,
                          MethodReturnTypeResolver returnTypes) throws Exception {
        // Restoring only changed arguments leaves immutable query/class parameters untouched.
        JavaType[] parameterTypes = new JavaType[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameterTypes[i] = codec.mapper().constructType(arguments[i] == null ? method.getParameterTypes()[i] : arguments[i].getClass());
            ResolvableType parameter = ResolvableType.forMethodParameter(method, i, target.getClass());
            if (!parameter.hasUnresolvableGenerics() && parameter.resolve() != Object.class && parameter.resolve() != null)
                parameterTypes[i] = type(parameter, codec);
        }
        ReplayValues.restoreArguments(call, arguments, codec, parameterTypes);
        ReplayValues.throwIfFailed(call.outcome().failure());
        if (method.getReturnType() == void.class || call.outcome().value().isNull()) return null;
        JavaType type = type(returnTypes.resolve(target, method, arguments), codec);
        return codec.mapper().convertValue(call.outcome().value(), type);
    }

    static ResolvableType declaredReturnType(Object target, Method method, Object[] arguments) {
        return ResolvableType.forMethodReturnType(method, target.getClass());
    }

    static JavaType type(ResolvableType type, SnapshotCodec codec) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class || type.hasUnresolvableGenerics())
            throw new IllegalStateException("Unresolved replay type " + type + "; register a BoundaryReplayAdapter");
        if (raw.isArray()) return codec.mapper().getTypeFactory().constructArrayType(type(type.getComponentType(), codec));
        if (type.getGenerics().length == 0) return codec.mapper().constructType(raw);
        JavaType[] arguments = java.util.Arrays.stream(type.getGenerics()).map(t -> type(t, codec)).toArray(JavaType[]::new);
        return codec.mapper().getTypeFactory().constructParametricType(raw, arguments);
    }
}
