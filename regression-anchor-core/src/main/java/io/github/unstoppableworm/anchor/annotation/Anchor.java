package io.github.unstoppableworm.anchor.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 붙이는 위치에 따라 JSON 필드 키, 외부 의존성 캡처 범위와 안정적인 호출 식별자를 지정한다.
 * 필드·레코드 컴포넌트·생성자 파라미터에는 비어 있지 않은 value로 JSON 키를 고정한다.
 * 접근자의 JSON 키도 해당 필드의 지정값을 따르며 일반 HTTP JSON과 DB 매핑은 바꾸지 않는다.
 * 메서드는 해당 public 인스턴스 메서드를, 클래스·인터페이스는 상속을 포함한 public 메서드 전체를 지정한다.
 * 클래스·메서드의 비어 있지 않은 value는 각각 빈 이름·메서드명 대신 사용하는 호출 식별자다.
 * 외부 메서드 파라미터의 value는 해당 위치의 타입 이름을 대신하며 내부 DTO 필드 이름은 바꾸지 않는다.
 * 지정하지 않은 식별 부분은 기존 Java 이름을 쓰고 인자의 순서·값은 계속 비교한다.
 * Object 메서드와 static 메서드는 클래스 지정 범위에서 제외한다.
 * record에서는 실제 본문을 실행하고 replay에서는 반환값·예외·인자 변경을 복원한다.
 * 일반 gateway와 기존 무값 지정은 하위 계측을 하나로 묶는다. 이름만 지정한 자동 인식 경계는 원래 종류·복원기를 유지한다.
 * 일반 업무 서비스에는 붙이지 않는다.
 * Spring 프록시를 통과하지 않는 내부 직접 호출과 직접 생성한 객체에는 캡처가 적용되지 않는다.
 * 가로챌 수 없는 명시적 지정은 시작 시 거부한다. Spring 자동 계측은 regression 프로필에서만 활성화된다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Anchor {
    /** 필드·생성자 파라미터의 JSON 키 또는 클래스·메서드·외부 메서드 파라미터의 고정 식별자. 클래스·메서드는 무값 캡처도 지원한다. */
    String value() default "";
}
