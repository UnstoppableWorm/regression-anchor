package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.util.Set;

@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass(MongoTemplate.class)
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MongoAnchorAutoConfiguration {
    private static final Set<String> CAPTURED = Set.of("find", "findOne", "findAll", "findById", "findDistinct",
            "findAndModify", "findAndReplace", "findAllAndRemove", "findAndRemove", "count", "exactCount", "estimatedCount",
            "exists", "insert", "insertAll", "save", "upsert", "updateFirst", "updateMulti", "remove");
    private static final Set<String> UNSUPPORTED = Set.of("query", "update", "bulkOps", "stream", "aggregateStream",
            "aggregate", "mapReduce", "execute", "executeCommand", "getCollection", "getDb", "withSession",
            "withReadPreference", "withReadConcern", "scroll", "geoNear");

    @Bean static BeanPostProcessor anchorMongoTemplate(ObjectProvider<RegressionAnchor> anchors) {
        return new BoundaryPostProcessor(anchors, "MONGODB", MongoTemplate.class::isInstance, (bean, method) -> {
            if (UNSUPPORTED.contains(method.getName())) throw anchors.getObject().unsupported(
                    "MongoTemplate." + method.getName() + " is not supported in an anchor scope; use materialized CRUD operations");
            return CAPTURED.contains(method.getName());
        }, true);
    }

    @Bean AnchorAutoConfiguration.CodecCustomizer anchorMongoCodec() {
        return mapper -> mapper.registerModule(new MongoSnapshotModule());
    }
}
