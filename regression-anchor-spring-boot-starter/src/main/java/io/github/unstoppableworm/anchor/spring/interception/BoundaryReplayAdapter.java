package io.github.unstoppableworm.anchor.spring.interception;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;

import java.lang.reflect.Method;

/**
 * 기본 메서드 타입 해석만으로 복원하기 어려운 외부 응답을 위한 Spring 확장 인터페이스다.
 * 소비자가 빈으로 등록하고 supports에서 담당할 호출 종류와 메서드를 선택한다.
 * restore는 외부 I/O 없이 기록된 반환값·예외·인자 변경을 복원해야 한다.
 * 한 호출에 여러 어댑터가 일치하면 모호한 선택을 막기 위해 실패하며, 없으면 기본 MethodReplay를 사용한다.
 */
public interface BoundaryReplayAdapter {
    boolean supports(String kind, Method method);
    Object restore(String kind, Method method, Snapshot.Call recorded, Object[] arguments, SnapshotCodec codec) throws Exception;
}
