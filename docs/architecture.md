# regression-anchor 구조 및 클래스 안내

이 라이브러리는 애플리케이션에 의존성으로 추가하여 **같은 JVM 안에서** 실행한다. 최초 입력으로 현재 업무 코드를 실행하면서 외부 호출의 입력·출력·예외·순서를 기록하거나 재생한다. 별도 테스트 서버를 호출하는 구조가 아니다.

## core와 starter

| 모듈 | 책임 | 사용하는 쪽 |
|---|---|---|
| `regression-anchor-core` | 실행 범위, 기록·재생·비교, JSON 형식, 저장소·공유 상태 인터페이스 | Spring 없이 직접 사용하거나 starter를 통해 사용 |
| `regression-anchor-spring-boot-starter` | Spring 빈 자동 구성, 프로필 조건, AOP와 HTTP 인터셉터, 인프라 타입 복원 | Spring Boot 애플리케이션 |

Spring Boot에서는 starter 하나를 의존성으로 추가하면 core도 함께 들어온다. core는 Spring 프로필을 알지 못한다. starter가 `regression` 프로필과 `regression-anchor.enabled` 조건을 검사하고 엔진을 구성한다.

```text
애플리케이션의 회귀 실행 코드
  └─ RegressionAnchor.run(RECORD 또는 REPLAY, caseId, input, 업무 함수)
       ├─ 현재 업무 함수 실행
       │    └─ 외부 호출 → starter의 AOP / HTTP 인터셉터
       │         └─ RegressionAnchor에 호출 정보 전달
       │              ├─ RECORD: 실제 외부 호출 후 결과 기록
       │              └─ REPLAY: 외부 작업 순번·식별자·입력 확인 후 해당 순번의 결과 복원
       ├─ 최초 입력·최종 출력·호출 목록·공유 상태 기록 또는 비교
       └─ SnapshotCodec → JSON 바이트 ↔ 소비자의 SnapshotStore
```

## 역할별 패키지

core의 기준 경로는 `io.github.unstoppableworm.anchor`, starter는 그 아래 `spring`이다.

| core 패키지 | 책임 | 주요 클래스 |
|---|---|---|
| `annotation` | 사용자가 붙이는 캡처·직렬화 표시 | `Anchor` |
| `execution` | 회귀 실행, 모드, 스레드 맥락, 공유 상태 관리 | `RegressionAnchor`, `ExecutionMode`, `SharedState`, `SharedStateScope` |
| `snapshot` | 기록 모델과 JSON 직렬화·역직렬화 | `Snapshot`, `SnapshotCodec`, `AnchorPropertyModule` |
| `verification` | 호출 대응, 값 비교, 차이와 보고서 | `CallAlignment`, `SnapshotComparator`, `Difference`, `Verification`, `VerificationReport` |
| `replay` | 기록된 값·예외·인자 변경 복원 | `ReplayAdapter`, `ReplayValues`, `ReplayMismatchException` |
| `storage` | 소비자가 구현하는 저장·조회 계약 | [`SnapshotStore`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/storage/SnapshotStore.java) |

| starter 패키지 | 책임 | 주요 클래스 |
|---|---|---|
| `spring.autoconfigure` | 엔진 조립, 프로필과 설정값 | `AnchorAutoConfiguration`, `AnchorProperties` |
| `spring.interception` | 공통 AOP와 Anchor 처리, 메서드 결과 복원 | `BoundaryPostProcessor`, `AnchoredMethods`, `BoundaryIdentity`, `MethodReplay`, `MethodReturnTypeResolver`, `BoundaryReplayAdapter` |
| `spring.mybatis` | MyBatis 매퍼 감지와 계측 연결 | [`MyBatisAnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/mybatis/MyBatisAnchorAutoConfiguration.java) |
| `spring.mongo` | MongoTemplate 계측, Mongo JSON과 반환 타입 처리 | `MongoAnchorAutoConfiguration`, `MongoSnapshotModule`, `MongoReturnTypeResolver` |
| `spring.http` | RestClient·RestTemplate HTTP 캡처·재생 | `HttpAnchorAutoConfiguration`, `AnchorHttpInterceptor` |
| `spring.feign` | Feign 프록시 감지와 계측 연결 | [`FeignAnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/feign/FeignAnchorAutoConfiguration.java) |

테스트도 같은 역할의 패키지에 두고, 테스트용 저장소와 공통 설정은 각 모듈의 `testsupport`에 모았다. `CallAlignment`, `BoundaryPostProcessor`, `MethodReturnTypeResolver`의 공개 접근은 패키지 간 내부 구현 연결을 위한 것이다. 소비자는 이 보조 구현을 생성할 필요 없이 실행 API와 필요한 확장 인터페이스를 사용한다.

기존 루트 패키지의 import는 아래처럼 변경한다. Maven/Gradle 좌표와 snapshot JSON 형식은 그대로다. 예제 앱의 import도 함께 갱신했고 저장된 기준 파일은 바꾸지 않았다.

```java
import io.github.unstoppableworm.anchor.annotation.Anchor;
import io.github.unstoppableworm.anchor.execution.ExecutionMode;
import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.execution.SharedState;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.storage.SnapshotStore;
import io.github.unstoppableworm.anchor.verification.Verification;
```

## MyBatis 캡처를 찾는 순서

1. `spring.mybatis.MyBatisAnchorAutoConfiguration`이 Spring 빈의 실제 타깃이 MyBatis `MapperProxy`인지 확인한다. 임의 공통 인터페이스 상속이나 매퍼별 회귀 어노테이션은 필요 없다.
2. `spring.interception.BoundaryPostProcessor`가 매퍼 프록시에 advice를 연결한다. 호출 종류는 `MYBATIS`, 식별자는 빈 이름·메서드명·파라미터 타입을 기본으로 하고 각 위치의 비어 있지 않은 Anchor 값으로 대체하며, 원래 메서드 실행 전에 `RegressionAnchor.captureBoundary`로 전달한다.
3. `execution.RegressionAnchor`가 외부 작업 식별자·순번·입력을 기록한다. record에서는 원래 매퍼를 실행하여 SQL 결과·예외·변경된 인자를 캡처하고, replay에서는 기준의 호출 식별자·순번·입력을 확인한 뒤 저장 결과를 복원한다.
4. `spring.interception.MethodReplay`와 `replay.ReplayValues`가 반환 타입과 생성 키 등의 인자 변경을 복원한다. replay에서는 원래 매퍼를 실행하지 않는다.

MyBatis Executor 플러그인이 아니라 **Spring에 등록된 매퍼 빈의 메서드 호출**을 가로채는 구조다. 자동 설정은 `regression` 프로필에서만 활성화된다. 매퍼를 통하지 않는 raw JDBC 호출은 이 계측 범위에 포함되지 않는다.

## 실행 API를 구분하는 기준

| API | 현재 업무 코드 | 외부 호출 | 결과 |
|---|---|---|---|
| `record` | 실행 | 실제 실행 | 새 기준 저장, 업무 결과 반환 |
| `replay` | 저장된 최초 입력으로 실행 | 계측된 경계의 기록값 사용 | `Verification` 반환 |
| `replayInput` | 제공한 최초 입력을 기준과 대조한 뒤 실행 | 계측된 경계의 기록값 사용 | `Verification` 반환 |
| `run(RECORD, ...)` | `record` 사용 | 실제 실행 | 업무 결과 반환 |
| `run(REPLAY, ...)` | `replayInput` 사용 | 계측된 경계의 기록값 사용 | 비교까지 일치하면 업무 결과 반환, 불일치하면 실패 |

기준은 `record`로 만들고 리팩터링 검증은 `replay` 계열로 수행한다. `replay`는 기록된 외부 응답으로 현재 업무 코드를 실행하고 결과까지 비교한다. 모드 프로필로 고정한 엔진은 반대 모드 작업을 거부한다.

재생에서는 최종 응답만 읽어 반환하지 않는다. **바뀐 업무 코드 자체를 실행**하고, 그 코드가 외부 의존성을 부를 때 기록값으로 응답한다. 따라서 DB 응답이 같아도 새 업무 코드의 계산이나 최종 출력이 달라지면 검출할 수 있다.

## core 클래스

| 타입 | 역할 |
|---|---|
| [`RegressionAnchor`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/execution/RegressionAnchor.java) | 회귀 사례 한 건의 실행을 총괄한다. 기록, 재생, 공유 상태 관리, 비교, 저장을 연결하는 시작점이다. |
| [`ExecutionMode`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/execution/ExecutionMode.java) | RECORD·REPLAY 실행과 MANUAL 엔진 정책을 구분한다. MANUAL 엔진에서는 호출별로 모드를 선택한다. |
| [`Snapshot`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/snapshot/Snapshot.java) | 최초 입력, 최종 결과, 호출 목록, 공유 입력·출력을 담는 기준/실행 모델이다. |
| [`SnapshotCodec`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/snapshot/SnapshotCodec.java) | 값을 JSON으로 고정하고 기준·보고서를 UTF-8 JSON으로 쓰고 읽는다. |
| [`SnapshotStore`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/storage/SnapshotStore.java) | 소비자가 구현하는 바이트 저장·조회 인터페이스다. JSON 처리는 구현하지 않는다. |
| [`SnapshotComparator`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/verification/SnapshotComparator.java) | 같은 전체 순번의 외부 작업과 JSON 값을 비교하고 차이 목록을 만든다. |
| [`CallAlignment`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/verification/CallAlignment.java) | 기준과 현재 호출의 같은 인덱스에서 순번·종류·외부 작업 식별자를 검사한다. |
| [`Difference`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/verification/Difference.java) | 차이가 난 경로와 기준값·실제값을 담고 누락과 null을 구분한다. |
| [`Verification`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/verification/Verification.java) | 이번 실행 값, 스냅샷, 차이를 반환하며 일치 여부 확인과 assertion을 제공한다. |
| [`VerificationReport`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/verification/VerificationReport.java) | 저장용 보고서다. 일치 여부, 호출 수, 실제 스냅샷, 차이를 한 JSON 문서로 묶는다. |
| [`Anchor`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/annotation/Anchor.java) | 필드에서는 JSON 키를, 클래스·메서드·외부 메서드 파라미터에서는 호출 식별자를 고정한다. 클래스·메서드는 기존 무값 캡처도 지원한다. |
| [`AnchorPropertyModule`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/snapshot/AnchorPropertyModule.java) | Anchor를 전용 Jackson mapper에 적용하고 이름 충돌을 검사한다. |
| [`ReplayAdapter`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/replay/ReplayAdapter.java) | 외부 호출 한 건을 기록값으로 복원하는 core 확장점이다. |
| [`ReplayValues`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/replay/ReplayValues.java) | 반환값·예외·변경된 인자를 복원하는 공통 유틸리티다. |
| [`ReplayMismatchException`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/replay/ReplayMismatchException.java) | 재생 입력·외부 작업 식별자·전체 호출 순서·횟수 등이 기준과 다를 때 실행을 실패시킨다. |
| [`SharedState`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/execution/SharedState.java) | 캐시 등 메서드 인자에 없는 상태를 조회·복원하는 소비자 인터페이스다. |
| [`SharedStateScope`](../regression-anchor-core/src/main/java/io/github/unstoppableworm/anchor/execution/SharedStateScope.java) | replay에 기록된 공유 입력을 설정하고 종료 후 원래 상태로 복구한다. |

`Snapshot.Call`은 계측된 호출 한 건, `Outcome`은 반환값/실패, `Failure`는 예외 정보다. `RegressionAnchor` 내부의 `Session`은 스레드들이 공유하는 한 실행의 호출 목록·순번·실패 상태다. `ThreadContext`는 스레드별 스택·억제 상태·상속받은 부모 호출 순번를 보관하고, `ScopeHandle`은 종료 시 큰 세션 참조를 해제하면서 늦은 자식 호출을 차단한다. `PendingCall`은 작성 중인 호출 기록, `Run`은 실행을 마친 내부 결과다. `Trigger`와 `Invocation`은 각각 최초 업무 함수와 계측된 원래 메서드를 전달하는 콜백이다.

`AnchorPropertyModule.AnchorNames`는 실제 Jackson 프로퍼티 이름을 결정한다. 이처럼 외부에서 직접 사용할 필요가 없는 보조 타입도 해당 선언 위에 역할을 설명했다.

## starter 클래스

| 타입 | 역할 |
|---|---|
| [`AnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/autoconfigure/AnchorAutoConfiguration.java) | 프로필을 확인하고 엔진·코덱·명시적 메서드 계측을 구성한다. |
| [`AnchorProperties`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/autoconfigure/AnchorProperties.java) | 비교 제외 경로, HTTP 헤더 선택, 본문 크기 제한 설정을 받는다. |
| [`BoundaryPostProcessor`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/BoundaryPostProcessor.java) | 대상 빈에 AOP를 붙여 호출을 core 엔진에 연결한다. |
| [`AnchoredMethods`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/AnchoredMethods.java) | 클래스 전체 및 개별 메서드의 Anchor를 찾고 실제 프록시로 가로챌 수 있는지 검사한다. |
| [`BoundaryIdentity`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/BoundaryIdentity.java) | 상속·인터페이스·브리지의 앵커를 해석하고 충돌을 검사해 operation 이름을 만든다. JSON 인자 배열은 바꾸지 않는다. |
| [`MethodReplay`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/MethodReplay.java) | 기록된 인자 변경·예외·반환값을 복원하며 반환 타입 결정은 MethodReturnTypeResolver에 맡긴다. |
| [`MethodReturnTypeResolver`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/MethodReturnTypeResolver.java) | 메서드·대상 빈·실제 인자로 반환 타입만 결정하는 내부 포트다. 기술별 자동 설정에서 구현을 주입한다. |
| [`BoundaryReplayAdapter`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/interception/BoundaryReplayAdapter.java) | 기본 복원기로 다루기 어려운 타입을 소비자가 Spring 빈으로 확장하는 지점이다. |
| [`MyBatisAnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/mybatis/MyBatisAnchorAutoConfiguration.java) | 실제 MyBatis 매퍼 프록시를 자동으로 계측한다. |
| [`MongoAnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/mongo/MongoAnchorAutoConfiguration.java) | MongoTemplate의 지원되는 CRUD 메서드를 자동으로 계측한다. |
| [`MongoReturnTypeResolver`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/mongo/MongoReturnTypeResolver.java) | Mongo 패키지 내부 어댑터로 Class·문서 인자에서 반환 요소 타입을 결정한다. 공통 복원기를 구현체에 의존시키지 않는다. |
| [`MongoSnapshotModule`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/mongo/MongoSnapshotModule.java) | Query·UpdateDefinition·ObjectId·쓰기 결과·BSON의 JSON 변환 규칙을 제공한다. |
| [`HttpAnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/http/HttpAnchorAutoConfiguration.java) | 지원되는 Spring HTTP 클라이언트에 인터셉터를 등록한다. |
| [`AnchorHttpInterceptor`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/http/AnchorHttpInterceptor.java) | HTTP 요청/응답을 캡처하고 replay에서 네트워크 대신 저장된 응답을 반환한다. |
| [`FeignAnchorAutoConfiguration`](../regression-anchor-spring-boot-starter/src/main/java/io/github/unstoppableworm/anchor/spring/feign/FeignAnchorAutoConfiguration.java) | Feign 메서드 단위의 인자·반환값·예외를 캡처한다. |

`AnchorAutoConfiguration.CodecCustomizer`는 전용 JSON 변환 규칙을 추가하는 확장점이다. HTTP의 `Request`와 `Reply`는 저장 모델, `BufferedResponse`는 읽은 실제 응답을 다시 제공하는 래퍼, `ReplayedResponse`는 기록값으로 만든 응답이다. `MongoSnapshotModule.Writer`는 타입별 JSON 출력 함수를 받는 내부 인터페이스다.

## 기술별 반환 타입 해석

`MethodReplay`는 인자 변경 복원 → 예외 복원 → 반환 타입 결정 → JSON 변환 순서만 담당한다. `MONGODB` 같은 기술 문자열 분기나 Mongo 구현 import는 없다. 반환 타입은 `MethodReturnTypeResolver` 포트로 받는다.

기본 해석은 메서드 선언과 구현 클래스의 제네릭을 사용한다. Mongo의 `<T> List<T> find(Query, Class<T>)`는 실제 `Class<T>` 인자로 타입이 정해지므로 `MongoReturnTypeResolver`가 이 규칙을 맡는다. `findDistinct`에서는 조회 대상 `Class<?>`와 반환 값의 `Class<T>`를 구분하며, `save(T)`는 문서 인자의 타입을 사용한다. `MongoAnchorAutoConfiguration`이 계측 후처리기를 만들 때 이 구현을 주입한다.

따라서 의존성은 `spring.mongo` → `spring.interception`의 포트를 향한다. Mongo 어댑터는 패키지 내부 타입이며 소비자가 생성하거나 등록할 필요가 없다. 소비자가 등록한 `BoundaryReplayAdapter`가 해당 호출을 지원하면 그 어댑터가 먼저 전체 복원을 맡는다. 타입을 확정하지 못하는 호출은 계속 실패하며 실제 Mongo 호출로 전환하지 않는다.

## 외부 작업의 순서와 반복 호출

모든 지원 형식(v1·v2·v3)은 동일한 최초 입력과 전체 외부 호출 순서를 기준으로 비교한다. `CallAlignment`는 같은 인덱스의 순번·종류·작업 식별자만 매칭하고 엔진은 인자를 검사한다. 결과를 정렬하거나 같은 메서드끼리 묶지 않으며 반복 호출은 해당 순번의 응답을 재생한다. 최종 반환값·예외·공유 상태도 비교한다.

중간 클래스·메서드·시그니처·Java 호출 경로는 비교하지 않는다. 경로 수집과 자식 스레드의 생성 경로 연결을 제거했다. 저장 모델의 `callerPath`는 이전 JSON/API 호환성을 위해 유지하며 새 v3 기록에는 빈 목록을 넣는다. `SnapshotComparator.compare(Snapshot, Snapshot, codec)`은 호출 객체의 `callerPath`·`parentSequence`만 제거한 JSON을 비교한다. 일반 JSON 비교 API와 업무 인자의 같은 이름을 가진 필드는 그대로 비교한다.

부모 관계는 동등성 기준에서는 제외하지만 중첩 경계를 통째로 재생하여 자식을 건너뛰는 일을 막기 위해 저장한다. `expectedParents` 검사와 스레드별 캡처 스택은 유지한다. 공개 `capture()`로 추가한 호출도 여전히 전체 목록에 포함되므로 그 호출 자체의 추가·제거를 무시하지 않는다.

기존 기준은 재기록하지 않고 새 규칙으로 읽는다. 비어 있지 않은 앵커로 고정하지 않은 경계 빈·메서드명·파라미터 타입 변경은 여전히 불일치할 수 있다. 이름 변경 전에 앵커를 지정한 기준은 같은 앵커로 재생한다. SQL 변경 검증은 지원하지 않는다. 동일 요청의 반복은 순번대로 복원하며, 캡처 정보와 최종 결과까지 모두 같다면 내부 두 호출의 교환 자체를 식별하지는 못한다.

## 실행 코드를 읽는 단위

`RegressionAnchor.capture()`는 호출을 접수한 뒤 실행하고 반환값·예외·변경된 인자를 기록한다. `matchRecordedCall()`은 접수 단계의 세션 잠금 안에서 전체 순서·외부 작업 식별자·입력·중첩 경계 제한을 검사한다. `completeCalls()`는 같은 잠금 안에서 실행을 닫고 진행 중 호출·실패·누락 여부를 확인한다. 실제 업무 코드와 외부 메서드는 잠금 밖에서 실행하므로 부모가 가상 스레드의 결과를 기다릴 수 있다.

`BoundaryPostProcessor.postProcessAfterInitialization()`은 프록시 가능 여부와 안정적인 식별자 충돌을 검사하고 advice를 설치한다. 이름 해석은 패키지 내부 `BoundaryIdentity`가 맡고, 기존 `AnchoredMethods`는 캡처 대상과 프록시 조건을 검사한다. 각 기술의 실행 제한 predicate는 시작 시 평가하지 않는다. 명시적 계측기는 등록된 자동 계측기의 빈 판별 규칙을 확인하여 이름만 지정한 자동 경계를 원래 계측기에 맡긴다. 기술별 타입을 공통 계측기에 하드코딩하지 않는다. 무값 지정은 기존 METHOD/opaque 경계를 유지한다. 이후 호출은 `intercept()`가 처리하고, `replayAdapterFor()`가 소비자의 복원 어댑터를 우선 연결한다. 이 로직은 하나의 클래스에 두어 프록시 설치와 런타임 동작을 같은 파일에서 확인할 수 있다.

`AnchorHttpInterceptor.intercept()`는 요청을 캡처하고 `recordReply()` 또는 `restoreReply()`를 엔진에 연결한다. 기록 응답을 읽다 실패하면 즉시 닫고, 성공하면 클라이언트가 버퍼 응답을 닫을 때 실제 응답도 닫는다. 재생 응답은 네트워크 연결을 갖지 않는다. 헤더 선택과 본문 직렬화·복원은 이름으로 구분하며 JSON 미디어 타입 판정은 양쪽이 같은 규칙을 사용한다.

공개 API·패키지·저장 모델·설정은 변경하지 않았다. `run()`은 `record()` 또는 재생·검증을 선택하는 편의 API이며, `record()`·`replay()`를 직접 호출해도 같은 실행 맥락을 사용한다. 자식 스레드 전파는 기존 `InheritableThreadLocal.childValue()`가 담당한다.

## 안정적인 이름의 범위

operation의 경계·메서드·파라미터 구성 요소만 해당 위치의 앵커로 대체한다. 파라미터는 이름이나 타입명 대신 고정 키를 쓸 수 있지만 인자 배열의 순서·개수·값은 유지한다. DTO 필드·record component·생성자 파라미터의 Anchor는 기존 AnchorPropertyModule에서 JSON 키로 처리하며 외부 메서드 파라미터 식별과 분리한다.

같은 위치의 상속 선언에 서로 다른 키가 있거나 서로 다른 경계/오버로드가 합쳐지면 시작 시 거부한다. 무앵커와 기존 무값 앵커는 기존 operation 문자열을 유지한다. 처음 이름을 지정하는 경우에는 리팩터링 전 새 기준을 기록해야 하며 기존 식별자를 값으로 추측하지 않는다. 전체 예시, 부분 지정과 지원 한계는 README의 이름 변경 절을 참고한다.

## 소비자가 구현하는 부분

필수 구현은 `SnapshotStore`다. 원본 JSON 바이트를 그대로 저장·조회하고 중복 기준의 원자적 생성 실패를 보장한다. 라이브러리는 JSON 파싱, 비교, 보고서 형식을 맡는다.

인메모리 캐시 등의 영향도 검증하려면 `SharedState`를 등록한다. 특수 외부 타입의 재생이 필요할 때만 `BoundaryReplayAdapter`, 특수 타입의 JSON 표현이 필요할 때만 `CodecCustomizer`를 추가한다. 각 매퍼에 모드 프로필을 붙이거나 재생 전용 대역 빈을 만들 필요는 없다.

## 현재 적용 범위와 읽는 순서

계측은 Spring 프록시/등록된 HTTP 인터셉터를 통과하는 경계에 적용된다. `InheritableThreadLocal`로 기본 상속 설정의 새 자식 스레드에 실행 범위를 전달하므로 가상 스레드 제출 코드에 별도 래퍼는 필요 없다. 자식 생성 시 실행 핸들·부모 호출 순번·억제 상태를 전달하고, 형제의 스택과 억제 상태는 분리한다. 호출 순번은 세션 잠금 안에서 정하지만 실제 외부 메서드는 잠금 밖에서 실행한다. 동시 실행도 캡처 접수 순서를 유지하므로 스케줄링으로 외부 호출 순서가 달라지면 실패한다. 업무 함수는 자식 작업 완료 후 반환해야 하며, 진행 중인 호출이 남은 종료와 만료된 맥락의 늦은 호출은 거부한다. 이미 존재하는 풀이나 상속을 끈 스레드는 자동 연결하지 않는다. replay 진행 중 맥락 없는 계측 호출은 실제 I/O를 거부하고 해당 엔진의 진행 중 replay를 모두 실패시킨다. 외부 경계 자체가 자기 호출이거나 직접 생성한 객체인 경우에는 Spring 인터셉터가 적용되지 않는다. 상위 업무 코드의 private 자기 호출은 계측하거나 경로로 비교할 필요가 없다. HTTP 자동 연결을 거치지 않은 클라이언트와 지원 목록 밖 Mongo 경로도 별도 고려가 필요하다.

replay는 계측된 업무 호출의 외부 실행을 대체한다. DataSource나 MongoClient 자체를 제거하거나 애플리케이션 초기화 단계의 모든 연결 동작을 차단하는 기능은 아니다. 저장된 기준을 읽을 저장소도 여전히 필요하다.

처음에는 `RegressionAnchor` → `Snapshot` → `CallAlignment` → `SnapshotCodec` / `SnapshotStore` → `BoundaryPostProcessor` 순서로 읽으면 된다. 이어서 관심 있는 인프라의 AutoConfiguration과 `MethodReplay`를 보면 외부 호출이 기록값으로 바뀌는 경로를 따라갈 수 있다.
