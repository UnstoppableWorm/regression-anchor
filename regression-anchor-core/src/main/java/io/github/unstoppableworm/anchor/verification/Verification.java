package io.github.unstoppableworm.anchor.verification;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;

import java.util.List;
import java.util.stream.Collectors;

/**
 * replay가 반환하는 실행 결과와 비교 결과다.
 * value는 현재 업무 코드의 반환값, actual은 이번 실행 스냅샷, differences는 기준과의 차이다.
 * matches로 일치 여부를 확인하거나 assertMatches로 불일치를 테스트 실패로 바꾼다.
 * report는 라이브러리의 저장용 보고서 모델을 만들며 저장 자체는 엔진과 SnapshotStore가 처리한다.
 */
public record Verification<T>(T value, Snapshot actual, List<Difference> differences) {
    public boolean matches() { return differences.isEmpty(); }
    public VerificationReport report() { return new VerificationReport(actual, differences); }
    public Verification<T> assertMatches() {
        if (!matches()) throw new AssertionError("Regression case '" + actual.caseId() + "' differs at "
                + differences.size() + " path(s):\n" + differences.stream().limit(20)
                .map(d -> d.path() + " expected=" + (d.expectedMissing() ? "<missing>" : d.expected())
                        + " actual=" + (d.actualMissing() ? "<missing>" : d.actual()))
                .collect(Collectors.joining("\n")));
        return this;
    }
}
