package io.github.unstoppableworm.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.concurrent.Future;
import java.util.stream.BaseStream;

/** Own mapper: application JSON configuration cannot silently alter persisted fixtures. */
public final class SnapshotCodec {
    private final ObjectMapper mapper;

    public SnapshotCodec() { this(defaultMapper()); }
    public SnapshotCodec(ObjectMapper mapper) { this.mapper = mapper.copy(); }

    public static ObjectMapper defaultMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }

    public ObjectMapper mapper() { return mapper; }

    public JsonNode freeze(Object value) {
        if (value instanceof Future<?> || value instanceof BaseStream<?, ?> || isPublisher(value)) {
            throw new IllegalArgumentException("Deferred/streaming results are unsupported; record a synchronous materialized result");
        }
        // Use the on-disk representation here too: valueToTree retains LongNode/RawValue
        // implementation details that disappear on a later read and cause false regressions.
        try { return mapper.readTree(mapper.writeValueAsBytes(value)); }
        catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot snapshot " + (value == null ? "null" : value.getClass().getName())
                    + "; register a deterministic Jackson serializer with the anchor SnapshotCodec", e);
        }
    }

    private boolean isPublisher(Object value) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> contract : type.getInterfaces()) if (isPublisherType(contract)) return true;
        }
        return false;
    }

    private boolean isPublisherType(Class<?> type) {
        if (type.getName().equals("org.reactivestreams.Publisher")
                || type.getName().equals("java.util.concurrent.Flow$Publisher")) return true;
        for (Class<?> parent : type.getInterfaces()) if (isPublisherType(parent)) return true;
        return false;
    }
}
