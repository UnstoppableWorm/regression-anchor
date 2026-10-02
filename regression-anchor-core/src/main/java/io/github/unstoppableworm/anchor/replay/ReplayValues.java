package io.github.unstoppableworm.anchor.replay;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/**
 * 기록된 반환값·예외·인자 변경을 복원할 때 사용하는 공통 유틸리티다.
 * 변경된 Map·Collection·배열·가변 DTO는 기존 객체에 반영하여 MyBatis 생성 키 같은 부수 효과도 재현한다.
 * 기록된 예외의 타입·메시지·원인도 복원하며, 복원이 정확히 불가능하면 실패한다.
 * 외부 메서드를 실제로 호출하거나 저장소를 읽지는 않는다.
 */
public final class ReplayValues {
    private ReplayValues() {}

    public static <T> ReplayAdapter<T> returning(Class<T> type) {
        return (call, arguments, codec) -> {
            restoreArguments(call, arguments, codec, null);
            throwIfFailed(call.outcome().failure());
            return type == Void.class || type == void.class ? null : codec.mapper().convertValue(call.outcome().value(), type);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void restoreArguments(Snapshot.Call call, Object[] arguments, SnapshotCodec codec, JavaType[] types) throws Exception {
        if (call.argumentsAfter().size() != arguments.length) throw new IllegalStateException("Replay argument count differs");
        for (int i = 0; i < arguments.length; i++) {
            JsonNode after = call.argumentsAfter().get(i);
            if (call.argumentsBefore().get(i).equals(after)) continue;
            Object argument = arguments[i];
            if (argument == null) throw new IllegalStateException("Cannot replay a replaced null argument");
            JavaType type = types == null ? codec.mapper().constructType(argument.getClass()) : types[i];
            if (argument instanceof Map map) {
                Map restored = codec.mapper().convertValue(after, type);
                map.clear(); map.putAll(restored);
            } else if (argument instanceof Collection collection) {
                Collection restored = codec.mapper().convertValue(after, type);
                collection.clear(); collection.addAll(restored);
            } else if (argument.getClass().isArray()) {
                Object restored = codec.mapper().convertValue(after, argument.getClass());
                if (Array.getLength(argument) != Array.getLength(restored)) throw new IllegalStateException("Cannot resize replay argument");
                System.arraycopy(restored, 0, argument, 0, Array.getLength(restored));
            } else {
                Object restored = codec.mapper().readerForUpdating(argument).readValue(after);
                if (restored != argument) throw new IllegalStateException("Replay requires in-place argument restoration");
            }
            if (!codec.freeze(argument).equals(after)) throw new IllegalStateException("Replay argument restoration was incomplete at " + i);
        }
    }

    /** Recreate exception type/message/cause, or fail rather than changing catch-branch semantics. */
    public static void throwIfFailed(Snapshot.Failure failure) throws Exception {
        if (failure != null) throw restoreFailure(failure);
    }

    private static Exception restoreFailure(Snapshot.Failure failure) throws Exception {
        Class<?> type = Class.forName(failure.type(), false, Thread.currentThread().getContextClassLoader());
        if (!Exception.class.isAssignableFrom(type)) throw new IllegalStateException("Cannot replay failure: " + failure.type());
        Exception cause = failure.cause() == null ? null : restoreFailure(failure.cause());
        Exception restored;
        try { restored = (Exception) type.getConstructor(String.class, Throwable.class).newInstance(failure.message(), cause); }
        catch (NoSuchMethodException e) {
            restored = (Exception) type.getConstructor(String.class).newInstance(failure.message());
            if (cause != null) restored.initCause(cause);
        }
        if (!failure.equals(Snapshot.Failure.from(restored))) throw new IllegalStateException("Cannot reconstruct recorded failure: " + failure.type());
        return restored;
    }
}
