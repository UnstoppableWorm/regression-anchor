package io.github.unstoppableworm.anchor.verification;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import java.util.List;

/**
 * 기준과 현재 호출을 전체 목록의 같은 인덱스에서만 대응시키는 내부 매칭 구현이다.
 * 호출 순번·종류·외부 작업 식별자를 검사하며 다른 위치의 기록은 찾지 않는다.
 * 업무 호출 경로와 부모 관계는 리팩터링으로 바뀔 수 있으므로 매칭에 사용하지 않는다.
 * 입력·출력은 실행 엔진과 비교기가 별도로 검증하며 반복 호출도 각각의 순번을 유지한다.
 */
public final class CallAlignment {
    private final List<Snapshot.Call> expected;

    public CallAlignment(List<Snapshot.Call> expected) {
        this.expected = List.copyOf(expected);
    }

    /** 전체 호출 목록의 같은 순번만 검사한다. 입력·출력 값으로 대응 대상을 찾지 않는다. */
    public Snapshot.Call match(int index, Snapshot.Call actual) {
        if (index < 0 || index >= expected.size()) return null;
        Snapshot.Call recorded = expected.get(index);
        return recorded.sequence() == actual.sequence()
                && recorded.kind().equals(actual.kind()) && recorded.operation().equals(actual.operation())
                ? recorded : null;
    }
}
