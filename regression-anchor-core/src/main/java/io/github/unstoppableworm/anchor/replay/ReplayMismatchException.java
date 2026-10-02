package io.github.unstoppableworm.anchor.replay;

import io.github.unstoppableworm.anchor.verification.Verification;

/**
 * 재생할 호출이 기록된 흐름과 맞지 않을 때 발생하는 예외다.
 * 최초 입력 또는 하위 호출의 식별자·입력·전체 호출 순서·횟수가 달라지면 실행을 실패시킨다.
 * 외부 작업의 전체 순서는 유지하지만 중간 업무 메서드의 이름과 호출 경로는 판정하지 않는다.
 * 이 경우 실제 외부 의존성을 호출하는 대체 경로는 없다.
 * 실행 후 최종 출력 차이는 별도로 {@link Verification}에서 확인한다.
 */
public final class ReplayMismatchException extends IllegalStateException {
    public ReplayMismatchException(String message) { super(message); }
}
