package io.github.unstoppableworm.anchor.snapshot;

import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.testsupport.TestSnapshotStore;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AnchorPropertyTest {
    final SnapshotCodec codec = new SnapshotCodec();
    final TestSnapshotStore store = new TestSnapshotStore();
    TestSnapshotStore store() { return store; }
    RegressionAnchor anchor() { return new RegressionAnchor(codec, store()); }

    @Test void reusesRecordedTriggerAndNestedCallsAcrossFieldRenames() throws Exception {
        var anchor = anchor();
        anchor.record("renamed", new OriginalRequest("customer-1", List.of(new OriginalLine("BOOK", 2))),
                request -> call(anchor, request, new OriginalResult(24000)));
        byte[] before = store.baselines.get("renamed");
        var result = anchor.replay("renamed", RenamedRequest.class, request -> {
            assertThat(request.buyerId()).isEqualTo("customer-1");
            assertThat(request.items().getFirst().productCode()).isEqualTo("BOOK");
            assertThat(request.items().getFirst().units()).isEqualTo(2);
            return call(anchor, request, new RenamedResult(24000));
        }).assertMatches();
        assertThat(result.actual().outcome().value().toString()).isEqualTo("{\"total\":24000}");
        assertThat(store.baselines.get("renamed")).isEqualTo(before);
    }

    @Test void canAdoptOldUnannotatedBaselineByUsingItsExistingKey() throws Exception {
        var anchor = anchor();
        anchor.record("legacy", new LegacyResult(100), value -> value);
        anchor.replay("legacy", RenamedResult.class, value -> {
            assertThat(value.payableAmount()).isEqualTo(100);
            return value;
        }).assertMatches();
    }

    @Test void renamedFieldStillReportsChangedValueAtTheStablePath() throws Exception {
        var anchor = anchor();
        anchor.record("drift", 100, OriginalResult::new);
        var changed = anchor.replay("drift", Integer.class, value -> new RenamedResult(value + 1));
        assertThat(changed.matches()).isFalse();
        assertThat(changed.differences()).singleElement().satisfies(difference -> {
            assertThat(difference.path()).isEqualTo("/outcome/value/total");
            assertThat(difference.expected().asInt()).isEqualTo(100);
            assertThat(difference.actual().asInt()).isEqualTo(101);
        });
    }

    @Test void unannotatedRenameAndChangedAnchorRemainDifferences() throws Exception {
        var anchor = anchor();
        anchor.record("names", 100, OriginalResult::new);
        assertThat(anchor.replay("names", Integer.class, UnanchoredResult::new).matches()).isFalse();
        assertThat(anchor.replay("names", Integer.class, WrongAnchorResult::new).matches()).isFalse();
    }

    @Test void capturesRenamedMutablePojoBeforeAndAfterGeneratedKeyMutation() throws Exception {
        var anchor = anchor();
        anchor.record("mutation", 1L, id -> {
            var row = new OriginalRow();
            return capture(anchor, row, () -> { row.setId(id); return row; });
        });
        var result = anchor.replay("mutation", Long.class, id -> {
            var row = new RenamedRow();
            return capture(anchor, row, () -> { row.setDatabaseKey(id); return row; });
        }).assertMatches();
        assertThat(result.actual().calls().getFirst().argumentsBefore().at("/0/id").isNull()).isTrue();
        assertThat(result.actual().calls().getFirst().argumentsAfter().at("/0/id").asLong()).isEqualTo(1);
    }

    @Test void supportsPrivateInheritedFieldsAndBeanAccessors() {
        var pojo = new InheritedPojo();
        pojo.setDisplayName("Ada");
        pojo.active = true;
        var tree = codec.freeze(pojo);
        assertThat(tree.toString()).isEqualTo("{\"active\":true,\"customerName\":\"Ada\"}");
        var restored = codec.mapper().convertValue(tree, InheritedPojo.class);
        assertThat(restored.getDisplayName()).isEqualTo("Ada");
    }

    @Test void supportsFieldsWithAccessorsAndCreatorAnnotations() {
        var bean = new AccessorPojo();
        bean.setLabel("Ada");
        assertThat(codec.freeze(bean).toString()).isEqualTo("{\"customerName\":\"Ada\"}");
        assertThat(codec.mapper().convertValue(codec.freeze(bean), AccessorPojo.class).getLabel()).isEqualTo("Ada");
        var immutable = codec.mapper().convertValue(Map.of("customerName", "Ada"), ImmutablePojo.class);
        assertThat(immutable.label).isEqualTo("Ada");
        assertThat(codec.freeze(immutable).toString()).isEqualTo("{\"customerName\":\"Ada\"}");
    }

    @Test void customCodecUsesLiteralAnchorsWithoutChangingTheApplicationMapper() throws Exception {
        var appMapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING).build();
        var dedicated = new SnapshotCodec(appMapper);
        var dto = new ApiDto("Ada", 2);
        assertThat(appMapper.writeValueAsString(dto)).contains("\"api_name\"").doesNotContain("stableCustomerName");
        var snapshot = dedicated.freeze(dto);
        assertThat(snapshot.toString()).contains("\"stableCustomerName\":\"Ada\"", "\"item_count\":2")
                .doesNotContain("api_name", "stable_customer_name");
        assertThat(dedicated.mapper().convertValue(snapshot, ApiDto.class)).isEqualTo(dto);
        assertThat(appMapper.isEnabled(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING)).isTrue();
    }

    @Test void preservesNullValuesAndMapKeysWhileAnchoringNestedValues() {
        var value = Map.of("rawMapKey", List.of(new NullableName(null)));
        assertThat(codec.freeze(value).toString()).isEqualTo("{\"rawMapKey\":[{\"name\":null}]}");
    }

    @Test void fieldAnchorAlsoOverridesApiNamesOnBeanAccessors() throws Exception {
        var value = new ApiPojo();
        value.setLabel("Ada");
        assertThat(new JsonMapper().writeValueAsString(value)).isEqualTo("{\"api_name\":\"Ada\"}");
        assertThat(codec.freeze(value).toString()).isEqualTo("{\"customerName\":\"Ada\"}");
        assertThat(codec.mapper().convertValue(Map.of("customerName", "Ada"), ApiPojo.class).getLabel()).isEqualTo("Ada");
    }

    @Test void fieldAnchorAppliesToNamedCreatorParameterWithApiName() {
        var value = new FieldOnlyCreator("Ada");
        assertThat(codec.freeze(value).toString()).isEqualTo("{\"customerName\":\"Ada\"}");
        assertThat(codec.mapper().convertValue(Map.of("customerName", "Ada"), FieldOnlyCreator.class).label).isEqualTo("Ada");
    }

    @Test void rejectsBlankAnchorsAndNeverWritesAPartialBaseline() {
        var anchor = anchor();
        assertThatThrownBy(() -> anchor.record("blank", 1, i -> new BlankAnchor(i)))
                .isInstanceOf(IllegalArgumentException.class).hasStackTraceContaining("@Anchor value must not be blank");
        assertThat(store().exists("blank")).isFalse();
        assertThat(anchor.isActive()).isFalse();
    }

    @Test void rejectsDuplicateAnchorsAndCollisionsWithUnannotatedKeys() {
        assertThatThrownBy(() -> codec.freeze(new DuplicateAnchors("a", "b"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.freeze(new CollidingField())).isInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Duplicate snapshot key 'existing'");
        assertThatThrownBy(() -> codec.mapper().convertValue(Map.of("existing", "x"), CollidingField.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsConflictingFieldAndCreatorAnchors() {
        assertThatThrownBy(() -> codec.mapper().convertValue(Map.of("fieldName", "value"), ConflictingPojo.class)).isInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Conflicting @Anchor");
    }

    @Test void classAndMethodMarkersDoNotRenameJsonProperties() {
        assertThat(codec.freeze(new CaptureMarkedPojo()).toString()).isEqualTo("{\"label\":\"Ada\"}");
        assertThat(codec.mapper().convertValue(Map.of("label", "Grace"), CaptureMarkedPojo.class).getLabel()).isEqualTo("Grace");
    }

    @Anchor static class CaptureMarkedPojo {
        private String label = "Ada";
        @Anchor public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    record OriginalRequest(@Anchor("customerId") String customerId, @Anchor("lines") List<OriginalLine> lines) {}
    record OriginalLine(@Anchor("sku") String sku, @Anchor("quantity") int quantity) {}
    record RenamedRequest(@Anchor("customerId") String buyerId, @Anchor("lines") List<RenamedLine> items) {}
    record RenamedLine(@Anchor("sku") String productCode, @Anchor("quantity") int units) {}
    record OriginalResult(@Anchor("total") int total) {}
    record RenamedResult(@Anchor("total") int payableAmount) {}
    record LegacyResult(int total) {}
    record UnanchoredResult(int payableAmount) {}
    record WrongAnchorResult(@Anchor("wrong") int payableAmount) {}
    record NullableName(@Anchor("name") String label) {}
    record ApiDto(@Anchor("stableCustomerName") @JsonProperty("apiName") String displayName, int itemCount) {}
    record BlankAnchor(@Anchor(" ") int value) {}
    record DuplicateAnchors(@Anchor("same") String first, @Anchor("same") String second) {}
    static class CollidingField {
        @Anchor("existing") public String renamed = "one";
        public String existing = "two";
    }
    static class OriginalRow {
        @Anchor("id") private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }
    static class RenamedRow {
        @Anchor("id") private Long databaseKey;
        public Long getDatabaseKey() { return databaseKey; }
        public void setDatabaseKey(Long key) { databaseKey = key; }
    }
    static class BasePojo {
        @Anchor("customerName") private String displayName;
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String name) { displayName = name; }
    }
    static class InheritedPojo extends BasePojo { public boolean active; }
    static class AccessorPojo {
        @Anchor("customerName") private String label;
        @Anchor public String getLabel() { return label; }
        public void setLabel(String value) { label = value; }
    }
    static class ImmutablePojo {
        @Anchor("customerName") public final String label;
        @JsonCreator public ImmutablePojo(@Anchor("customerName") String label) { this.label = label; }
    }
    static class ConflictingPojo {
        @Anchor("fieldName") public final String label;
        @JsonCreator ConflictingPojo(@Anchor("constructorName") String label) { this.label = label; }
    }
    static class ApiPojo {
        @Anchor("customerName") private String label;
        @JsonProperty("api_name") public String getLabel() { return label; }
        @JsonProperty("api_name") public void setLabel(String label) { this.label = label; }
    }
    static class FieldOnlyCreator {
        @Anchor("customerName") public final String label;
        @JsonCreator public FieldOnlyCreator(@JsonProperty("api_name") String label) { this.label = label; }
    }

    private <T> T call(RegressionAnchor anchor, Object input, T result) throws Exception { return capture(anchor, input, () -> result); }
    private <T> T capture(RegressionAnchor anchor, Object input, RegressionAnchor.Invocation<T> invocation) throws Exception {
        try { return anchor.capture("TEST", "stable-operation", new Object[]{input}, invocation); }
        catch (Exception | Error error) { throw error; }
        catch (Throwable error) { throw new RuntimeException(error); }
    }
}
