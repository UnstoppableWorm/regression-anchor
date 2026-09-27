package io.github.unstoppableworm.anchor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Explicit, synchronous recording scopes; inert outside record/verify. */
public final class RegressionAnchor {
    @FunctionalInterface public interface Trigger<I, O> { O run(I input) throws Exception; }
    @FunctionalInterface public interface Invocation<T> { T run() throws Throwable; }

    private final SnapshotCodec codec;
    private final SnapshotStore store;
    private final SnapshotComparator comparator;
    private final ThreadLocal<Session> current = new ThreadLocal<>();

    public RegressionAnchor(SnapshotCodec codec, SnapshotStore store) { this(codec, store, new SnapshotComparator()); }
    public RegressionAnchor(SnapshotCodec codec, SnapshotStore store, SnapshotComparator comparator) {
        this.codec = codec; this.store = store; this.comparator = comparator;
    }

    public boolean isActive() { return current.get() != null; }
    public SnapshotCodec codec() { return codec; }

    public <I, O> O record(String caseId, I input, Trigger<I, O> trigger) throws Exception {
        if (store.exists(caseId)) throw new IllegalStateException("Baseline already exists: " + caseId + "; choose a new case or explicitly remove the old baseline");
        Run<O> run = execute(caseId, input, trigger);
        store.create(run.snapshot());
        if (run.failure() != null) throw run.failure();
        return run.value();
    }

    /** Loads the original trigger input; callers do not maintain expected outputs. */
    public <I, O> Verification<O> verify(String caseId, Class<I> inputType, Trigger<I, O> trigger) throws Exception {
        return verify(caseId, codec.mapper().constructType(inputType), trigger);
    }

    public <I, O> Verification<O> verify(String caseId, TypeReference<I> inputType, Trigger<I, O> trigger) throws Exception {
        return verify(caseId, codec.mapper().constructType(inputType), trigger);
    }

    private <I, O> Verification<O> verify(String caseId, JavaType inputType, Trigger<I, O> trigger) throws Exception {
        Snapshot expected = store.load(caseId);
        I input = codec.mapper().convertValue(expected.input(), inputType);
        Run<O> run = execute(caseId, input, trigger);
        List<Difference> differences = comparator.compare(codec.freeze(expected), codec.freeze(run.snapshot()));
        store.report(run.snapshot(), differences);
        return new Verification<>(run.value(), run.snapshot(), differences);
    }

    private <I, O> Run<O> execute(String caseId, I input, Trigger<I, O> trigger) throws Exception {
        if (isActive()) throw new IllegalStateException("Nested record/verify scopes are not supported");
        Session session = new Session();
        JsonNode frozenInput = codec.freeze(input);
        current.set(session);
        try {
            O value = null;
            Exception failure = null;
            try { value = trigger.run(input); }
            catch (Exception e) { failure = e; }
            if (session.captureFailure != null) throw session.captureFailure;
            Snapshot.Outcome outcome = outcome(value, failure);
            Snapshot snapshot = new Snapshot(Snapshot.FORMAT_VERSION, caseId, frozenInput, outcome,
                    session.calls.stream().map(PendingCall::freeze).toList());
            return new Run<>(value, failure, snapshot);
        } finally { current.remove(); }
    }

    public <T> T capture(String kind, String operation, Object[] arguments, Invocation<T> invocation) throws Throwable {
        Session session = current.get();
        if (session == null) return invocation.run();
        PendingCall call = new PendingCall(session.calls.size() + 1L, session.stack.peek(), kind, operation);
        call.before = freezeInSession(session, arguments);
        session.calls.add(call);
        session.stack.push(call.sequence);
        try {
            T value;
            try { value = invocation.run(); }
            catch (Throwable failure) {
                call.outcome = new Snapshot.Outcome(codec.freeze(null), Snapshot.Failure.from(failure));
                throw failure;
            }
            call.outcome = new Snapshot.Outcome(freezeInSession(session, value), null);
            return value;
        } finally {
            session.stack.pop();
            call.after = freezeInSession(session, arguments);
        }
    }

    /** Fail the entire scope even if application code catches an instrumentation exception. */
    public RuntimeException unsupported(String message) {
        IllegalStateException error = new IllegalStateException(message);
        if (isActive()) current.get().captureFailure = error;
        return error;
    }

    private JsonNode freezeInSession(Session session, Object value) {
        try { return codec.freeze(value); }
        catch (RuntimeException error) { session.captureFailure = error; throw error; }
    }

    private Snapshot.Outcome outcome(Object value, Throwable failure) {
        return new Snapshot.Outcome(codec.freeze(failure == null ? value : null), Snapshot.Failure.from(failure));
    }

    private record Run<T>(T value, Exception failure, Snapshot snapshot) {}
    private static final class Session {
        final List<PendingCall> calls = new ArrayList<>();
        final ArrayDeque<Long> stack = new ArrayDeque<>();
        RuntimeException captureFailure;
    }
    private static final class PendingCall {
        final long sequence;
        final Long parent;
        final String kind, operation;
        JsonNode before, after;
        Snapshot.Outcome outcome;
        PendingCall(long sequence, Long parent, String kind, String operation) {
            this.sequence = sequence; this.parent = parent; this.kind = kind; this.operation = operation;
        }
        Snapshot.Call freeze() { return new Snapshot.Call(sequence, parent, kind, operation, before, after, outcome); }
    }
}
