package io.github.unstoppableworm.anchor.spring.mongo;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorAutoConfiguration;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryPostProcessor;
import io.github.unstoppableworm.anchor.spring.interception.BoundaryReplayAdapter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.util.Set;

/**
 * regression에서 MongoTemplate 빈의 지원되는 조회·저장·수정·삭제 메서드를 자동 계측한다.
 * 지원 목록의 구체화된 결과를 기록·재생하며, fluent·stream·raw 접근 등 지원하지 않는 경로는 제한한다.
 * MongoSnapshotModule을 전용 코덱에 등록해 쿼리와 쓰기 결과를 비교 가능한 JSON으로 만든다.
 * MongoReturnTypeResolver를 주입해 Class·문서 인자에서 반환 타입을 해석하고 공통 복원기의 기술 의존을 막는다.
 * getConverter는 DB I/O가 없는 로컬 메타데이터 접근으로 통과시켜 Boot 초기화를 허용한다.
 * MongoDB 관련 클래스가 있고 라이브러리가 활성화된 경우에만 적용된다.
 */
@AutoConfiguration(after = AnchorAutoConfiguration.class)
@ConditionalOnClass(MongoTemplate.class)
@Profile("regression")
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MongoAnchorAutoConfiguration {
    private static final Set<String> CAPTURED = Set.of("find", "findOne", "findAll", "findById", "findDistinct",
            "findAndModify", "findAndReplace", "findAllAndRemove", "findAndRemove", "count", "exactCount", "estimatedCount",
            "exists", "insert", "insertAll", "save", "upsert", "updateFirst", "updateMulti", "remove");
    private static final Set<String> UNSUPPORTED = Set.of("query", "update", "bulkOps", "stream", "aggregateStream",
            "aggregate", "mapReduce", "execute", "executeCommand", "getCollection", "getDb", "withSession",
            "withReadPreference", "withReadConcern", "scroll", "geoNear");

    @Bean static BeanPostProcessor anchorMongoTemplate(ObjectProvider<RegressionAnchor> anchors, ObjectProvider<BoundaryReplayAdapter> adapters) {
        return new BoundaryPostProcessor(anchors, adapters, "MONGODB", MongoTemplate.class::isInstance, (bean, method) -> {
            if (UNSUPPORTED.contains(method.getName())) throw anchors.getObject().unsupported(
                    "MongoTemplate." + method.getName() + " is not supported in an anchor scope; use materialized CRUD operations");
            return CAPTURED.contains(method.getName());
        }, true, method -> method.getName().equals("getConverter") && method.getParameterCount() == 0,
                new MongoReturnTypeResolver());
    }

    @Bean AnchorAutoConfiguration.CodecCustomizer anchorMongoCodec() {
        return mapper -> mapper.registerModule(new MongoSnapshotModule());
    }
}
