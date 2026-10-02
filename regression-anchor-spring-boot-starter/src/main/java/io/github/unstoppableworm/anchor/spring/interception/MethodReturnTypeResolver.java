package io.github.unstoppableworm.anchor.spring.interception;

import org.springframework.core.ResolvableType;
import java.lang.reflect.Method;

/**
 * 공통 복원기가 기술별 반환 타입 해석에 의존할 때 사용하는 내부 포트다.
 * 대상 빈·메서드·실제 인자로부터 반환 타입을 결정하며 외부 I/O나 값 복원은 수행하지 않는다.
 * 기본 구현은 메서드 선언과 구현 클래스의 제네릭을 해석한다.
 * 기술별 자동 설정은 BoundaryPostProcessor를 구성할 때 자체 해석기를 주입할 수 있다.
 * 소비자의 전체 복원 확장점인 BoundaryReplayAdapter와 달리 반환 타입 결정만 담당한다.
 */
@FunctionalInterface
public interface MethodReturnTypeResolver {
    ResolvableType resolve(Object target, Method method, Object[] arguments);
}
