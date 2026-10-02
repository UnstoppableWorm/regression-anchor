package io.github.unstoppableworm.anchor.verification;

import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 기준 JSON과 현재 JSON을 재귀적으로 비교하여 경로별 {@link Difference}를 만든다.
 * 객체는 키별, 배열은 순서대로 비교하며 누락된 필드와 명시적인 null도 구분한다.
 * Snapshot 비교에서는 모든 지원 버전에 대해 호출 경로·부모 관계만 제외하고 전체 순번대로 비교한다.
 * 설정한 JSON Pointer 경로는 비교에서 제외할 수 있고, 별표는 경로 한 조각과 일치한다.
 * 이 제외 규칙은 실행 후 비교에만 적용되며 replay의 외부 작업 식별자·입력·전체 호출 순서 검사를 완화하지 않는다.
 */
public final class SnapshotComparator {
    private final Set<String> ignoredPaths;

    public SnapshotComparator() { this(Set.of()); }
    /** JSON Pointer paths. A whole segment '*' matches one array index or field name. */
    public SnapshotComparator(Set<String> ignoredPaths) { this.ignoredPaths = Set.copyOf(ignoredPaths); }

    public List<Difference> compare(JsonNode expected, JsonNode actual) {
        List<Difference> differences = new ArrayList<>();
        compare("", expected, actual, differences);
        return List.copyOf(differences);
    }

    /** 저장 형식은 유지하며 업무 구조에만 의존하는 호출 메타데이터를 비교에서 제외한다. */
    public List<Difference> compare(Snapshot expected, Snapshot actual, SnapshotCodec codec) {
        return compare(comparableSnapshot(expected, codec), comparableSnapshot(actual, codec));
    }

    private JsonNode comparableSnapshot(Snapshot snapshot, SnapshotCodec codec) {
        JsonNode tree = codec.freeze(snapshot);
        for (JsonNode call : tree.path("calls")) {
            ((ObjectNode) call).remove(List.of("callerPath", "parentSequence"));
        }
        return tree;
    }

    private void compare(String path, JsonNode expected, JsonNode actual, List<Difference> differences) {
        if (ignoredPaths.stream().anyMatch(pattern -> matches(pattern, path)) || expected.equals(actual)) return;
        if (expected.isObject() && actual.isObject()) {
            Set<String> fields = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(fields::add);
            actual.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) compare(path + "/" + escape(field), expected.path(field), actual.path(field), differences);
        } else if (expected.isArray() && actual.isArray()) {
            for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
                compare(path + "/" + i, at(expected, i), at(actual, i), differences);
            }
        } else differences.add(new Difference(path, expected, actual));
    }

    private JsonNode at(JsonNode node, int i) { return i < node.size() ? node.get(i) : MissingNode.getInstance(); }
    private String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private boolean matches(String pattern, String path) {
        String[] left = pattern.split("/", -1), right = path.split("/", -1);
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) if (!left[i].equals("*") && !left[i].equals(right[i])) return false;
        return true;
    }
}
