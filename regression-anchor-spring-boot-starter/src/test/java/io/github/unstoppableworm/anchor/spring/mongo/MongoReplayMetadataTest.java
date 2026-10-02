package io.github.unstoppableworm.anchor.spring.mongo;

import io.github.unstoppableworm.anchor.execution.ExecutionMode;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MongoReplayMetadataTest {
    private final TestSnapshotStore store = new TestSnapshotStore();
    private final AtomicInteger queries = new AtomicInteger();
    private final MongoConverter converter = mock(MongoConverter.class);

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, MongoAnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, () -> store)
                .withBean("mongoTemplate", MongoTemplate.class, () -> mock(MongoTemplate.class, invocation -> {
                    return switch (invocation.getMethod().getName()) {
                        case "getConverter" -> converter;
                        case "setApplicationContext" -> null;
                        case "findAll" -> { queries.incrementAndGet(); yield List.of(new Document("price", 12)); }
                        default -> throw new AssertionError("Unexpected backend access: " + invocation.getMethod());
                    };
                }))
                .withUserConfiguration(StartupConfiguration.class);
    }

    @Test void replayOnlyStartupCanReadConverterWhileUnscopedDatabaseAccessStillFails() {
        runner("regression", "regression-replay").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConversionSetup.class).converter()).isSameAs(converter);
            var mongo = context.getBean(MongoTemplate.class);
            assertThatThrownBy(() -> mongo.findAll(Document.class, "products")).hasMessageContaining("active replay scope");
            assertThatThrownBy(mongo::getDb).hasMessageContaining("active replay scope");
            assertThatThrownBy(() -> mongo.collectionExists("products")).hasMessageContaining("active replay scope");
            assertThat(queries).hasValue(0);
        });
    }

    @Test void converterIsLocalMetadataInRecordAndReplayScopesButQueriesAreStillCaptured() {
        runner("regression").run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var mongo = context.getBean(MongoTemplate.class);
            RegressionAnchor.Trigger<String, List<Document>> query = collection -> {
                assertThat(mongo.getConverter()).isSameAs(converter);
                return mongo.findAll(Document.class, collection);
            };
            var recorded = anchor.run(ExecutionMode.RECORD, "case", "products", query);
            var replayed = anchor.run(ExecutionMode.REPLAY, "case", "products", query);
            assertThat(replayed).isEqualTo(recorded);
            assertThat(queries).hasValue(1);
            assertThat(anchor.loadSnapshot("case").calls()).hasSize(1);
            assertThat(anchor.loadSnapshot("case").calls().getFirst().operation()).contains("#findAll(");
            assertThatThrownBy(() -> anchor.run(ExecutionMode.REPLAY, "case", "products", input -> mongo.getDb()))
                    .hasMessageContaining("not supported");
            assertThat(queries).hasValue(1);
        });
    }

    @Test void reconstructsMongoWriteResultsAndBsonIds() {
        var mapper = SnapshotCodec.defaultMapper().registerModule(new MongoSnapshotModule());
        var codec = new SnapshotCodec(mapper);
        var id = new org.bson.BsonObjectId(new org.bson.types.ObjectId("507f1f77bcf86cd799439011"));
        var update = com.mongodb.client.result.UpdateResult.acknowledged(1, 2L, id);
        var restored = codec.mapper().convertValue(codec.freeze(update), com.mongodb.client.result.UpdateResult.class);
        assertThat(restored.getUpsertedId()).isEqualTo(id);
        assertThat(restored.getMatchedCount()).isEqualTo(1);
        assertThat(restored.getModifiedCount()).isEqualTo(2);
        var delete = codec.mapper().convertValue(codec.freeze(com.mongodb.client.result.DeleteResult.acknowledged(3)), com.mongodb.client.result.DeleteResult.class);
        assertThat(delete.getDeletedCount()).isEqualTo(3);
        assertThat(codec.mapper().convertValue(codec.freeze(com.mongodb.client.result.UpdateResult.unacknowledged()), com.mongodb.client.result.UpdateResult.class).wasAcknowledged()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    static class StartupConfiguration {
        // Spring Boot's GridFS auto-configuration similarly reads this getter during initialization.
        @Bean ConversionSetup conversionSetup(MongoTemplate mongo) { return new ConversionSetup(mongo.getConverter()); }
    }
    record ConversionSetup(MongoConverter converter) {}
}
