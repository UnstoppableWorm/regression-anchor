# regression-anchor

최초 입력만 제공하면 실제 실행에서 하위 호출의 입력·출력·예외·순서를 JSON 기준 데이터로 기록하는 Java 회귀 테스트 라이브러리입니다. 리팩터링 후에는 저장한 최초 입력을 다시 읽어 같은 진입점을 실행하고 호출 흐름과 최종 결과를 비교합니다.

Java 21+, Spring Boot 4.1.1에서 검증했습니다. Maven Central에 배포된 패키지가 아니므로 먼저 이 저장소에서 `./mvnw install`을 실행합니다.

라이브러리 내부 구조와 클래스별 역할은 [구조 및 클래스 안내](docs/architecture.md)를 참고하세요. 배포 소스의 모든 클래스·인터페이스·어노테이션·enum·record와 이름 있는 내부 타입에 한국어 Javadoc을 작성했습니다.

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

Spring Boot에서는 공통 `regression` 프로필을 켜고, **메서드 인자로 실행 모드를 선택**할 수 있습니다.

```java
import static io.github.unstoppableworm.anchor.execution.ExecutionMode.RECORD;
import static io.github.unstoppableworm.anchor.execution.ExecutionMode.REPLAY;

// @ActiveProfiles("regression")인 같은 애플리케이션에서 호출할 수 있습니다.
anchor.run(RECORD, "quote-001", request, v1::quote);
anchor.run(REPLAY, "quote-001", request, v3::quote); // 현재 로직 실행 + 기록된 외부 응답 사용 + 결과 비교
anchor.run(REPLAY, "quote-001", request, v2::quote); // 결과가 바뀌면 AssertionError
```

기존 모드 프로필은 실행 환경을 한 모드로 고정하는 선택지입니다.

| 프로필 | `anchor.run(caseId, input, trigger)` 동작 |
|---|---|
| `regression` | `run(RECORD, ...)` 또는 `run(REPLAY, ...)`로 선택. 모드 인자를 생략하면 실행 전에 오류 |
| `regression,regression-record,staging` | 실제 검증계 DB/API/SDK를 호출하고 소비자의 `SnapshotStore`에 기준 생성 |
| `regression,regression-replay` | 저장된 기준을 읽고 현재 업무 코드 실행, 외부 경계 응답은 기록값으로 대체 |
| `regression` 없음 | starter 자동 설정 비활성화, 원래 애플리케이션 동작 유지 |
| `regression` + 두 모드 모두 | 애플리케이션 singleton 초기화 전에 시작 실패 |

`staging`은 예시입니다. 실제 접속 정보가 들어 있는 **소비 프로젝트의 검증계 프로필**을 record 실행에 함께 넣습니다. 라이브러리가 DB/Redis 주소나 자격 증명을 선택하지 않습니다. 테스트에는 `@ActiveProfiles({"regression", "regression-record"})` 또는 replay 조합을 사용합니다.

```java
// 두 모드에서 같은 코드: caseId와 최초 입력, 실행할 업무 코드만 제공합니다.
OrderReceipt receipt = anchor.run("order-001", request, orderService::place);
```

`run`은 record 모드에서는 실제 결과를 반환하고, replay 모드에서는 최초 입력과 하위 호출의 맥락·입력을 매칭합니다. 캡처된 전체 호출 순서가 기준과 같아야 하며, 서로 다른 맥락이나 외부 메서드 사이의 순서 변경도 거부합니다. 현재 업무 코드가 만들어 낸 최종 결과와 공유 출력까지 비교해 일치할 때 반환합니다. 최종 응답만 꺼내 돌려주는 방식이 아니므로 업무 로직 변경도 탐지합니다. 기록된 실패와 일치한 경우에도 업무 예외를 다시 던집니다. 동일 인자를 반복 호출하면 각 순번의 기록값을 복원합니다.

세부 API가 필요한 경우 `record`, `replay`(저장된 최초 입력 복원), `replayInput`(제공한 최초 입력 매칭)을 사용합니다. 프로필로 모드를 고정했다면 반대 모드의 API와 `run` 인자는 저장소 접근·업무 실행 전에 거부합니다. `run`은 불일치 시 실패하고, `replay`/`replayInput`은 `Verification`으로 결과와 차이를 확인할 수 있습니다.

호출 인자는 그 실행에만 적용되며 엔진 설정을 변경하지 않습니다. `anchor.mode()`는 설정된 기본값/제약을 반환하므로, `regression`만 켠 엔진에서는 계속 `MANUAL`입니다. `MANUAL`과 `null`은 `run`의 실행 모드 인자로 사용할 수 없습니다. 같은 입력으로 여러 번 기록해도 기존 기준을 덮어쓰지 않습니다.

기준 없음, 최초 입력 불일치, 하위 호출 식별자·입력·전체 순서·횟수 불일치에는 **실제 의존성을 호출하는 fallback이 없습니다.** `regression-replay`로 고정한 엔진은 실행 범위 밖의 계측 대상 메서드와 HTTP 요청도 거부합니다. 활성 replay가 없고 만료된 상속 맥락도 없는 경우, `regression`만 켰거나 record로 고정한 엔진의 범위 밖 호출은 평소처럼 실행하며 기록하지 않습니다. 자식 스레드의 자동 연결과 맥락 누락 차단 조건은 아래 가상 스레드 절을 참고하세요.

`regression-anchor.enabled=true`만으로 프로필 조건을 우회할 수 없습니다. `false`는 `regression` 안에서도 모든 starter 자동 설정을 끄는 추가 스위치입니다. 여러 활성 프로필 중 `regression`이 포함되면 이 규칙이 적용되므로 운영 실행 설정에는 넣지 않아야 합니다. 수동으로 생성한 core 엔진은 별개이며, 기존 생성자는 `MANUAL`로 `run(RECORD/REPLAY, ...)` 또는 세부 API를 직접 선택합니다. 모드 없는 `run`을 사용하려면 마지막 생성자 인자로 `ExecutionMode.RECORD` 또는 `REPLAY`를 지정합니다.

메서드 인자는 Spring bean 구성을 바꾸지 않습니다. **기존 Controller·Service·Mapper·MongoTemplate·HTTP client를 그대로 사용**하며 재생 시 라이브러리가 계측한 외부 호출을 가로채 기록값으로 대체합니다. 각 업무·인프라 클래스에 `@Profile("!regression-replay")`를 붙이거나 재생용 대역 빈을 작성할 필요가 없습니다. 프로필 조건은 starter 활성화와 회귀 전용 설정에 둡니다.

활성 starter에는 **소비 프로젝트의 `SnapshotStore` bean이 필수**입니다. 기본 저장소는 제공하지 않습니다. 비활성 프로필에서는 저장소 없이 시작합니다. replay에서 업무 DB를 제거해도 기준을 읽을 저장소는 필요하며, 파일을 사용할지 별도 저장 서버를 사용할지는 소비자가 정합니다.

## JSON은 라이브러리, 저장 위치와 I/O는 소비 프로젝트

기준과 보고서의 JSON 형식, UTF-8 직렬화·역직렬화는 라이브러리가 담당합니다. 소비 프로젝트는 완성된 바이트를 어디에 보관하고 읽을지만 구현합니다. 파일, RDB, MongoDB, 객체 저장소 중 무엇을 쓸지와 디렉터리·연결 정보·보존 정책은 소비 프로젝트가 정합니다. 저장소 구현에는 `ObjectMapper`나 `SnapshotCodec`이 필요 없습니다.

```java
public interface SnapshotStore {
    byte[] load(String caseId) throws Exception;
    boolean exists(String caseId) throws Exception;
    void create(String caseId, byte[] snapshotJson) throws Exception;
    default void saveReport(String caseId, byte[] reportJson) throws Exception {}
}
```

- `load`: 저장했던 JSON 바이트를 그대로 반환하며 없거나 읽을 수 없으면 예외를 던집니다. 파싱은 라이브러리가 수행합니다. 조회 과정에서 기준을 만들지 않습니다.
- `exists`: 실행 전에 중복 기록을 확인합니다. 동시 요청의 경합까지 해결하는 장치는 아닙니다.
- `create`: 전달받은 JSON 바이트를 변경 없이 저장한 뒤 반환합니다. 같은 ID가 있으면 **원자적으로 생성에 실패**해야 하고, 부분 데이터나 덮어쓰기가 없어야 합니다. DB의 고유 제약 조건이나 객체 저장소의 조건부 생성 등 구현체에서 이 계약을 보장합니다.
- `saveReport`: 라이브러리가 완성한 보고서 JSON 바이트를 저장하는 선택적 훅입니다. `matches`, `callCount`, `actual`, `differences`가 한 문서에 들어갑니다. 기본 구현은 저장하지 않습니다. 어느 경우에도 반환된 `Verification`에서 실제 결과와 차이를 읽을 수 있습니다. 기준은 변경하지 않습니다.

```java
// 소비 프로젝트의 설정. MySnapshotStore는 해당 프로젝트가 구현합니다.
@Bean
@Profile("regression")
@ConditionalOnProperty(prefix = "regression-anchor", name = "enabled", havingValue = "true", matchIfMissing = true)
SnapshotStore snapshotStore(MySnapshotRepository repository) {
    return new MySnapshotStore(repository);
}
```

프레임워크 없이 사용할 때에도 `new RegressionAnchor(new SnapshotCodec(), mySnapshotStore)`처럼 인터페이스 구현을 주입합니다. 저장소 예외는 파일 전용 `IOException`에 한정하지 않고 호출자에게 전파합니다. 조회한 기준의 버전과 caseId는 검증 엔진이 확인한 뒤 업무 코드를 실행합니다. caseId의 경로나 파일명 제약은 라이브러리가 강제하지 않습니다.

저장소 경계에서는 `Snapshot`이나 `Difference`를 전달하지 않습니다. `RegressionAnchor`가 `SnapshotCodec.writeJson`으로 직렬화한 뒤 저장소를 호출하고, 읽을 때에는 역직렬화와 버전·caseId 검증까지 마친 뒤 업무 코드를 실행합니다. 잘못된 JSON이나 뒤에 덧붙은 JSON 문서는 실행 전에 실패합니다. 기준을 직접 확인할 때에는 `anchor.loadSnapshot(caseId)`를 사용합니다.

보고서는 라이브러리의 `VerificationReport` 형식으로 생성하며 `result.report()`로 확인할 수 있습니다. 다른 위치에도 보관하려면 `anchor.saveReport(result, destinationStore)`를 호출합니다. 소비자는 목적지만 제공하며 직렬화하지 않습니다. 저장된 보고서는 `codec.readJson(bytes, VerificationReport.class)`로 읽을 수 있고, 누락 필드와 명시적인 null의 차이도 복원합니다.

예제 `tech-playground/regression-anchor-target`의 `FileSnapshotStore`는 `Files.readAllBytes`와 `Files.write`를 사용합니다. 기준은 `<caseId>.json`, 보고서는 `<caseId>.report.json`으로 저장합니다. 파일 저장소나 기본 경로는 라이브러리에 포함하지 않습니다. 라이브러리 테스트의 메모리 저장소도 바이트를 그대로 저장하는 테스트 전용 코드이며 배포 JAR에는 포함되지 않습니다.

기존 객체 기반 `SnapshotStore` 구현은 위의 바이트 기반 시그니처로 변경해야 합니다. 기존 기준 JSON 형식은 유지하므로 재기록할 필요가 없습니다. 보고서는 기존 actual/diff 두 파일 대신 한 문서로 생성합니다.

```yaml
regression-anchor:
  enabled: true
  # 꼭 필요한 비결정적 필드만 명시적으로 제외합니다. '*'는 한 경로 조각입니다.
  ignored-paths:
    - /calls/*/outcome/value/updatedAt
  request-headers: [content-type, accept]
  response-headers: [content-type]
  max-http-body-bytes: 1048576
```

중복 `record`는 실행 전에 거부하고, `replay`는 기준을 생성하거나 갱신하지 않습니다. 기준을 갱신할 필요가 있으면 소비 프로젝트의 정책에 따라 변경을 검토하고 새 기준을 준비합니다. `exists` 확인과 `create` 사이에는 업무 실행이 있으므로, 같은 ID의 동시 기록은 양쪽 업무가 실행될 수 있지만 기준 저장은 하나만 성공해야 합니다.

## 캐시 등 공유 입력·출력

메서드 인자 외에 실행 결과에 영향을 주는 인메모리 캐시나 설정은 소비 프로젝트의 `SharedState<T>`로 등록합니다. JVM의 모든 전역 변수를 자동으로 수집하지 않습니다. stable key, 복원 타입, 조회·복원 방법을 소비자가 결정합니다.

```java
@Bean
SharedState<Map<String, Integer>> priceCacheState(PriceCache cache) {
    return new SharedState<>() {
        public String key() { return "product-prices"; }
        public TypeReference<Map<String, Integer>> type() { return new TypeReference<>() {}; }
        public Map<String, Integer> capture() { return cache.copyEntries(); }
        public void restore(Map<String, Integer> value) { cache.replaceAllEntries(value); }
    };
}
```

`capture`는 부수 효과 없이 현재 상태를 반환하고, `restore`는 기존 항목의 삭제까지 포함해 전체 상태를 교체해야 합니다. Caffeine, Guava, Spring Cache, 일반 Map 등의 구체 API는 라이브러리가 선택하지 않습니다. 만료 시간·캐시 정책이 분기에 영향을 주면 해당 정보도 담고 테스트 clock을 통제해야 합니다. 샘플은 TTL 없는 Map 캐시입니다.

- `record`: 실행 직전 상태를 `sharedInputs`, 직후 상태를 `sharedOutputs`로 고정하여 `SnapshotStore`에 함께 전달합니다. 실제 실행으로 바뀐 캐시 상태는 유지됩니다.
- `replay`: 현재 상태를 임시 보관하고 기록된 `sharedInputs`를 복원합니다. 실행 후 `sharedOutputs`를 비교한 뒤, 성공·실패와 관계없이 호출 전 상태로 복구합니다. 복원·정리 실패도 검증 성공으로 숨기지 않습니다.
- 메서드 최종 응답이 같아도 공유 출력이 다르면 `/sharedOutputs/product-prices/...`에서 탐지합니다. 공유 DTO에도 `@Anchor`가 적용됩니다.

같은 엔진의 공유 상태 포함 실행은 직렬화합니다. 일반 애플리케이션 스레드나 다른 엔진까지 잠그지는 않으므로 전용 테스트 프로세스에서 실행하세요. 조회/복원 콜백은 로컬 상태만 다루고 별도 DB/API 호출을 하면 안 됩니다. 공유 상태에 대한 중간 읽기·쓰기 각각의 이력은 기록하지 않고 시작·종료 snapshot을 비교합니다.

공유 상태는 snapshot 버전 2부터 포함되며 현재 새 기록은 버전 3입니다. 공유 상태 없는 버전 1 기준도 원본을 수정하지 않고 읽어 검증·재생합니다. 기록의 공유 key 목록과 등록한 provider 목록이 다르면 실행 전에 실패합니다. 새 캐시를 관찰하기 시작할 때에는 해당 provider를 등록한 상태로 기준을 새로 기록해야 합니다.

## DB/API를 호출하지 않는 재생

```java
// 최초 1회: 준비한 환경에서 실제 동작과 공유 상태를 기록합니다.
anchor.record("order-001", request, orderService::place);

// 서비스 코드를 실행하되 계측된 외부 경계는 기록된 결과로 대체할 때
anchor.replay("order-001", OrderRequest.class, orderService::place).assertMatches();
```

`replay`에서 업무 코드는 실제로 실행하며 상위 서비스에 계측 어노테이션을 붙일 필요가 없습니다. MyBatis Mapper, MongoTemplate CRUD, RestClient/RestTemplate, Feign 경계는 kind·operation·전체 호출 순번·입력이 기록과 같은지 확인한 뒤 저장된 반환값/예외를 복원합니다. MyBatis 생성 키 같은 인자 변경도 기존 인스턴스에 반영합니다. 반복 호출에는 각 순번의 결과를 사용하며, 기록이 없다고 실제 의존성을 호출하는 fallback은 없습니다.

입력·외부 작업 식별자·전체 호출 순서·추가/누락 호출이 맞지 않으면 `ReplayMismatchException`으로 중단합니다. 애플리케이션이 이 오류를 잡아도 전체 재생은 실패합니다. 반환값을 복원할 수 없거나 인자의 변경을 정확히 반영할 수 없어도 실패합니다. 입력 매칭은 `ignored-paths`와 별개로 엄격하게 수행합니다. 정상 재생 후의 서비스 결과와 공유 출력 차이는 기존 `Verification`으로 반환합니다.

Jackson으로 복원 가능한 명시적 타입의 DTO, 컬렉션, Mongo 쓰기 결과 등을 지원합니다. 해석할 수 없는 제네릭/다형 타입, 생성자 없는 특수 예외, 복잡한 객체 별칭 관계는 임의로 추측하지 않습니다. 특수 반환 타입·인자 변경은 소비자의 `BoundaryReplayAdapter` Spring bean으로 구현할 수 있고, core에서는 `captureBoundary(..., ReplayAdapter, invocation)`를 사용할 수 있습니다. 예외의 기본 복원 범위는 타입·메시지·cause이며 추가 vendor 필드 등은 보존하지 않습니다. 하위 계측 호출을 포함하는 기존 외부 경계 기록은 재생을 거부합니다. `@Anchor`는 처음 기록할 때부터 내부 계측을 하나의 경계로 묶습니다. 일반 메서드용 `capture`는 재생에서도 실제로 실행합니다.

HTTP 재생은 기록 대상으로 지정한 헤더와 JSON/text/base64 본문으로 응답을 구성합니다. JSON은 구조를 재직렬화하므로 원래 wire bytes/헤더 전체의 복제가 아닙니다. 디코딩과 분기에 필요한 헤더(기본 content-type 등)는 기록 목록에 포함해야 합니다. JSON 응답과 raw base64 표현을 구분할 수 없는 기록은 거부합니다. 바이트 서명 등 전송 원문에 의존하는 API는 전용 경계 adapter가 필요합니다.

공통 복원기 `MethodReplay`는 특정 DB나 SDK를 직접 참조하지 않습니다. 반환 타입은 `MethodReturnTypeResolver`로 결정합니다. 기본 해석은 메서드 선언·구현 클래스의 제네릭을 사용하고, Mongo 자동 설정은 `Class<T>`·문서 인자를 해석하는 `MongoReturnTypeResolver`를 주입합니다. 소비자는 별도 설정 없이 사용하며 기존 `BoundaryReplayAdapter`를 등록한 호출은 사용자 어댑터가 우선합니다.

replay 프로필은 기존 DataSource나 SDK client를 없애거나 교체하지 않습니다. 예제의 `ReplayApplicationTest`와 `RefactoringReplayTest`는 일반 클라이언트와 자동 계측된 빈을 생성한 뒤 SQL·Mongo 클라이언트를 닫고 HTTP 요청을 거부한 상태에서도 재생됨을 확인합니다. MongoTemplate의 `getConverter()`처럼 프레임워크 구성에 쓰는 로컬 메타데이터 조회는 기록·재생 및 범위 밖 차단 대상에서 제외합니다. 실제 조회·저장과 raw DB 접근은 이 예외에 포함되지 않습니다. Redis는 자동 인식 대상이 아니므로, 실제 Redis SDK를 호출하는 Spring gateway의 public 메서드에 `@Anchor`를 붙여 같은 방식으로 기록·재생합니다.

재생이 차단하는 것은 **계측한 경계 내부의 실행**입니다. raw JDBC/driver 접근, 직접 만든 계측 밖 HTTP 클라이언트, 시작 시 migration/health check, 자동 연결 범위 밖의 비동기 작업을 시스템 전체에서 차단하는 네트워크 sandbox는 아닙니다. 시작 시 연결까지 없애려는 추가 요구가 있을 때에만 소비 앱의 시작 설정을 조정합니다. 예제의 `OrderOfflineReplayTest`는 최소 context와 실패 대역으로 backend 호출 0회를 확인하는 별도 테스트이며, 이 대역 구성이 라이브러리 사용의 필수 조건은 아닙니다.

재생은 기록된 의존성 결과에 대한 현재 서비스 로직을 검증합니다. MyBatis SQL 자체나 실제 외부 시스템의 구현 변경은 실행하지 않으므로 그런 변경에는 별도 통합 테스트도 필요합니다.

## 하나의 `@Anchor`, 위치별 역할

```java
import io.github.unstoppableworm.anchor.annotation.Anchor;

record Receipt(@Anchor("total") int payableAmount) {} // 스냅샷 JSON 키 고정

class CacheGateway {
    @Anchor public String find(String key) { /* 캐시 조회 */ return cache.get(key); }
}

@Anchor
class StorageGateway { // 상속 메서드까지 public 인스턴스 메서드 전체를 캡처·재생
    public Metadata head(String key) { return client.head(key); }
    public void remove(String key) { client.remove(key); }
}
```

필드·레코드 컴포넌트·생성자 파라미터에는 비어 있지 않은 고정 키를 지정합니다. 메서드·클래스·인터페이스에는 `value` 없이 사용하며 값을 지정하면 시작 시 거부합니다. 클래스/인터페이스 지정은 해당 Spring 빈의 상속 메서드를 포함한 public 인스턴스 메서드 전체에 적용됩니다. `Object` 메서드와 static 메서드는 전체 지정에서 제외됩니다. 같은 메서드에 다시 `@Anchor`를 붙여도 한 번만 기록합니다.

클래스 전체를 지정해도 프록시의 제한은 같습니다. private 메서드는 대상이 아니며 직접 지정하면 실패합니다. 클래스 프록시의 final 메서드나 JDK 프록시에 노출되지 않은 public 메서드는 시작 시 실패합니다. 어노테이션이 붙은 상위 클래스·인터페이스도 인식합니다. 필드 지정만 있는 DTO는 캡처 프록시가 되지 않으며, 레코드 컴포넌트에서 접근자로 전파된 어노테이션 역시 필드 키로만 처리합니다.

## 필드명 리팩터링: `@Anchor`

DTO의 Java 필드명이 바뀌어도 같은 snapshot 키를 유지하려면 `io.github.unstoppableworm.anchor.annotation.Anchor`를 붙입니다. `value`는 리팩터링 전후에 동일하게 유지합니다.

```java
// 리팩터링 전
record Before(@Anchor("total") int total) {}

// 리팩터링 후
record After(@Anchor("total") int payableAmount) {}

// 둘 다 {"total": 25200}으로 기록·비교됩니다.
```

일반 DTO의 필드(상속 필드 포함), 생성자 파라미터, Java record component에 적용할 수 있습니다. getter/setter의 JSON 키도 해당 필드의 지정을 따릅니다. 중첩 객체와 컬렉션 안의 DTO에도 적용됩니다. 변경된 값을 무시하는 기능은 아니므로 `payableAmount`의 값이 달라지면 `/outcome/value/total`에서 차이를 보고합니다.

최초 입력 복원에도 같은 규칙을 사용합니다. 예를 들어 `customerId`를 `buyerId`로 바꾼 입력 DTO에 `@Anchor("customerId")`를 붙이면, 기존 파일의 `customerId`를 새 필드로 읽어 `replay`를 실행합니다. 애너테이션이 없던 기준도 기존 JSON 키를 value로 지정하면 재사용할 수 있습니다. 기존 키와 다른 새 value를 도입할 때에는 리팩터링 전에 기준을 새로 기록해야 합니다.

앵커는 같은 객체 안에서 유일한 공백 아닌 문자열이어야 합니다. 중복된 앵커, 일반 필드와의 키 충돌, 동일 속성의 필드/생성자 파라미터에 지정한 상충하는 앵커는 오류로 처리합니다. 애너테이션이 없는 속성은 기존 직렬화 규칙을 따릅니다.

필드 키 지정은 snapshot 전용 mapper에서만 해석합니다. 일반 애플리케이션의 JSON 이름이나 Mongo/SQL 필드 이름은 바꾸지 않습니다. 같은 속성의 `@JsonProperty`보다 snapshot 앵커를 우선하고, naming strategy가 앵커의 명시적인 이름을 다시 바꾸지 않도록 합니다. 사용자 제공 mapper는 복사해서 사용하며 `USE_ANNOTATIONS`가 꺼져 있으면 거부합니다. snapshot mapper의 명시적 이름에 대한 `ALLOW_EXPLICIT_PROPERTY_RENAMING`은 비활성화됩니다. 일반 불변 DTO는 Jackson이 사용할 생성자도 있어야 합니다. `@JsonCreator` 생성자의 파라미터 이름을 보존하지 않는 빌드에서는 파라미터에도 앵커를 명시하세요.

Map/Document/이미 직렬화된 HTTP JSON의 문자열 키를 자동으로 치환하지는 않습니다. 해당 경로는 Java 필드 애너테이션 정보가 없으므로 실제 전송·저장 계약대로 비교합니다. 사용자 정의 serializer가 직접 키를 쓰는 경우에도 그 serializer에서 앵커 규칙을 적용해야 합니다. 메서드명·bean 이름·파라미터 타입에 따른 `operation` 비교 규칙은 별도입니다.

## 직접 지정하는 외부 경계: `@Anchor`

기본 감지 대상에 없는 Redis/gRPC/메시지 브로커/객체 저장소 SDK 등의 호출은 소비자가 감싼 Spring bean 메서드에 `io.github.unstoppableworm.anchor.annotation.Anchor`를 붙입니다. 별도 제품용 모듈 없이 해당 메서드를 `METHOD` 종류로 기록합니다.

```java
@Component
public class ObjectStorageGateway {
    private final StorageClient client;
    public ObjectStorageGateway(StorageClient client) { this.client = client; }

    @Anchor
    public Metadata head(String bucket, String key) {
        return client.head(bucket, key);
    }
}
```

- `record`: 메서드를 실제로 실행해 호출 전후 인자, 반환값/예외, 순번과 부모를 기록·비교합니다.
- `replay`: 메서드 본문을 실행하지 않고 해당 순번의 결과와 인자 변경을 복원합니다. 입력이 달라지거나 기록이 없으면 실제 인프라로 연결하지 않고 실패합니다.
- 메서드 본문 내부의 SDK/HTTP/DB 호출은 **하나의 경계 안에 포함**됩니다. 내부 계측은 중복 기록하지 않습니다. 여러 외부 호출을 각각 비교하려면 업무 로직은 경계 밖에서 실행하고 실제 외부 호출 지점을 각각 지정하세요.

`@Anchor`는 재생에서 대체하는 외부 의존성에 사용합니다. 일반 업무 메서드는 별도 계측 없이 실행합니다. 자동 인식되는 클라이언트에 붙여도 중복 기록하지 않습니다. 기본 operation은 메서드 이름·bean 이름·파라미터 타입이며, 각 위치에 비어 있지 않은 value를 지정하면 해당 이름을 고정할 수 있습니다.

인터페이스 선언, 구현 메서드, 상속 메서드의 애너테이션을 인식합니다. **Spring bean의 public 인스턴스 메서드를 프록시 밖에서 호출**해야 합니다. 직접 생성한 객체나 같은 bean 내부의 self-invocation은 가로채지 못합니다. private/static 메서드, 클래스 프록시에서의 final 메서드처럼 적용할 수 없는 지정은 시작 시 실패합니다. 이미 노출된 인터페이스 프록시는 final 구현도 처리할 수 있습니다.

일반 DTO/컬렉션·void·예외·가변 인자의 복원은 기존 replay 규칙을 따릅니다. 특수 SDK 타입은 `BoundaryReplayAdapter`에서 `kind="METHOD"`를 처리할 수 있습니다. 본문 내부의 전역 캐시 변경이나 다른 부수 효과를 자동 재현하지는 않습니다. 재생에서 실제 실행해야 할 캐시·업무 로직은 경계 밖에 두고 `SharedState`로 상태를 검증하세요.

소비 예제의 `external/ObjectStorageGateway`는 클래스에 `@Anchor`를 붙입니다. 이 클래스와 `ObjectStorageRegressionTest`는 객체 저장소 SDK 대역으로 기록/SDK 접근 없는 재생을 확인합니다. 특정 클라우드 제품과 실제 통신한 검증은 아닙니다.

## 이름 변경에도 유지되는 외부 경계 식별자

`@Anchor` 하나를 위치에 따라 사용합니다. 앵커 값을 리팩터링 전후에 유지하면 Java 이름이 달라져도 같은 외부 작업으로 대응할 수 있습니다.

| 위치 | 비어 있지 않은 value의 의미 | 지정하지 않은 부분 |
|---|---|---|
| 클래스·인터페이스 | 외부 경계 식별자 | Spring 빈 이름 |
| 메서드 | 외부 작업 식별자 | Java 메서드 이름 |
| 외부 메서드 파라미터 | 해당 위치의 인자 식별자 | Java 파라미터 타입의 전체 이름 |
| 필드·record component·역직렬화 생성자 파라미터 | JSON 필드 키 | 기존 직렬화 규칙 |

```java
// 이름 변경 전
@Anchor("order-store")
interface OrderMapper {
    @Anchor("save-order")
    int insert(@Anchor("order") OrderRow row);
}
record OrderRow(@Anchor("amount") int total) {}

// 이름 변경 후: 클래스·빈·메서드·DTO·변수명·패키지를 바꿔도 키는 유지한다.
@Anchor("order-store")
interface PurchaseMapper {
    @Anchor("save-order")
    int persist(@Anchor("order") PurchaseRow payload);
}
record PurchaseRow(@Anchor("amount") int payableAmount) {}
```

위 예시의 operation은 `anchor:order-store#save-order(@order)`입니다. 한 부분만 지정하면 그 부분만 안정화합니다. 예를 들어 메서드에만 `@Anchor("save-order")`를 붙이면 빈 이름과 파라미터 타입 이름은 계속 식별에 사용합니다. 메서드 파라미터의 Java 변수명이나 `-parameters` 컴파일 옵션은 사용하지 않습니다. 파라미터에만 붙이는 앵커는 일반 업무 메서드를 캡처 대상으로 만들지 않습니다. 자동 인식 경계이거나 클래스·메서드에 캡처 지정이 있어야 합니다.

새 이름이 하나도 없으면 기존 `bean#method(java.Type,...)` 문자열을 그대로 사용합니다. 새 이름이 있으면 `anchor:` 접두부와 이스케이프한 구성 요소를 사용하고 파라미터 앵커에는 `@` 표시를 붙여 Java 타입 이름과 구별합니다. `argumentsBefore/After`는 순서 있는 배열 그대로입니다. 파라미터 추가·삭제·순서 변경은 자동 호환하지 않으며 DTO 내부 키는 별도의 필드 앵커로 고정해야 합니다.

클래스·메서드의 무값 `@Anchor`는 기존처럼 `METHOD` 종류의 통째 재생 경계를 만듭니다. 타입이나 해당 메서드의 상속 선언에 무값 표시가 있으면 이 기존 동작을 우선 유지합니다. 이름 지정만 사용하는 자동 인식 경계는 MyBatis의 `MYBATIS`, Feign의 `FEIGN`, Mongo의 `MONGODB` 종류·지원 범위·재생 어댑터를 유지합니다. 이름을 붙인다고 Mongo의 지원하지 않는 API가 새로 허용되지는 않습니다. 일반 gateway의 이름 지정은 `METHOD` 경계로 동작하며 클래스 지정은 상속을 포함한 public 메서드를 캡처합니다. 어느 경우에도 같은 호출을 두 번 기록하지 않습니다.

인터페이스·구현·상속·브리지 메서드에서 값을 찾습니다. 같은 위치에 서로 다른 비어 있지 않은 값이 있으면 충돌로 거부하며 자동으로 하나를 선택하지 않습니다. 동일한 값의 반복 지정은 허용합니다. 공백 문자열, 외부 메서드 파라미터의 빈 값, 한 메서드의 중복 파라미터 키도 거부합니다. 같은 종류의 서로 다른 빈에 같은 경계 키를 주거나, 오버로드가 같은 operation으로 합쳐지면 시작 시 오류가 납니다. 같은 실제 빈의 별칭·브리지는 중복으로 취급하지 않습니다. 서로 다른 버전의 빈은 예제처럼 별도 컨텍스트에서 기록·재생하세요.

적용 순서는 **이름 변경 전 앵커 지정 → 그 코드로 새 기준 기록 → 앵커를 유지하면서 리팩터링 → 재생**입니다. 기존 무앵커 기준은 계속 재생할 수 있지만 이미 기록한 뒤 처음 앵커를 붙인 경우의 과거 식별자는 추측해서 연결하지 않습니다. 자동 마이그레이션이나 기준 덮어쓰기는 없습니다. 저장 모델과 formatVersion 3은 그대로입니다.

앵커는 이름을 고정할 뿐 값의 변경을 숨기지 않습니다. SQL 변경, HTTP URI·본문 계약 변경, 파라미터 순서·개수 변경, JSON 구조·값 의미 변경은 이 기능의 지원 범위 밖입니다. `Class<?>` 인자나 다형성 JSON 식별자처럼 타입 이름이 실제 기록값에 들어가면 그 값은 계속 비교합니다. 앵커는 DTO의 반환 타입·인자 변경을 복원할 수 있는 기존 타입 조건도 완화하지 않습니다.

`StableBoundaryIdentityTest`는 MapperProxy와 일반 gateway의 이름 변경, 생성 키·반환값 복원, 기존 JDK 프록시의 제네릭 인터페이스, 상속·충돌·부분 지정·기존 무값 동작을 검증합니다. 소비 예제의 `BoundaryRenameReplayTest`는 서로 다른 패키지의 gateway와 DTO로 같은 JSON 파일을 재생하며 SDK 호출은 기록 시 한 번뿐임을 확인합니다.

## 기본 계측 대상

| 대상 | 자동 기록 범위 |
|---|---|
| MyBatis Spring Mapper bean | 실제 `MapperProxy`의 메서드 입력, 반환값, 예외, 실행 후 인자. 상속받은 mapper 메서드와 생성 키 포함 |
| MongoTemplate bean | `find`, `findOne`, `findAll`, `findById`, `findDistinct`, `findAndModify`, `findAndReplace`, `findAllAndRemove`, `findAndRemove`, `count`, `exactCount`, `estimatedCount`, `exists`, `insert`, `insertAll`, `save`, `upsert`, `updateFirst`, `updateMulti`, `remove` |
| RestClient | Spring Boot가 제공하는 `RestClient.Builder`로 생성한 클라이언트의 HTTP 요청/응답 |
| RestTemplate | Spring bean 또는 Boot의 `RestTemplateBuilder`로 만든 인스턴스의 HTTP 요청/응답 |
| Feign | Spring bean인 Feign 프록시의 메서드 입력/반환값/예외. Spring Cloud `@FeignClient` 인터페이스도 감지 |
| 메서드·클래스의 `@Anchor` | 개별 메서드 또는 해당 빈의 public 인스턴스 메서드 전체를 기록·재생 |

Mongo Query는 필터·projection·sort·skip·limit·hint·collation·meta를, Update는 update 문서·array filter를 기록합니다. 쓰기 결과의 matched/modified count 및 upserted ID도 비교합니다. 일반 DTO는 Jackson으로 직렬화하고 알 수 없는 객체를 빈 문자열로 숨기지 않습니다. 커스텀 타입은 `AnchorAutoConfiguration.CodecCustomizer` bean으로 전용 Jackson 2 serializer를 등록할 수 있습니다. 애플리케이션의 Jackson 3 설정과 기준 파일 설정은 독립적입니다.

HTTP는 메서드·전체 URI·선택한 헤더·본문·응답 상태를 기록합니다. JSON 본문은 구조로 비교하고, 텍스트/바이너리 본문은 text/base64로 보존합니다. HTTP 오류 응답도 저장하며 응답 스트림을 다시 제공하므로 정상적인 client decoding/error handling이 유지됩니다. 기본 헤더 목록에는 인증/Cookie/Date가 없습니다. URI와 본문, DB 결과의 값은 그대로 저장되므로 테스트용 데이터로 기준을 만드세요. 제외 경로는 비교에만 적용되며 저장 데이터에서 값을 지우지 않습니다.

## 외부 작업의 전체 순서로 비교

동일한 최초 입력에서 **같은 외부 작업이 같은 순서·인자·횟수로 발생하는지** 검사합니다. 중간 업무 클래스명·메서드명·시그니처·호출 경로는 판정하지 않으므로 클래스 이동, helper 추출·인라인, private 메서드 이름 변경을 허용합니다. 일반 서비스에 관찰 어노테이션을 붙일 필요는 없습니다.

```text
변경 전: service → helper → DB 조회 → DB 저장 → HTTP 호출
변경 후: domain object   → DB 조회 → DB 저장 → HTTP 호출  (외부 계약이 같으면 통과)
순서 변경:                DB 조회 → HTTP 호출 → DB 저장  (실패)
```

`CallAlignment`는 기준 목록의 같은 위치에서 `sequence`, `kind`, `operation`을 검사합니다. 엔진은 호출 전 인자도 검사한 뒤 해당 순번의 반환값·예외·인자 변경을 복원합니다. 다른 위치에서 일치하는 입력을 찾거나 메서드별로 묶고 정렬하지 않습니다. 같은 작업을 같은 인자로 여러 번 호출하여 A/B/C가 기록됐으면 그대로 A/B/C를 재생합니다. 누락·추가·반복 횟수 차이는 실패합니다.

최종 반환값·예외·공유 상태는 현재 업무 코드를 실행한 결과로 비교합니다. 외부 요청 목록이 같아도 업무 코드가 최종 배열을 B/A/C로 바꾸면 불일치입니다. 요청 본문의 배열 순서 역시 비교합니다. 반면 캡처 가능한 작업·인자·결과와 최종 상태가 모두 같다면 내부의 두 호출을 서로 교환한 의도 자체는 식별할 수 없습니다.

Spring 메서드 경계는 앵커로 지정한 안정적인 이름을 사용하고, 지정하지 않은 부분은 기존 빈 이름·메서드명·파라미터 타입 이름을 사용합니다. HTTP 경계는 메서드·URI와 기록하는 요청 정보를 검사합니다. 외부 경계 이름 변경은 위 절의 사전 앵커 지정 조건에서 지원합니다. SQL 쿼리 변경이나 실제 DB 최종 상태·트랜잭션 효과의 동등성도 검증하지 않습니다. replay는 현재 외부 시스템의 응답을 다시 조회하지 않습니다.

새 기록의 형식은 계속 **formatVersion 3**입니다. 공개 저장 모델의 `callerPath`는 남기되 새 기록에서는 빈 목록으로 저장하며 Java 스택을 수집하지 않습니다. v1·v2·v3 모두 같은 전체 순서 규칙을 적용하고, 과거 `callerPath`의 유무·내용과 `parentSequence` 차이는 동등성 판정에서 제외합니다. 기존 파일의 재기록이나 버전 변경은 필요 없습니다.

`parentSequence`는 직접 중첩 캡처의 관계를 저장하고, 기록된 자식이 있는 외부 경계 전체를 응답 하나로 대체하여 자식 호출을 누락시키지 않도록 하는 안전 검사에 계속 사용합니다. 호출 순번과 부모 참조의 기존 v3 무결성 검사도 유지합니다. 비교에서 부모 관계를 제외하는 것이 중첩 재생 제한을 없애는 뜻은 아닙니다. 공개 `capture()`로 직접 추가한 호출도 캡처 목록의 일부이므로 그 호출 자체를 추가·제거하면 횟수 차이로 실패합니다.

매칭 실패 시 실제 의존성을 호출하는 fallback은 없습니다. 오류를 업무 코드가 잡아도 전체 회귀 실행은 실패합니다. 보고서의 `actual.calls`와 차이 경로는 캡처된 순서 그대로이며, 최종 비교용 JSON에서만 호출 경로·부모 메타데이터를 제외합니다. 원본 스냅샷이나 인자 안의 같은 이름을 가진 필드는 수정하지 않습니다.

`CallerContextTest`는 내부 이름·클래스·구조 변경 허용과 외부 작업 순서·인자 변경 거부를 검증합니다. `CapturedCallOrderTest`는 최종 배열의 결과 교환을 검출합니다. `ReplayTest`와 `SnapshotComparatorTest`는 과거 경로와 변경된 부모 관계를 가진 v1·v2·v3 기준의 호환성, 순번별 결과·최종 상태 비교 및 원본 보존을 확인합니다.

## 가상 스레드 호출부를 바꾸지 않는 실행 맥락 전달

회귀 실행 안에서 새로 만드는 자식 스레드에는 `InheritableThreadLocal`로 실행 맥락을 자동 전달합니다. 기존 `Thread.startVirtualThread(...)`, `Executors.newVirtualThreadPerTaskExecutor().submit(...)` 호출에 Anchor 래퍼를 추가하지 않습니다. 가상 스레드를 사용하는 `SimpleAsyncTaskExecutor`의 Spring `@Async`도 같은 방식으로 동작합니다. executor 객체는 회귀 실행 전에 만들어 두어도 됩니다. 작업별 스레드가 실행 안에서 생성되어야 합니다.

```java
// 기존 업무 코드 그대로: Anchor 전용 래퍼가 필요하지 않습니다.
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var result = executor.submit(() -> mapper.findById(id));
    return result.get();
}
```

Java 21 가상 스레드 builder는 기본적으로 상속 가능한 스레드 로컬을 전달합니다([공식 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.Builder.OfVirtual.html#inheritInheritableThreadLocals(boolean))). 엔진은 스냅샷·호출 순번·실패 상태만 실행별로 공유하고, 호출 스택과 중복 캡처 억제 상태는 스레드별로 분리합니다. 자식에는 실행 핸들·계측된 부모 순번·억제 상태를 전달하며 업무 호출 경로는 수집하지 않습니다. 외부 메서드 실행 중에는 공통 잠금을 잡지 않아 부모가 자식을 기다릴 수 있습니다.

병렬 작업도 **계측 지점에 진입한 전체 순서**를 보존합니다. 스케줄링 때문에 서로 다른 호출의 순서가 달라져도 replay는 실패합니다. 실행 순서를 재배치하거나 일치할 때까지 기다려서 순서 변경을 숨기지 않습니다.

업무 함수는 평소의 `get`/`join` 또는 executor 종료 대기로 자식 작업까지 마친 후 반환해야 합니다. 실행 종료 시 진행 중인 캡처 호출이 있으면 불완전한 기준 저장을 거부합니다. 종료된 맥락을 물려받은 자식의 늦은 외부 호출도 거부합니다. 엔진이 모든 fire-and-forget 작업의 완료를 자동 추적하거나 기다리는 기능은 아닙니다.

이미 만들어진 스레드 풀, `CompletableFuture` common pool, Reactor/WebClient, `inheritInheritableThreadLocals(false)`는 이 자동 연결 범위에 포함되지 않습니다. 이 엔진에 replay가 진행 중일 때 맥락 없는 계측 대상 호출이 들어오면 실제 I/O를 거부하고 진행 중인 replay도 실패시킵니다. 맥락이 없으므로 어느 실행에 속했는지 추측하지 않습니다. 고정 REPLAY 모드는 실행 범위 밖에서도 계속 차단합니다. MANUAL 모드에서 replay가 끝난 뒤 도착한 맥락 없는 작업까지 식별하는 기능이나 계측 밖 I/O를 막는 네트워크 차단 기능은 아닙니다.

`VirtualThreadCaptureTest`와 `VirtualThreadInterceptionTest`에서 기존 executor·직접 생성·중첩 자식·가상 스레드 `@Async`, 동시 기록, 부모/형제 맥락 격리, 인자 변경과 예외 복원, 순서 변경 거부, 늦은 호출 차단을 확인합니다.

## 실행 의미와 현재 지원 범위

- `record`는 SQL/Mongo 쓰기와 HTTP 요청까지 실제로 실행하여 기준을 만듭니다. `replay`는 그 기록으로 현재 업무 코드를 실행·검증하므로 현재 DB/API 응답에 의존하지 않습니다.
- 최초 입력은 저장하지만 실행할 Java 함수를 파일로 저장하지 않습니다. 검증 시 진입점 함수(`service::place`)와 입력 타입을 지정합니다.
- 동기 호출과 기본 상속 설정으로 새로 생성하는 자식 스레드의 흐름을 추적합니다. 가상 스레드의 기존 제출 코드를 유지할 수 있으며 독립 회귀 실행은 분리합니다. 위 절의 생성·종료 조건을 따릅니다. 캡처하는 외부 경계 자체의 Future/Publisher/Stream 반환은 거부합니다. `@Async` 업무 함수 안에서 일반 반환값을 가진 경계를 호출하고 완료를 기다리는 것은 지원합니다.
- 외부 메서드를 가로채는 계측은 Spring proxy를 통과하는 호출이 대상입니다. 직접 `new`로 만든 mapper/gateway, 같은 bean의 self-invocation과 private/static 메서드는 자동 추적 대상이 아닙니다. 클래스 프록시의 final 메서드도 가로챌 수 없습니다. 추가 클래스 프록시 대상은 non-final 클래스로 만듭니다.
- `RestClient.create()`로 직접 생성한 클라이언트는 자동 계측되지 않습니다. Boot builder를 주입하거나 `AnchorHttpInterceptor`를 직접 등록합니다. Feign은 HTTP transport 내부 retry 횟수/헤더 대신 논리적 메서드 결과를 기록합니다.
- Mongo fluent API, bulk, stream, aggregation, raw driver 접근은 활성 범위에서 명시적으로 거부합니다. Mongo admin/index 작업은 회귀 기록 대상이 아닙니다. MyBatis cursor/ResultHandler 같은 스트리밍 API 대신 반환 컬렉션을 사용하세요.
- 앵커를 지정하지 않은 타입명과 bean 이름을 바꾸면 operation도 바뀔 수 있습니다. 외부 계약 변경을 의도한 경우 기준을 검토·갱신합니다. 임의 객체 그래프·순환 참조·거대/스트리밍 본문은 지원하지 않습니다.
- 저장소의 동시성·원자적 생성·일관성·보존 정책은 소비 구현체의 책임입니다. 저장 실패를 숨기지 않으며 이미 수행된 업무의 부수 효과를 되돌리지는 않습니다.

## 코드 탐색

실행 엔진은 `execution`, JSON은 `snapshot`, 비교와 보고서는 `verification`, 재생 값 복원은 `replay`, 저장소 계약은 `storage`, 어노테이션은 `annotation`으로 나누었습니다. starter는 `spring.autoconfigure`, `spring.interception`과 `spring.mybatis`·`spring.mongo`·`spring.http`·`spring.feign`으로 나눕니다. 전체 패키지 지도와 클래스별 소스 링크는 [구조 안내](docs/architecture.md#역할별-패키지)에 있습니다.

MyBatis 캡처의 시작점은 [MyBatisAnchorAutoConfiguration](regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/mybatis/MyBatisAnchorAutoConfiguration.java)입니다. 실제 `MapperProxy` 빈을 찾아 [BoundaryPostProcessor](regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/BoundaryPostProcessor.java)에 `MYBATIS` 계측을 맡깁니다. SQL 실행 전에 엔진으로 전달하므로 replay에서는 원래 매퍼 호출을 생략합니다.

패키지를 분리하면서 Java import 경로가 변경되었습니다. 소비 앱은 새 경로로 import해야 하며, 의존성 좌표와 저장 JSON 형식은 바뀌지 않았습니다.

## 프로젝트

- `regression-anchor-core`: Spring 없는 기록 범위, JSON snapshot, 비교기, 저장소 인터페이스
- `regression-anchor-spring-boot-starter`: 자동 구성 및 MyBatis/Mongo/HTTP/Feign/사용자 bean 계측
- 소비자 예제: 별도 `tech-playground` 저장소의 `regression-anchor-target`

```sh
./mvnw verify
```

테스트는 반복 호출, 부모 관계, 입력 변경, 출력 변경, 누락/추가 호출, 인자 mutation, 예외, null, 숫자/BSON 저장 안정성, thread 격리, 기준 보존, 선택적 의존성, HTTP 오류/크기 제한, Feign 계측, 공유 상태 복원·정리, 엄격한 재생 매칭, 생성 키 복원, 제네릭 반환 타입을 다룹니다. 실제 MyBatis/H2와 Mongo wire protocol, Spring Boot RestClient의 종합 검증은 소비자 예제에 있습니다.

설계에 참고한 공식 문서: [MyBatis mapper injection](https://mybatis.org/spring/mappers.html), [Spring REST clients](https://docs.spring.io/spring-boot/reference/io/rest-client.html), [MongoTemplate CRUD](https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-crud-operations.html).
