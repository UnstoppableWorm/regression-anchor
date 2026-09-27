package io.github.unstoppableworm.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Ordered dependency calls. Sequence numbers distinguish repeated invocations. */
public record Snapshot(int formatVersion, String caseId, JsonNode input, Outcome outcome, List<Call> calls) {
    public static final int FORMAT_VERSION = 1;

    public record Call(long sequence, Long parentSequence, String kind, String operation,
                       JsonNode argumentsBefore, JsonNode argumentsAfter, Outcome outcome) {}

    public record Outcome(JsonNode value, Failure failure) {}

    public record Failure(String type, String message, Failure cause) {
        public static Failure from(Throwable error) { return from(error, 0); }
        private static Failure from(Throwable error, int depth) {
            if (error == null || depth == 8) return null;
            return new Failure(error.getClass().getName(), error.getMessage(), from(error.getCause(), depth + 1));
        }
    }
}
