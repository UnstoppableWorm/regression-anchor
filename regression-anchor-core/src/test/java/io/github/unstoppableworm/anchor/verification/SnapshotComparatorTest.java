package io.github.unstoppableworm.anchor.verification;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 재생 보고서에 쓰는 비교기가 업무 경로를 제외하고 외부 호출의 순번별 결과를 비교하는지 검증한다. */
class SnapshotComparatorTest {
    private final SnapshotCodec codec = new SnapshotCodec();
    private final SnapshotComparator comparator = new SnapshotComparator();

    @Test void detectsChangedOutputsAtTheirGlobalPositions() {
        var expected = snapshot(call(1, "a", "A"), call(2, "b", "B"), call(3, "c", "C"));
        var actual = snapshot(call(1, "b", "B"), call(2, "a", "A"), call(3, "c", "C"));
        assertThat(comparator.compare(expected, actual, codec)).isNotEmpty();
    }

    @Test void ignoresChangedCallersWhenAllObservableValuesAreIdentical() {
        var expected = snapshot(call(1, "a", "A"), call(2, "b", "B"), call(3, "c", "C"));
        var actual = snapshot(call(1, "b", "A"), call(2, "a", "B"), call(3, "c", "C"));
        assertThat(actual.outcome()).isEqualTo(expected.outcome());
        assertThat(actual.calls()).extracting(call -> call.outcome().value().asText()).containsExactly("A", "B", "C");
        assertThat(comparator.compare(expected, actual, codec)).isEmpty();
    }

    @Test void preservesRepeatedCallOrder() {
        var expected = snapshot(call(1, "a", "A1"), call(2, "a", "A2"), call(3, "b", "B1"), call(4, "b", "B2"));
        var actual = snapshot(call(1, "a", "A2"), call(2, "a", "A1"), call(3, "b", "B1"), call(4, "b", "B2"));
        assertThat(comparator.compare(expected, actual, codec)).extracting(Difference::path)
                .containsExactly("/calls/0/outcome/value", "/calls/1/outcome/value");
    }

    @Test void comparesSequenceButIgnoresParentMetadata() {
        var first = call(1, "a", "A");
        var changed = new Snapshot.Call(2, 1L, first.kind(), first.operation(), first.argumentsBefore(),
                first.argumentsAfter(), first.outcome(), first.callerPath());
        assertThat(comparator.compare(snapshot(first), snapshot(changed), codec)).extracting(Difference::path)
                .containsExactly("/calls/0/sequence");
    }

    @Test void reportsMissingAndAdditionalCallsAtTheirActualPositions() {
        var first = call(1, "a", "A");
        var second = call(2, "b", "B");
        assertThat(comparator.compare(snapshot(first, second), snapshot(first), codec)).extracting(Difference::path)
                .containsExactly("/calls/1");
        assertThat(comparator.compare(snapshot(first), snapshot(first, second), codec)).extracting(Difference::path)
                .containsExactly("/calls/1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void ignoresOnlyCallMetadataWithoutMutatingSnapshots(int version) {
        var first = call(1, "old", "A");
        var second = call(2, "old", "B");
        var nested = new Snapshot.Call(2, 1L, second.kind(), second.operation(), second.argumentsBefore(),
                second.argumentsAfter(), second.outcome(), second.callerPath());
        var expected = new Snapshot(version, "case", codec.freeze(1), snapshot().outcome(), List.of(first, nested));
        var actual = new Snapshot(version, "case", codec.freeze(1), expected.outcome(),
                List.of(call(1, "renamed", "A"), call(2, "moved", "B")));
        var frozen = codec.freeze(expected);
        assertThat(comparator.compare(expected, actual, codec)).isEmpty();
        assertThat(codec.freeze(expected)).isEqualTo(frozen);
    }

    @Test void doesNotIgnoreBusinessFieldsNamedLikeCallMetadataOrTheirArrayOrder() {
        var call = call(1, "a", "A");
        var before = codec.freeze(java.util.Map.of("callerPath", List.of("x", "y"), "parentSequence", 1));
        var after = codec.freeze(java.util.Map.of("callerPath", List.of("y", "x"), "parentSequence", 2));
        var expected = new Snapshot.Call(1, null, call.kind(), call.operation(), before, before, call.outcome());
        var actual = new Snapshot.Call(1, null, call.kind(), call.operation(), after, before, call.outcome());
        assertThat(comparator.compare(snapshot(expected), snapshot(actual), codec)).extracting(Difference::path)
                .containsExactlyInAnyOrder("/calls/0/argumentsBefore/callerPath/0",
                        "/calls/0/argumentsBefore/callerPath/1", "/calls/0/argumentsBefore/parentSequence");
        assertThat(comparator.compare(codec.freeze(expected), codec.freeze(actual))).isNotEmpty();
    }

    private Snapshot.Call call(long sequence, String caller, String output) {
        return new Snapshot.Call(sequence, null, "METHOD", "gateway#method1(int)",
                codec.freeze(new Object[]{1}), codec.freeze(new Object[]{1}),
                new Snapshot.Outcome(codec.freeze(output), null),
                List.of("Work#" + caller + "(I)Ljava/lang/String;", "Work#shared(I)Ljava/lang/String;"));
    }

    private Snapshot snapshot(Snapshot.Call... calls) {
        return new Snapshot(Snapshot.FORMAT_VERSION, "case", codec.freeze(1),
                new Snapshot.Outcome(codec.freeze(0), null), List.of(calls));
    }
}
