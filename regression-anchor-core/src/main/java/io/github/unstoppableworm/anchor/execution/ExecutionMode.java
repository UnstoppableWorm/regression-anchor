package io.github.unstoppableworm.anchor.execution;

/**
 * 엔진의 기본 실행 정책과 {@code run} 호출에서 선택할 기록·재생 모드를 나타낸다.
 * RECORD는 실제 의존성을 호출해 기준을 만들고, REPLAY는 기록된 외부 결과를 사용해 현재 코드를 검증한다.
 * MANUAL은 엔진을 한 모드로 고정하지 않는 정책으로, 호출자가 RECORD 또는 REPLAY를 지정해야 한다.
 * MANUAL 자체는 {@code run}의 실행 모드 인자로 사용할 수 없다.
 */
public enum ExecutionMode { MANUAL, RECORD, REPLAY }
