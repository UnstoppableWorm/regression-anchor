package io.github.unstoppableworm.anchor.execution;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 메서드 인자에 나타나지 않는 인메모리 캐시 등의 공유 입력·출력을 연결하는 소비자 확장점이다.
 * 소비자가 고유 키, 복원 타입, 현재 상태 조회와 전체 상태 교체 방법을 구현한다.
 * 엔진은 실행 전후 상태를 기록하고, replay에서는 기록된 초기 상태로 실행한 뒤 원래 상태를 복구한다.
 * capture는 상태를 바꾸지 않아야 하며 restore는 기존에만 있던 항목의 제거까지 처리해야 한다.
 * 모든 전역 상태를 자동 수집하는 기능은 아니며, 이 어댑터는 로컬 상태만 다룬다.
 */
public interface SharedState<T> {
    /** Stable identity, independent of the Java field/bean name. */
    String key();
    TypeReference<T> type();
    T capture() throws Exception;
    void restore(T value) throws Exception;
}
