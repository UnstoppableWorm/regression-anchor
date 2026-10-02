package io.github.unstoppableworm.anchor.storage;

/**
 * 라이브러리가 만든 JSON 바이트의 저장 위치와 I/O를 소비자가 결정하는 인터페이스다.
 * 파일·DB·객체 저장소 등 원하는 구현을 제공하며, JSON 생성과 해석은 라이브러리가 담당한다.
 * load는 원본 바이트를 그대로 반환하고, create는 같은 caseId를 덮어쓰지 않도록 원자적으로 생성해야 한다.
 * saveReport는 완성된 보고서 바이트를 저장하는 선택적 훅이며 기본 구현은 저장하지 않는다.
 * 여러 스레드에서 사용한다면 독립 실행 사이의 동시 접근도 구현체가 지원해야 한다.
 */
public interface SnapshotStore {
    /** Return the original bytes unchanged; throw if absent or unreadable. Never create on read. */
    byte[] load(String caseId) throws Exception;

    /** An early duplicate check; it does not replace the atomic guarantee of create. */
    boolean exists(String caseId) throws Exception;

    /**
     * Persist the supplied bytes unchanged before returning. Atomically reject an existing caseId,
     * including racing creates, without changing its baseline. No partial baseline may be visible.
     */
    void create(String caseId, byte[] snapshotJson) throws Exception;

    /**
     * Optional storage of a complete library-produced report, for matching and differing verifications.
     * Must not modify the baseline. Defaults to no persistence; Verification still contains the result.
     * Storage failures from any method propagate to the caller.
     */
    default void saveReport(String caseId, byte[] reportJson) throws Exception {}
}
