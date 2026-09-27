package io.github.unstoppableworm.anchor.spring;

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

final class MongoSnapshotModule extends SimpleModule {
    MongoSnapshotModule() {
        super("anchor-mongodb");
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

    @FunctionalInterface private interface Writer<T> { void write(T value, JsonGenerator gen) throws IOException; }
    private static <T> JsonSerializer<T> serializer(Writer<T> writer) {
        return new JsonSerializer<>() {
            @Override public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException { writer.write(value, gen); }
        };
    }
}
