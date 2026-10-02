package io.github.unstoppableworm.anchor.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 기준값과 실제값이 달라진 JSON 경로 하나를 표현하는 비교 결과다.
 * path는 차이가 발생한 위치이고 expected와 actual은 그 위치의 JSON 값이다.
 * 누락된 필드를 null과 구분하기 위한 플래그를 함께 직렬화하여 저장된 보고서에서도 차이를 보존한다.
 */
public record Difference(String path, JsonNode expected, JsonNode actual) {
    /** Missing nodes serialize as null plus a flag; reconstruct that distinction when reading reports. */
    @JsonCreator
    public static Difference fromJson(@JsonProperty("path") String path,
                                      @JsonProperty("expected") JsonNode expected,
                                      @JsonProperty("actual") JsonNode actual,
                                      @JsonProperty("expectedMissing") boolean expectedMissing,
                                      @JsonProperty("actualMissing") boolean actualMissing) {
        return new Difference(path, expectedMissing ? MissingNode.getInstance() : expected,
                actualMissing ? MissingNode.getInstance() : actual);
    }

    @JsonProperty
    public boolean expectedMissing() { return expected.isMissingNode(); }
    @JsonProperty
    public boolean actualMissing() { return actual.isMissingNode(); }
}
