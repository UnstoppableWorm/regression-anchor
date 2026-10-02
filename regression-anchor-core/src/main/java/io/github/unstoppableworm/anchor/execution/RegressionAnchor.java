package io.github.unstoppableworm.anchor.execution;

import io.github.unstoppableworm.anchor.replay.ReplayAdapter;
import io.github.unstoppableworm.anchor.replay.ReplayMismatchException;
import io.github.unstoppableworm.anchor.replay.ReplayValues;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import io.github.unstoppableworm.anchor.verification.CallAlignment;
import io.github.unstoppableworm.anchor.verification.Difference;
import io.github.unstoppableworm.anchor.verification.SnapshotComparator;
import io.github.unstoppableworm.anchor.verification.Verification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 회귀 테스트 한 건의 기록·실행·비교와 자식 스레드 실행 맥락을 관리하는 엔진이다.
 * 애플리케이션은 caseId, 최초 입력, 실행할 업무 메서드를 전달하고,
 * starter의 인터셉터는 실행 중 만나는 외부 호출을 이 엔진에 전달한다.
 * {@code record}는 실제 실행을 기준으로 저장한다.
 * {@code replay}는 현재 업무 코드를 실행하되 외부 호출 결과를 기록값으로 복원한다.
 * 기록과 같은 전체 호출 순서·외부 작업 식별자·입력을 요구하며 중간 업무 호출 경로는 비교하지 않는다.
 * 재생 시 대응한 호출의 입력을 확인하고, 실행 후 출력과 최종 결과·공유 상태까지 비교한다.
 * JSON 변환은 {@link SnapshotCodec}, 저장 위치와 I/O는 소비자의 {@link SnapshotStore}에 맡긴다.
 * 상속 가능한 스레드 로컬로 새 자식 스레드에 실행 범위를 자동 전달하며 중첩 실행은 허용하지 않는다.
 * 호출 목록은 실행별로 공유하되 스택·억제 상태는 스레드별로 분리한다. 업무 함수는 자식 작업을 마친 뒤 반환해야 한다.
 * 호출 매칭과 종료 검사는 세션 잠금 안에서 수행하고, 실제 업무·의존성 실행은 잠금 밖에서 수행한다.
 * Spring 프로필에 따른 활성화와 모드 제한 설정은 starter가 담당한다.
 */
public final class RegressionAnchor {
    /**
     * 회귀 테스트의 시작점이 되는 업무 함수를 받는 콜백이다.
     * 최초 입력을 받아 결과를 반환하며, 예를 들어 {@code orderService::place}를 전달한다.
     * record·replay 모두 이 업무 함수 자체는 실행한다.
     */
    @FunctionalInterface public interface Trigger<I, O> { O run(I input) throws Exception; }
    /**
     * 계측 대상 메서드의 실제 호출을 지연 실행하기 위한 내부 연결용 콜백이다.
     * 인터셉터가 원래 메서드 호출을 감싸서 전달하며, 재생 가능한 외부 경계에서는 replay 시 실행하지 않는다.
     * AOP 호출에서 발생하는 예외를 그대로 다루기 위해 Throwable을 허용한다.
     */
    @FunctionalInterface public interface Invocation<T> { T run() throws Throwable; }

    private final SnapshotCodec codec;
    private final SnapshotStore store;
    private final SnapshotComparator comparator;
    private final List<SharedState<?>> sharedStates;
    private final ExecutionMode mode;
    private final ReentrantLock sharedLock = new ReentrantLock();
    private final Set<Session> replaying = ConcurrentHashMap.newKeySet();
    private final InheritableThreadLocal<ThreadContext> current = new InheritableThreadLocal<>() {
        @Override protected ThreadContext childValue(ThreadContext parent) {
            return parent == null ? null : parent.child();
        }
    };

    public RegressionAnchor(SnapshotCodec codec, SnapshotStore store) { this(codec, store, new SnapshotComparator()); }
    public RegressionAnchor(SnapshotCodec codec, SnapshotStore store, SnapshotComparator comparator) {
        this(codec, store, comparator, List.of());
    }
    public RegressionAnchor(SnapshotCodec codec, SnapshotStore store, SnapshotComparator comparator, List<SharedState<?>> sharedStates) {
        this(codec, store, comparator, sharedStates, ExecutionMode.MANUAL);
    }
    public RegressionAnchor(SnapshotCodec codec, SnapshotStore store, SnapshotComparator comparator, List<SharedState<?>> sharedStates, ExecutionMode mode) {
        this.codec = codec; this.store = store; this.comparator = comparator;
        this.mode = Objects.requireNonNull(mode);
        this.sharedStates = List.copyOf(sharedStates);
        var keys = new HashSet<String>();
        for (var state : this.sharedStates) {
            if (state.key() == null || state.key().isBlank() || !keys.add(state.key()))
                throw new IllegalArgumentException("Shared state keys must be nonblank and unique: " + state.key());
        }
    }

    public boolean isActive() {
        ThreadContext context = current.get();
        return context != null && context.handle.session != null && context.suppressed == 0;
    }
    public SnapshotCodec codec() { return codec; }
    /** Configured default/restriction, not the mode of a currently running invocation. */
    public ExecutionMode mode() { return mode; }
    public boolean isReplaying() {
        ThreadContext context = current.get();
        Session session = context == null ? null : context.handle.session;
        return session != null && context.suppressed == 0 && session.expected != null;
    }

    /** Uses the fixed engine mode; a MANUAL engine requires the overload with an explicit mode. */
    public <I, O> O run(String caseId, I input, Trigger<I, O> trigger) throws Exception {
        if (mode == ExecutionMode.MANUAL)
            throw new IllegalStateException("Pass RECORD or REPLAY to run(mode, caseId, input, trigger)");
        return run(mode, caseId, input, trigger);
    }

    /** Selects this invocation's behavior without changing the engine's fixed mode or other scopes. */
    public <I, O> O run(ExecutionMode executionMode, String caseId, I input, Trigger<I, O> trigger) throws Exception {
        if (executionMode == null || executionMode == ExecutionMode.MANUAL)
            throw new IllegalArgumentException("Execution mode must be RECORD or REPLAY");
        return switch (executionMode) {
            case RECORD -> record(caseId, input, trigger);
            case REPLAY -> {
                var result = replayInput(caseId, input, trigger).assertMatches();
                ReplayValues.throwIfFailed(result.actual().outcome().failure());
                yield result.value();
            }
            case MANUAL -> throw new IllegalArgumentException("Execution mode must be RECORD or REPLAY");
        };
    }

    private void requireMode(ExecutionMode requested) {
        if (mode != ExecutionMode.MANUAL && mode != requested)
            throw new IllegalStateException("Operation is forbidden in " + mode + " mode");
    }

    /** Interceptors call this before allowing an unscoped invocation to reach infrastructure. */
    public void requireReplayScope() {
        ThreadContext context = current.get();
        if (context != null) {
            context.session(); // An inherited but expired scope must never fall through to live I/O.
            return;
        }
        if (mode == ExecutionMode.REPLAY || !replaying.isEmpty()) {
            var failure = new IllegalStateException("Dependency access requires an active replay scope; context was not propagated");
            // With no context we cannot attribute the call safely. Fail all active replays, never guess an owner.
            replaying.forEach(session -> fail(session, failure));
            throw failure;
        }
    }

    private void enterScope() {
        ThreadContext context = current.get();
        if ((context != null && context.handle.session != null) || sharedLock.isHeldByCurrentThread())
            throw new IllegalStateException("Nested record/replay scopes are not supported");
        current.remove(); // A new explicit root execution may replace an expired inherited scope.
        if (!sharedStates.isEmpty()) sharedLock.lock();
    }
    private void exitScope() { if (!sharedStates.isEmpty()) sharedLock.unlock(); }

    public <I, O> O record(String caseId, I input, Trigger<I, O> trigger) throws Exception {
        requireMode(ExecutionMode.RECORD);
        enterScope();
        try {
            if (store.exists(caseId)) throw new IllegalStateException("Baseline already exists: " + caseId + "; choose a new case or explicitly remove the old baseline");
            Run<O> run = execute(caseId, input, trigger, null);
            store.create(caseId, codec.writeJson(run.snapshot()));
            if (run.failure() != null) throw run.failure();
            return run.value();
        } finally { exitScope(); }
    }

    /** Executes business code, substituting recorded boundary results. Never falls back to live I/O. */
    public <I, O> Verification<O> replay(String caseId, Class<I> inputType, Trigger<I, O> trigger) throws Exception {
        return replay(caseId, codec.mapper().constructType(inputType), null, false, trigger);
    }
    public <I, O> Verification<O> replay(String caseId, TypeReference<I> inputType, Trigger<I, O> trigger) throws Exception {
        return replay(caseId, codec.mapper().constructType(inputType), null, false, trigger);
    }

    /** Strictly matches the caller's initial input before invoking any business code. */
    public <I, O> Verification<O> replayInput(String caseId, I input, Trigger<I, O> trigger) throws Exception {
        return replay(caseId, null, input, true, trigger);
    }

    /** Load, decode and validate a baseline without exposing JSON parsing to the storage implementation. */
    public Snapshot loadSnapshot(String caseId) throws Exception {
        byte[] json = store.load(caseId);
        Snapshot loaded = json == null ? null : codec.readJson(json, Snapshot.class);
        if (loaded == null || loaded.formatVersion() < 1 || loaded.formatVersion() > Snapshot.FORMAT_VERSION
                || !Objects.equals(caseId, loaded.caseId()))
            throw new IllegalStateException("Unsupported or inconsistent snapshot: " + caseId);
        if (loaded.formatVersion() >= 3) {
            for (int i = 0; i < loaded.calls().size(); i++) {
                var call = loaded.calls().get(i);
                if (call.sequence() != i + 1L
                        || (call.parentSequence() != null && (call.parentSequence() < 1 || call.parentSequence() >= call.sequence())))
                    throw new IllegalStateException("Inconsistent call sequence or parent at /calls/" + i + "; record a new baseline");
            }
        }
        return loaded;
    }

    /** Export a library-formatted report to a consumer-selected destination, e.g. per refactoring version. */
    public void saveReport(Verification<?> result, SnapshotStore destination) throws Exception {
        destination.saveReport(result.actual().caseId(), codec.writeJson(result.report()));
    }

    private <I, O> Verification<O> replay(String caseId, JavaType inputType, I suppliedInput, boolean supplied,
                                         Trigger<I, O> trigger) throws Exception {
        requireMode(ExecutionMode.REPLAY);
        enterScope();
        try {
            Snapshot expected = loadSnapshot(caseId);
            var keys = sharedStates.stream().map(SharedState::key).collect(Collectors.toSet());
            if (!keys.equals(expected.sharedInputs().keySet()) || !keys.equals(expected.sharedOutputs().keySet()))
                throw new IllegalStateException("Shared state providers do not match the baseline: expected " + expected.sharedInputs().keySet() + ", registered " + keys);
            if (supplied && !expected.input().equals(codec.freeze(suppliedInput)))
                throw new ReplayMismatchException("Replay initial input differs at /input for " + caseId);
            I input = supplied ? suppliedInput : codec.mapper().convertValue(expected.input(), inputType);
            Run<O> run;
            try (var scope = new SharedStateScope(codec, sharedStates, expected.sharedInputs())) {
                run = execute(caseId, input, trigger, expected);
            }
            List<Difference> differences = comparator.compare(expected, run.snapshot(), codec);
            var result = new Verification<>(run.value(), run.snapshot(), differences);
            saveReport(result, store);
            return result;
        } finally { exitScope(); }
    }

    private <I, O> Run<O> execute(String caseId, I input, Trigger<I, O> trigger, Snapshot expected) throws Exception {
        if (isActive()) throw new IllegalStateException("Nested record/replay scopes are not supported");
        Map<String, JsonNode> sharedInputs = SharedStateScope.capture(codec, sharedStates);
        Session session = new Session(expected);
        JsonNode frozenInput = codec.freeze(input);
        current.set(new ThreadContext(session.handle, null, 0));
        if (expected != null) replaying.add(session);
        try {
            O value = null;
            Exception failure = null;
            try { value = trigger.run(input); }
            catch (Exception e) { failure = e; }
            List<Snapshot.Call> calls = completeCalls(session);
            Snapshot.Outcome outcome = outcome(value, failure);
            // Shared-state adapters run outside interception: they must only access the consumer's local state.
            current.remove();
            Map<String, JsonNode> sharedOutputs = SharedStateScope.capture(codec, sharedStates);
            Snapshot snapshot = new Snapshot(expected == null ? Snapshot.FORMAT_VERSION : expected.formatVersion(), caseId, frozenInput, outcome,
                    calls, sharedInputs, sharedOutputs);
            return new Run<>(value, failure, snapshot);
        } finally {
            synchronized (session) { session.handle.session = null; }
            replaying.remove(session);
            current.remove();
        }
    }

    private List<Snapshot.Call> completeCalls(Session session) {
        synchronized (session) {
            // Closing the scope and admitting a new call use the same lock.
            session.handle.session = null;
            if (session.inFlight != 0)
                fail(session, new IllegalStateException("Regression trigger returned with unfinished dependency calls; await child tasks before returning"));
            if (session.captureFailure != null) throw session.captureFailure;
            if (session.expected != null && session.calls.size() != session.expected.calls().size())
                throw new ReplayMismatchException("Replay ended with missing calls at /calls/" + session.calls.size());
            return session.calls.stream().map(PendingCall::freeze).toList();
        }
    }

    public <T> T capture(String kind, String operation, Object[] arguments, Invocation<T> invocation) throws Throwable {
        return capture(kind, operation, arguments, null, invocation);
    }

    public <T> T captureBoundary(String kind, String operation, Object[] arguments, ReplayAdapter<T> adapter, Invocation<T> invocation) throws Throwable {
        if (adapter == null) throw new IllegalArgumentException("A replay adapter is required for a boundary");
        return capture(kind, operation, arguments, adapter, invocation);
    }

    /** Treat an explicit gateway and its implementation as one replayable boundary. */
    public <T> T captureOpaqueBoundary(String kind, String operation, Object[] arguments, ReplayAdapter<T> adapter, Invocation<T> invocation) throws Throwable {
        return captureBoundary(kind, operation, arguments, adapter, () -> {
            ThreadContext context = current.get();
            if (context == null) return invocation.run();
            context.suppressed++;
            try { return invocation.run(); }
            finally { context.suppressed--; }
        });
    }

    private <T> T capture(String kind, String operation, Object[] arguments, ReplayAdapter<T> adapter, Invocation<T> invocation) throws Throwable {
        ThreadContext context = current.get();
        if (context == null || context.suppressed > 0) {
            requireReplayScope();
            return invocation.run();
        }
        Session session = context.session();
        PendingCall call;
        Snapshot.Call recorded = null;
        synchronized (session) {
            context.session(); // Closing the scope and admitting a new call are mutually exclusive.
            if (session.captureFailure != null) throw session.captureFailure;
            call = new PendingCall(session.calls.size() + 1L, context.parent(), kind, operation,
                    session.expected == null || session.expected.formatVersion() >= 3 ? List.of() : null);
            call.argumentsBefore = freezeInSession(session, arguments);
            if (session.expected != null) recorded = matchRecordedCall(session, call, adapter != null);
            session.calls.add(call);
            session.inFlight++;
            context.stack.push(call.sequence);
        }
        boolean restoring = recorded != null && adapter != null;
        // Do not hold the session lock while executing user/SDK code: it may await a child thread.
        try {
            T value;
            try { value = restoring ? adapter.restore(recorded, arguments, codec) : invocation.run(); }
            catch (Throwable failure) {
                call.outcome = new Snapshot.Outcome(codec.freeze(null), Snapshot.Failure.from(failure));
                if (restoring && !Objects.equals(recorded.outcome().failure(), call.outcome.failure())) {
                    RuntimeException error = unsupported("Cannot reconstruct replay result for " + operation);
                    error.initCause(failure);
                    throw error;
                }
                throw failure;
            }
            call.outcome = new Snapshot.Outcome(freezeInSession(session, value), null);
            if (restoring && !recorded.outcome().equals(call.outcome))
                throw unsupported("Replay result reconstruction differs for " + operation);
            return value;
        } finally {
            try {
                call.argumentsAfter = freezeInSession(session, arguments);
                if (restoring && !recorded.argumentsAfter().equals(call.argumentsAfter))
                    throw unsupported("Replay argument reconstruction differs for " + operation);
            } finally {
                context.stack.pop();
                synchronized (session) { session.inFlight--; }
            }
        }
    }

    // Called under the session lock, before adding the call or executing any dependency.
    private Snapshot.Call matchRecordedCall(Session session, PendingCall call, boolean replayBoundary) {
        int index = session.calls.size();
        Snapshot.Call recorded;
        String path = "/calls/" + index;
        if (index >= session.expected.calls().size()) throw mismatch(session, "Unexpected extra call at " + path + ": " + call.operation);
        recorded = session.alignment.match(index, call.freeze());
        if (recorded == null) throw mismatch(session, "Replay call identity/order differs at "
                + path + ": expected " + session.expected.calls().get(index).kind() + "/"
                + session.expected.calls().get(index).operation() + ", actual " + call.kind + "/" + call.operation);
        if (!recorded.argumentsBefore().equals(call.argumentsBefore))
            throw mismatch(session, "Replay input differs at " + path + "/argumentsBefore: expected " + recorded.argumentsBefore() + ", actual " + call.argumentsBefore);
        // Parent metadata is not identity, but prevents substituting an outer boundary and skipping its children.
        if (replayBoundary && session.expectedParents.contains(recorded.sequence()))
            throw mismatch(session, "Cannot replay a boundary with nested recorded calls at " + path);
        return recorded;
    }

    private ReplayMismatchException mismatch(Session session, String message) {
        var error = new ReplayMismatchException(message);
        fail(session, error);
        return error;
    }

    /** Fail the entire scope even if application code catches an instrumentation exception. */
    public RuntimeException unsupported(String message) {
        IllegalStateException error = new IllegalStateException(message);
        ThreadContext context = current.get();
        Session session = context == null ? null : context.handle.session;
        if (session != null) fail(session, error);
        return error;
    }

    private JsonNode freezeInSession(Session session, Object value) {
        try { return codec.freeze(value); }
        catch (RuntimeException error) { fail(session, error); throw error; }
    }

    private static void fail(Session session, RuntimeException failure) {
        synchronized (session) { if (session.captureFailure == null) session.captureFailure = failure; }
    }

    private Snapshot.Outcome outcome(Object value, Throwable failure) {
        return new Snapshot.Outcome(codec.freeze(failure == null ? value : null), Snapshot.Failure.from(failure));
    }

    /**
     * 한 번의 업무 실행에서 얻은 반환값, 업무 예외, 완성된 스냅샷을 묶는 내부 결과다.
     * 기록 저장이나 기준 비교를 마친 뒤 결과를 반환하거나 예외를 다시 던질 때 사용한다.
     */
    private record Run<T>(T value, Exception failure, Snapshot snapshot) {}
    /**
     * 루트와 자식 스레드가 공유하는 한 회귀 실행의 상태를 보관한다.
     * 호출 순번 할당·목록 변경·실패 전달·종료 검사는 이 객체의 잠금으로 보호한다.
     * 실제 외부 호출은 잠금 밖에서 실행하며 스택과 억제 상태는 ThreadContext가 따로 보관한다.
     * 계측 실패를 별도로 기억하므로 업무 코드가 예외를 잡아도 잘못된 회귀 실행이 성공 처리되지 않는다.
     */
    private static final class Session {
        final Snapshot expected;
        final CallAlignment alignment;
        final Set<Long> expectedParents;
        Session(Snapshot expected) {
            this.expected = expected;
            this.alignment = expected == null ? null : new CallAlignment(expected.calls());
            this.expectedParents = expected == null ? Set.of() : expected.calls().stream()
                    .map(Snapshot.Call::parentSequence).filter(Objects::nonNull).collect(Collectors.toSet());
        }
        final List<PendingCall> calls = new ArrayList<>();
        final ScopeHandle handle = new ScopeHandle(this);
        RuntimeException captureFailure;
        int inFlight;
    }

    /** 종료 시 큰 스냅샷 상태 참조를 해제하고 늦게 실행된 자식에는 종료 표지만 남기는 공유 핸들이다. */
    private static final class ScopeHandle {
        volatile Session session;
        ScopeHandle(Session session) { this.session = session; }
    }

    /**
     * 스레드마다 독립적인 캡처 스택·억제 상태와 상속받은 부모 호출 순번을 보관한다.
     * 자식 생성 시 핸들과 맥락만 전달하며 형제 스레드끼리 부모 스택이나 억제 횟수를 공유하지 않는다.
     * 종료된 범위는 외부 I/O로 흘려보내지 않고 거부한다.
     */
    private static final class ThreadContext {
        final ScopeHandle handle;
        final Long inheritedParent;
        final ArrayDeque<Long> stack = new ArrayDeque<>();
        int suppressed;
        ThreadContext(ScopeHandle handle, Long inheritedParent, int suppressed) {
            this.handle = handle; this.inheritedParent = inheritedParent; this.suppressed = suppressed;
        }
        Session session() {
            Session session = handle.session;
            if (session == null) throw new IllegalStateException("Regression scope has already ended; dependency access is forbidden");
            return session;
        }
        Long parent() { return stack.isEmpty() ? inheritedParent : stack.peek(); }
        ThreadContext child() {
            return new ThreadContext(handle, parent(), suppressed);
        }
    }
    /**
     * 메서드 진입부터 종료까지 입력·출력 정보를 차례로 채우는 내부 호출 기록이다.
     * 진입 시 순번·부모·입력을 저장하고, 종료 시 변경된 인자와 반환값 또는 예외를 저장한다.
     * 실행이 끝나면 저장용 {@link Snapshot.Call}로 변환한다.
     */
    private static final class PendingCall {
        final long sequence;
        final Long parent;
        final String kind, operation;
        final List<String> callerPath;
        JsonNode argumentsBefore, argumentsAfter;
        Snapshot.Outcome outcome;
        PendingCall(long sequence, Long parent, String kind, String operation, List<String> callerPath) {
            this.sequence = sequence; this.parent = parent; this.kind = kind; this.operation = operation;
            this.callerPath = callerPath;
        }
        Snapshot.Call freeze() { return new Snapshot.Call(sequence, parent, kind, operation, argumentsBefore, argumentsAfter, outcome, callerPath); }
    }
}
