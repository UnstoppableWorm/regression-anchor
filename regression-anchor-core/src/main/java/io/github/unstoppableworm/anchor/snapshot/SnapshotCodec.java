package io.github.unstoppableworm.anchor.snapshot;

import io.github.unstoppableworm.anchor.annotation.Anchor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.concurrent.Future;
import java.util.stream.BaseStream;
import java.io.IOException;

/**
 * 스냅샷과 보고서의 JSON 직렬화·역직렬화를 라이브러리 안에서 책임지는 전용 코덱이다.
 * 전용 Jackson mapper에 {@link Anchor} 규칙을 적용하고, 저장소에는 완성된 UTF-8 바이트를 전달한다.
 * freeze는 값을 JSON으로 왕복 변환해 호출 시점의 값을 고정하므로 이후 원본 객체 변경의 영향을 받지 않는다.
 * 기본 mapper는 날짜·숫자 표현과 키 순서를 정리하며, Future·Stream·Publisher 객체 자체의 기록은 거부한다.
 * 소비자가 결정하는 저장 위치나 파일·DB I/O에는 관여하지 않는다.
 */
public final class SnapshotCodec {
    private final ObjectMapper mapper;

    public SnapshotCodec() { this(defaultMapper()); }
    public SnapshotCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.registerModule(new AnchorPropertyModule());
        // An explicit stable key must remain literal even with a custom naming strategy.
        this.mapper.setConfig(this.mapper.getSerializationConfig()
                .with(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING, false));
        this.mapper.setConfig(this.mapper.getDeserializationConfig()
                .with(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING, false));
        if (!this.mapper.isEnabled(MapperFeature.USE_ANNOTATIONS)) {
            throw new IllegalArgumentException("SnapshotCodec requires Jackson USE_ANNOTATIONS for @Anchor support");
        }
    }

    public static ObjectMapper defaultMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).addModule(new AnchorPropertyModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }

    public ObjectMapper mapper() { return mapper; }

    /** Canonical UTF-8 JSON encoding for baselines, reports and exported regression data. */
    public byte[] writeJson(Object value) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    /** Decode stored JSON in the library; reject trailing documents instead of ignoring corruption. */
    public <T> T readJson(byte[] json, Class<T> type) throws IOException {
        return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
    }

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
