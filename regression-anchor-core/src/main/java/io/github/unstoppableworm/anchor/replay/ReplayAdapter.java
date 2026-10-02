package io.github.unstoppableworm.anchor.replay;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;

/**
 * 저장된 외부 호출 한 건을 실제 호출 없이 복원하는 core의 확장 인터페이스다.
 * 기록된 반환값을 런타임 객체로 만들고, 필요하면 전달된 인자의 변경과 예외도 재현한다.
 * 엔진의 {@code captureBoundary}가 replay에서 실제 Invocation 대신 호출한다.
 * Spring 메서드의 타입 해석과 기본 구현 선택은 starter가 담당한다.
 */
@FunctionalInterface
public interface ReplayAdapter<T> {
    T restore(Snapshot.Call recorded, Object[] arguments, SnapshotCodec codec) throws Exception;
}
