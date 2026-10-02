package io.github.unstoppableworm.anchor.verification;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;

import java.util.List;
import java.util.Objects;

/**
 * 저장하거나 응답으로 전달할 회귀 검증 보고서의 표준 JSON 모델이다.
 * 일치 여부, 호출 수, 이번 실행 스냅샷, 차이 목록을 한 문서로 묶는다.
 * 런타임 반환 객체를 별도로 중복 저장하지 않고 actual 안의 JSON 결과를 사용한다.
 * 생성 시 요약값과 상세 데이터가 모순되지 않는지 검사하며, 직렬화는 SnapshotCodec이 담당한다.
 */
public record VerificationReport(boolean matches, int callCount, Snapshot actual, List<Difference> differences) {
    public VerificationReport(Snapshot actual, List<Difference> differences) {
        this(differences.isEmpty(), actual.calls().size(), actual, differences);
    }

    public VerificationReport {
        Objects.requireNonNull(actual, "actual");
        differences = List.copyOf(differences);
        if (matches != differences.isEmpty() || callCount != actual.calls().size())
            throw new IllegalArgumentException("Inconsistent verification report summary");
    }
}
