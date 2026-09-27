# regression-anchor

최초 입력만 제공하면 실제 실행에서 하위 호출의 입력·출력·예외·순서를 JSON 기준 데이터로 기록하는 Java 회귀 테스트 라이브러리입니다. 리팩터링 후에는 저장한 최초 입력을 다시 읽어 같은 진입점을 실행하고 호출 흐름과 최종 결과를 비교합니다.

Java 21+, Spring Boot 4.1.1에서 검증했습니다. Maven Central에 배포된 패키지가 아니므로 먼저 이 저장소에서 `./mvnw install`을 실행합니다.

## 빠른 시작

```sh
./mvnw clean install
```

대상 Spring Boot 프로젝트에 의존성을 추가합니다.

```xml
<dependency>
  <groupId>io.github.unstoppableworm</groupId>
  <artifactId>regression-anchor-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
// 최초 기준 생성: expected output을 사람이 적지 않습니다.
anchor.record("order-001", request, orderService::place);

// 이후 테스트: 원본 입력을 파일에서 복원합니다. 기준이 없으면 실패합니다.
anchor.verify("order-001", OrderRequest.class, orderService::place)
      .assertMatches();

// List/Map 등 제네릭 입력
anchor.verify("batch", new TypeReference<List<OrderRequest>>() {}, service::process)
      .assertMatches();
```

`RegressionAnchor`는 주입 가능한 Spring bean입니다. `record`/`verify` 범위 밖에서는 계측이 비활성 상태입니다. 프레임워크 없는 사용은 `regression-anchor-core`와 `FileSnapshotStore`를 직접 구성하면 됩니다.

```yaml
regression-anchor:
  enabled: true
  baseline-directory: src/test/resources/anchor-baselines
  report-directory: target/regression-anchor-reports
  # 꼭 필요한 비결정적 필드만 명시적으로 제외합니다. '*'는 한 경로 조각입니다.
  ignored-paths:
    - /calls/*/outcome/value/updatedAt
  request-headers: [content-type, accept]
  response-headers: [content-type]
  max-http-body-bytes: 1048576
```

기준은 `<caseId>.json`에 저장됩니다. 검증은 기준을 덮어쓰지 않고 `<caseId>.actual.json`, `<caseId>.diff.json`을 보고서 디렉터리에 씁니다. 중복 `record`는 실행 전에 거부합니다. 기준 갱신이 필요한 경우 변경을 검토한 후 기존 파일을 명시적으로 제거하고 다시 기록합니다. 같은 caseId의 동시 검증 보고서는 마지막 완료 실행의 내용입니다.

## 기본 계측 대상

| 대상 | 자동 기록 범위 |
|---|---|
| MyBatis Spring Mapper bean | 실제 `MapperProxy`의 메서드 입력, 반환값, 예외, 실행 후 인자. 상속받은 mapper 메서드와 생성 키 포함 |
| MongoTemplate bean | `find`, `findOne`, `findAll`, `findById`, `findDistinct`, `findAndModify`, `findAndReplace`, `findAllAndRemove`, `findAndRemove`, `count`, `exactCount`, `estimatedCount`, `exists`, `insert`, `insertAll`, `save`, `upsert`, `updateFirst`, `updateMulti`, `remove` |
| RestClient | Spring Boot가 제공하는 `RestClient.Builder`로 생성한 클라이언트의 HTTP 요청/응답 |
| RestTemplate | Spring bean 또는 Boot의 `RestTemplateBuilder`로 만든 인스턴스의 HTTP 요청/응답 |
| Feign | Spring bean인 Feign 프록시의 메서드 입력/반환값/예외. Spring Cloud `@FeignClient` 인터페이스도 감지 |
| `@AnchorBoundary` | 사용자가 추가로 지정한 Spring bean 또는 메서드 |

Mongo Query는 필터·projection·sort·skip·limit·hint·collation·meta를, Update는 update 문서·array filter를 기록합니다. 쓰기 결과의 matched/modified count 및 upserted ID도 비교합니다. 일반 DTO는 Jackson으로 직렬화하고 알 수 없는 객체를 빈 문자열로 숨기지 않습니다. 커스텀 타입은 `AnchorAutoConfiguration.CodecCustomizer` bean으로 전용 Jackson 2 serializer를 등록할 수 있습니다. 애플리케이션의 Jackson 3 설정과 기준 파일 설정은 독립적입니다.

HTTP는 메서드·전체 URI·선택한 헤더·본문·응답 상태를 기록합니다. JSON 본문은 구조로 비교하고, 텍스트/바이너리 본문은 text/base64로 보존합니다. HTTP 오류 응답도 저장하며 응답 스트림을 다시 제공하므로 정상적인 client decoding/error handling이 유지됩니다. 기본 헤더 목록에는 인증/Cookie/Date가 없습니다. URI와 본문, DB 결과의 값은 그대로 저장되므로 테스트용 데이터로 기준을 만드세요. 제외 경로는 비교에만 적용되며 저장 데이터에서 값을 지우지 않습니다.

## 반복 호출과 부모 관계

```text
최초 주문 요청
└─ 1 orderService.place(...)       CUSTOM, @AnchorBoundary
   ├─ 2 orderMapper.price(BOOK)    MYBATIS
   ├─ 3 orderMapper.reserve(...)  MYBATIS
   ├─ 4 orderMapper.price(PEN)     MYBATIS
   ├─ ...
   ├─ 6 orderMapper.price(BOOK)    MYBATIS (2번 호출과 별도 보관)
   ├─ 8 mongoTemplate.findOne(...) MONGODB
   ├─ 9 POST /shipping/quote       HTTP
   └─ ...
```

호출마다 `sequence`, `parentSequence`, `kind`, `operation`, `argumentsBefore`, `argumentsAfter`, `outcome`이 있습니다. `operation`에는 bean 이름, 메서드명, 파라미터 타입이 들어가 overload와 다중 bean을 구분합니다. 부모는 가장 가까운 **계측된** 호출입니다. 일반 내부 메서드는 기본적으로 계측하지 않아 메서드 추출 등 구현 리팩터링이 외부 상호작용에 영향을 주지 않으면 통과합니다. 추가 부모 노드가 필요하면 `@AnchorBoundary`를 사용합니다.

배열 순서, 호출 순서, 횟수, 누락/추가 호출 모두 비교합니다. 동일한 최종 응답이라도 하위 호출의 값/순서가 달라지면 실패합니다. 객체 key 순서는 무시하지만 숫자와 문자열은 구분합니다. 예외의 타입·메시지·최대 8단계 cause도 저장하고 비교합니다. `record` 중 비즈니스 예외가 발생하면 기준을 저장한 뒤 원래 예외를 다시 던집니다. `verify`는 비즈니스 예외를 비교 결과에 포함합니다. 기준에 있는 예외와 동일하면 검증이 통과합니다. 캡처 실패나 저장 실패는 검증 실패 결과로 숨기지 않고 호출자에게 던집니다.

## 실행 의미와 현재 지원 범위

- **실제 실행 비교**입니다. 검증 시 SQL/Mongo 쓰기와 HTTP 요청도 실제로 실행합니다. 기록된 의존성 응답을 대신 반환하는 오프라인 replay/mock 모드는 없습니다. 각 실행 전에 동일한 테스트 DB 및 API fixture를 준비해야 코드 변경과 외부 상태 변경을 구분할 수 있습니다.
- 최초 입력은 저장하지만 실행할 Java 함수를 파일로 저장하지 않습니다. 검증 시 진입점 함수(`service::place`)와 입력 타입을 지정합니다.
- 동기 호출 한 스레드의 흐름을 추적합니다. 서로 다른 스레드의 독립 실행 범위는 분리합니다. `@Async`, Reactor/WebClient, executor로 넘기는 자식 작업의 컨텍스트 전파와 병렬 인과관계는 지원하지 않습니다. Future/Publisher/Stream 반환은 거부합니다.
- Spring proxy를 통과하는 호출이 대상입니다. 직접 `new`로 만든 mapper/gateway, 같은 bean의 self-invocation, private/final 메서드는 자동 추적 대상이 아닙니다. 추가 클래스 프록시 대상은 non-final 클래스로 만듭니다.
- `RestClient.create()`로 직접 생성한 클라이언트는 자동 계측되지 않습니다. Boot builder를 주입하거나 `AnchorHttpInterceptor`를 직접 등록합니다. Feign은 HTTP transport 내부 retry 횟수/헤더 대신 논리적 메서드 결과를 기록합니다.
- Mongo fluent API, bulk, stream, aggregation, raw driver 접근은 활성 범위에서 명시적으로 거부합니다. Mongo admin/index 작업은 회귀 기록 대상이 아닙니다. MyBatis cursor/ResultHandler 같은 스트리밍 API 대신 반환 컬렉션을 사용하세요.
- 타입명과 bean 이름을 바꾸면 operation도 바뀔 수 있습니다. 외부 계약 변경을 의도한 경우 기준을 검토·갱신합니다. 임의 객체 그래프·순환 참조·거대/스트리밍 본문은 지원하지 않습니다.
- 파일 저장소는 atomic hard link 및 atomic move를 지원하는 로컬 파일시스템을 요구합니다. baseline 디렉터리는 명시적인 record 시에만 쓰며 보고서는 별도 디렉터리에 둡니다.

## 프로젝트

- `regression-anchor-core`: Spring 없는 기록 범위, JSON snapshot, 비교기, 파일 저장소
- `regression-anchor-spring-boot-starter`: 자동 구성 및 MyBatis/Mongo/HTTP/Feign/사용자 bean 계측
- 소비자 예제: 별도 `tech-playground` 저장소의 `regression-anchor-target`

```sh
./mvnw verify
```

테스트는 반복 호출, 부모 관계, 입력 변경, 출력 변경, 누락/추가 호출, 인자 mutation, 예외, null, 숫자/BSON 저장 안정성, thread 격리, 기준 보존, 선택적 의존성, HTTP 오류/크기 제한, Feign 계측을 다룹니다. 실제 MyBatis/H2와 Mongo wire protocol, Spring Boot RestClient의 종합 검증은 소비자 예제에 있습니다.

설계에 참고한 공식 문서: [MyBatis mapper injection](https://mybatis.org/spring/mappers.html), [Spring REST clients](https://docs.spring.io/spring-boot/reference/io/rest-client.html), [MongoTemplate CRUD](https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-crud-operations.html).
