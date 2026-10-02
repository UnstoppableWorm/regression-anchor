package io.github.unstoppableworm.anchor.spring.mongo;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryReplayAdapter;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MongoReturnTypeResolverTest {
    private final AtomicBoolean offline = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final MongoConverter converter = mock(MongoConverter.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("regression"))
                .withConfiguration(AutoConfigurations.of(AnchorAutoConfiguration.class, MongoAnchorAutoConfiguration.class))
                .withBean(SnapshotStore.class, TestSnapshotStore::new)
                .withBean("mongoTemplate", MongoTemplate.class, () -> mock(MongoTemplate.class, invocation -> {
                    String name = invocation.getMethod().getName();
                    if (name.equals("getConverter")) return converter;
                    if (name.equals("setApplicationContext")) return null;
                    if (offline.get()) throw new AssertionError("Mongo backend entered during replay");
                    calls.incrementAndGet();
                    return switch (name) {
                        case "find" -> List.of(new Person("Ada"));
                        case "findOne" -> new Person("Ada");
                        case "findDistinct" -> List.of("Ada");
                        case "save" -> invocation.getArgument(0);
                        case "count" -> 1L;
                        case "insertAll" -> invocation.getArgument(0);
                        default -> throw new AssertionError("Unexpected Mongo call: " + name);
                    };
                }));
    }

    @Test void injectedMongoResolverRestoresEntityListDistinctDocumentAndFixedReturnTypesWithoutIo() {
        runner().run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var mongo = context.getBean(MongoTemplate.class);
            RegressionAnchor.Trigger<Integer, Result> work = input -> new Result(
                    mongo.find(new Query(), Person.class),
                    mongo.findOne(new Query(), Person.class),
                    mongo.findDistinct(new Query(), "name", Person.class, String.class),
                    mongo.save(new Person("Grace")),
                    mongo.count(new Query(), Person.class));
            var recorded = anchor.record("typed", 1, work);
            offline.set(true);
            var replayed = anchor.replay("typed", Integer.class, work).assertMatches();
            assertThat(replayed.value()).isEqualTo(recorded);
            assertThat(replayed.value().people()).containsExactly(new Person("Ada"));
            assertThat(replayed.value().names()).containsExactly("Ada");
            assertThat(calls).hasValue(5);
        });
    }

    @Test void unresolvedMongoTypesFailWithoutFallingBackToTheBackend() {
        assertThatThrownBy(() -> new MongoReturnTypeResolver().resolve(null,
                MongoTemplate.class.getMethod("insertAll", Collection.class), new Object[]{List.of(new Person("Ada"))}))
                .hasMessageContaining("Unresolved Mongo replay type");
        runner().run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var mongo = context.getBean(MongoTemplate.class);
            RegressionAnchor.Trigger<Integer, Collection<Person>> work = input -> mongo.insertAll(List.of(new Person("Ada")));
            anchor.record("unresolved", 1, work);
            offline.set(true);
            assertThatThrownBy(() -> anchor.replay("unresolved", Integer.class, work))
                    .hasStackTraceContaining("Unresolved replay type");
            assertThat(calls).hasValue(1);
        });
    }

    @Test void consumerAdapterOverridesTheMongoDefaultWithoutCompetingWithIt() {
        var restored = new AtomicInteger();
        runner().withBean(BoundaryReplayAdapter.class, () -> new BoundaryReplayAdapter() {
            @Override public boolean supports(String kind, Method method) {
                return kind.equals("MONGODB") && method.getName().equals("insertAll");
            }
            @Override public Object restore(String kind, Method method, Snapshot.Call call, Object[] arguments, SnapshotCodec codec) {
                restored.incrementAndGet();
                return List.of(codec.mapper().convertValue(call.outcome().value().get(0), Person.class));
            }
        }).run(context -> {
            var anchor = context.getBean(RegressionAnchor.class);
            var mongo = context.getBean(MongoTemplate.class);
            RegressionAnchor.Trigger<Integer, Collection<Person>> work = input -> mongo.insertAll(List.of(new Person("Ada")));
            anchor.record("custom", 1, work);
            offline.set(true);
            assertThat(anchor.replay("custom", Integer.class, work).assertMatches().value())
                    .containsExactly(new Person("Ada"));
            assertThat(restored).hasValue(1);
            assertThat(calls).hasValue(1);
        });
    }

    record Person(String name) {}
    record Result(List<Person> people, Person person, List<String> names, Person saved, long count) {}
}
