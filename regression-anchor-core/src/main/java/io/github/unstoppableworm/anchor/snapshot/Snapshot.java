package io.github.unstoppableworm.anchor.snapshot;

import io.github.unstoppableworm.anchor.replay.ReplayValues;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 한 회귀 사례의 최초 입력, 최종 결과, 순서가 있는 호출 목록, 공유 입력·출력을 담는 저장 모델이다.
 * record에서 만들어 기준으로 저장하고, replay에서 현재 실행과 비교한다.
 * 반복 호출은 각 Call의 순번으로 구분하며 부모 순번은 계측된 호출 사이의 관계를 나타낸다.
 * formatVersion으로 저장 형식을 구분하고, JSON 변환은 {@link SnapshotCodec}이 담당한다.
 */
public record Snapshot(int formatVersion, String caseId, JsonNode input, Outcome outcome, List<Call> calls,
                       Map<String, JsonNode> sharedInputs, Map<String, JsonNode> sharedOutputs) {
    public static final int FORMAT_VERSION = 3;

    public Snapshot {
        sharedInputs = sharedInputs == null ? Map.of() : Map.copyOf(sharedInputs);
        sharedOutputs = sharedOutputs == null ? Map.of() : Map.copyOf(sharedOutputs);
    }

    /** Source compatibility for consumers that do not capture shared state. */
    public Snapshot(int version, String id, JsonNode input, Outcome outcome, List<Call> calls) {
        this(version, id, input, outcome, calls, Map.of(), Map.of());
    }

    /**
     * 계측된 메서드 호출 한 건의 식별 정보와 입출력이다.
     * sequence는 실행 내 호출 순번, parentSequence는 계측된 부모 호출의 순번이다.
     * kind와 operation은 호출 대상을 식별하고, argumentsBefore/After는 호출로 인한 인자 변경까지 보존한다.
     * callerPath는 과거 기록을 읽기 위해 유지하며 새 버전 3 기록에는 빈 목록을 저장한다.
     * callerPath와 parentSequence는 동등성 판정에서 제외한다. 부모 정보는 중첩 경계 재생 안전 검사에 사용한다.
     * 모든 지원 버전에서 순번·종류·외부 작업 식별자·입출력은 계속 비교한다.
     */
    public record Call(long sequence, Long parentSequence, String kind, String operation,
                       JsonNode argumentsBefore, JsonNode argumentsAfter, Outcome outcome,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<String> callerPath) {
        public Call {
            callerPath = callerPath == null ? null : List.copyOf(callerPath);
        }
        public Call(long sequence, Long parentSequence, String kind, String operation,
                    JsonNode argumentsBefore, JsonNode argumentsAfter, Outcome outcome) {
            this(sequence, parentSequence, kind, operation, argumentsBefore, argumentsAfter, outcome, null);
        }
    }

    /**
     * 메서드 또는 최초 업무 실행의 반환값과 실패 정보를 담는다.
     * 정상 종료는 value에 JSON 결과를, 예외 종료는 failure에 예외 정보를 저장한다.
     * 이를 통해 정상적인 null 반환과 예외 종료를 구분한다.
     */
    public record Outcome(JsonNode value, Failure failure) {}

    /**
     * 비교와 재생에 필요한 예외 타입·메시지·원인 체인을 저장한다.
     * 원인은 최대 8단계까지만 수집하며 스택 트레이스나 예외 객체의 모든 필드는 저장하지 않는다.
     * 재생 시 {@link ReplayValues}가 이 정보로 예외 복원을 시도한다.
     */
    public record Failure(String type, String message, Failure cause) {
        public static Failure from(Throwable error) { return from(error, 0); }
        private static Failure from(Throwable error, int depth) {
            if (error == null || depth == 8) return null;
            return new Failure(error.getClass().getName(), error.getMessage(), from(error.getCause(), depth + 1));
        }
    }
}
