package io.github.unstoppableworm.anchor.execution;

import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * replay 동안 공유 상태를 기준 데이터에 맞추고 종료 후 되돌리는 내부 실행 범위다.
 * 진입 시 현재 상태를 보관한 뒤 기록된 공유 입력을 복원하고, 복원이 정확한지 확인한다.
 * 정상 종료나 실패 모두에서 기존 상태 복구를 시도하며 복구 실패도 호출자에게 전달한다.
 * 정적 capture 메서드는 등록된 상태를 키별 JSON 값으로 수집한다.
 */
final class SharedStateScope implements AutoCloseable {
    private final SnapshotCodec codec;
    private final List<SharedState<?>> states;
    private final Map<String, JsonNode> original;

    static Map<String, JsonNode> capture(SnapshotCodec codec, List<SharedState<?>> states) throws Exception {
        Map<String, JsonNode> result = new TreeMap<>();
        for (SharedState<?> state : states) result.put(state.key(), codec.freeze(state.capture()));
        return result;
    }

    SharedStateScope(SnapshotCodec codec, List<SharedState<?>> states, Map<String, JsonNode> input) throws Exception {
        this.codec = codec; this.states = states;
        this.original = capture(codec, states);
        try {
            restore(input);
            if (!capture(codec, states).equals(input)) throw new IllegalStateException("Shared input restoration was incomplete");
        } catch (Exception | Error error) {
            try { close(); } catch (Exception | Error cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void restore(Map<String, JsonNode> values) throws Exception {
        Throwable failure = null;
        for (SharedState<?> state : states) {
            try { restoreOne(state, values.get(state.key())); }
            catch (Exception | Error e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private <T> void restoreOne(SharedState<T> state, JsonNode value) throws Exception {
        state.restore(codec.mapper().convertValue(value, state.type()));
    }

    @Override public void close() throws Exception {
        restore(original);
        if (!capture(codec, states).equals(original)) throw new IllegalStateException("Shared state cleanup was incomplete");
    }
}
