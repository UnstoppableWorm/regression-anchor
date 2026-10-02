package io.github.unstoppableworm.anchor.spring.mongo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonValue;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import java.io.IOException;
import java.util.TreeMap;

/**
 * MongoDB 관련 값을 기록·비교·재생하기 위한 Jackson 변환 규칙을 모은 내부 모듈이다.
 * Query와 UpdateDefinition의 조건·옵션을 직렬화하고 ObjectId와 쓰기 결과의 복원을 지원한다.
 * UpdateResult·DeleteResult의 승인 여부와 처리 수, upsert ID를 보존한다.
 * BSON 값은 Canonical Extended JSON으로 표현하여 숫자 등 BSON 타입 간 차이를 유지한다.
 * 모든 MongoDB 타입을 자동 지원하는 범용 직렬화기는 아니다.
 */
final class MongoSnapshotModule extends SimpleModule {
    MongoSnapshotModule() {
        super("anchor-mongodb");
        addDeserializer(ObjectId.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override public ObjectId deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                return new ObjectId(parser.getValueAsString());
            }
        });
        addDeserializer(UpdateResult.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override public UpdateResult deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                com.fasterxml.jackson.databind.JsonNode tree = parser.readValueAsTree();
                if (!tree.path("acknowledged").asBoolean()) return UpdateResult.unacknowledged();
                var id = tree.path("upsertedId");
                BsonValue bson = id.isNull() || id.isMissingNode() ? null : org.bson.BsonDocument.parse(id.toString()).get("value");
                return UpdateResult.acknowledged(tree.path("matchedCount").asLong(), tree.path("modifiedCount").asLong(), bson);
            }
        });
        addDeserializer(DeleteResult.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override public DeleteResult deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                com.fasterxml.jackson.databind.JsonNode tree = parser.readValueAsTree();
                return tree.path("acknowledged").asBoolean() ? DeleteResult.acknowledged(tree.path("deletedCount").asLong()) : DeleteResult.unacknowledged();
            }
        });
        addSerializer(ObjectId.class, serializer((value, gen) -> gen.writeString(value.toHexString())));
        addSerializer(Query.class, serializer((query, gen) -> {
            gen.writeStartObject();
            gen.writeObjectField("filter", query.getQueryObject());
            gen.writeObjectField("projection", query.getFieldsObject());
            gen.writeObjectField("sort", query.getSortObject());
            gen.writeNumberField("skip", query.getSkip());
            gen.writeNumberField("limit", query.getLimit());
            gen.writeStringField("hint", query.getHint());
            gen.writeObjectField("collation", query.getCollation().map(c -> c.toDocument()).orElse(null));
            gen.writeObjectField("restrictedTypes", query.getRestrictedTypes().stream().map(Class::getName).sorted().toList());
            gen.writeObjectField("keyset", query.hasKeyset() ? query.getKeyset().getKeys() : null);
            gen.writeObjectField("readConcern", query.hasReadConcern() ? query.getReadConcern().asDocument() : null);
            gen.writeObjectField("readPreference", query.hasReadPreference() ? query.getReadPreference().toDocument() : null);
            var meta = new TreeMap<String, Object>();
            query.getMeta().values().forEach(entry -> meta.put(entry.getKey(), entry.getValue()));
            meta.put("flags", query.getMeta().getFlags());
            meta.put("cursorBatchSize", query.getMeta().getCursorBatchSize());
            meta.put("allowDiskUse", query.getMeta().getAllowDiskUse());
            gen.writeObjectField("meta", meta);
            gen.writeEndObject();
        }));
        addSerializer(UpdateDefinition.class, serializer((update, gen) -> {
            gen.writeStartObject();
            gen.writeObjectField("update", update.getUpdateObject());
            gen.writeObjectField("arrayFilters", update.getArrayFilters().stream().map(f -> f.asDocument()).toList());
            gen.writeBooleanField("isolated", update.isIsolated());
            gen.writeEndObject();
        }));
        addSerializer(UpdateResult.class, serializer((result, gen) -> {
            gen.writeStartObject();
            gen.writeBooleanField("acknowledged", result.wasAcknowledged());
            if (result.wasAcknowledged()) {
                gen.writeNumberField("matchedCount", result.getMatchedCount());
                gen.writeNumberField("modifiedCount", result.getModifiedCount());
                gen.writeObjectField("upsertedId", result.getUpsertedId());
            }
            gen.writeEndObject();
        }));
        addSerializer(DeleteResult.class, serializer((result, gen) -> {
            gen.writeStartObject();
            gen.writeBooleanField("acknowledged", result.wasAcknowledged());
            if (result.wasAcknowledged()) gen.writeNumberField("deletedCount", result.getDeletedCount());
            gen.writeEndObject();
        }));
        // Canonical Extended JSON preserves BSON type distinctions (including upserted IDs).
        addSerializer(BsonValue.class, serializer((value, gen) -> gen.writeRawValue(
                new org.bson.BsonDocument("value", value).toJson(org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED).build()))));
    }

    /**
     * MongoDB 타입별 JSON 출력 로직을 공통 JsonSerializer 생성기에 전달하는 내부 함수다.
     * 각 타입의 필드 기록 규칙을 람다로 작성하면서 IOException을 그대로 전달할 수 있게 한다.
     */
    @FunctionalInterface private interface Writer<T> { void write(T value, JsonGenerator gen) throws IOException; }
    private static <T> JsonSerializer<T> serializer(Writer<T> writer) {
        return new JsonSerializer<>() {
            @Override public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException { writer.write(value, gen); }
        };
    }
}
