package io.github.unstoppableworm.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
