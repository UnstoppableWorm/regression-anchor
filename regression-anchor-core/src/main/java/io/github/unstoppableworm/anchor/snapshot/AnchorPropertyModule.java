package io.github.unstoppableworm.anchor.snapshot;

import io.github.unstoppableworm.anchor.annotation.Anchor;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Executable;

/**
 * {@link Anchor}를 스냅샷 전용 Jackson 이름 규칙으로 연결하는 내부 모듈이다.
 * 직렬화와 역직렬화 양쪽에 동일한 앵커 이름을 적용한다.
 * Jackson이 같은 이름의 프로퍼티를 합치기 전에 빈 값·중복 키·필드와 생성자 파라미터 간 충돌을 검사한다.
 * 일반 애플리케이션의 ObjectMapper 대신 {@link SnapshotCodec}에 등록된다.
 */
final class AnchorPropertyModule extends SimpleModule {
    AnchorPropertyModule() {
        super("regression-anchor-fields");
        setSerializerModifier(new BeanSerializerModifier() {
            @Override public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                    BeanDescription bean, List<BeanPropertyWriter> properties) {
                // Inspect original properties before anchor renaming can merge two fields.
                var original = config.with(validationIntrospector(config.getAnnotationIntrospector()));
                validate(original.introspect(bean.getType()).findProperties(), true, bean.getBeanClass());
                return properties;
            }
        });
        setDeserializerModifier(new BeanDeserializerModifier() {
            @Override public List<BeanPropertyDefinition> updateProperties(DeserializationConfig config,
                    BeanDescription bean, List<BeanPropertyDefinition> properties) {
                var original = config.with(validationIntrospector(config.getAnnotationIntrospector()));
                validate(original.introspect(bean.getType()).findProperties(), false, bean.getBeanClass());
                return properties;
            }
        });
    }

    @Override public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.insertAnnotationIntrospector(new AnchorNames(true));
    }

    private static AnnotationIntrospector validationIntrospector(AnnotationIntrospector introspector) {
        AnnotationIntrospector result = null;
        for (AnnotationIntrospector item : introspector.allIntrospectors()) {
            if (item instanceof AnchorNames) item = new AnchorNames(false);
            result = result == null ? item : AnnotationIntrospector.pair(result, item);
        }
        return result;
    }

    private static void validate(List<BeanPropertyDefinition> properties, boolean serialization, Class<?> type) {
        Map<String, String> owners = new HashMap<>();
        for (BeanPropertyDefinition property : properties) {
            if (serialization ? !property.couldSerialize() : !property.couldDeserialize()) continue;
            String anchor = null;
            for (AnnotatedMember member : new AnnotatedMember[]{property.getField(), property.getConstructorParameter()}) {
                if (member == null) continue;
                Anchor annotation = propertyAnchor(member);
                if (annotation == null) continue;
                String value = value(annotation);
                if (anchor != null && !anchor.equals(value)) throw new IllegalArgumentException(
                        "Conflicting @Anchor values for " + type.getName() + "." + property.getInternalName());
                anchor = value;
            }
            String key = anchor == null ? property.getName() : anchor;
            String previous = owners.putIfAbsent(key, property.getInternalName());
            if (previous != null) throw new IllegalArgumentException("Duplicate snapshot key '" + key
                    + "' in " + type.getName() + " (" + previous + ", " + property.getInternalName() + ")");
        }
    }

    private static String value(Anchor annotation) {
        if (annotation.value().isBlank()) throw new IllegalArgumentException("@Anchor value must not be blank");
        return annotation.value();
    }

    /**
     * 필드·생성자 파라미터에서 Anchor를 찾아 Jackson 프로퍼티 이름으로 변환한다.
     * 접근자는 대응하는 필드와 상위 클래스의 필드만 확인하여 메서드 캡처 지정과 구분한다.
     * 검증 단계에서는 원래 이름을 유지하고, 실제 변환 단계에서는 앵커 이름을 적용한다.
     */
    private static final class AnchorNames extends NopAnnotationIntrospector {
        private final boolean stableNames;
        AnchorNames(boolean stableNames) { this.stableNames = stableNames; }
        @Override public PropertyName findNameForSerialization(Annotated member) { return name(member); }
        @Override public PropertyName findNameForDeserialization(Annotated member) { return name(member); }
        private PropertyName name(Annotated member) {
            Anchor anchor = propertyAnchor(member);
            // A field anchor belongs to the whole Java property, even when a getter,
            // setter or creator parameter has its own API-facing @JsonProperty name.
            if (anchor == null) anchor = fieldAnchor(member);
            if (anchor == null) return null;
            String key = value(anchor);
            // USE_DEFAULT keeps annotated private fields visible while discovering their original names.
            return stableNames ? PropertyName.construct(key) : PropertyName.USE_DEFAULT;
        }
    }

    private static Anchor propertyAnchor(Annotated member) {
        // Jackson merges accessor annotations into properties: use the actual declaration
        // so a method's capture marker never becomes an empty field key.
        if (member instanceof AnnotatedField field) return field.getAnnotated().getAnnotation(Anchor.class);
        if (member instanceof AnnotatedParameter parameter
                && parameter.getOwner().getAnnotated() instanceof java.lang.reflect.Constructor<?> constructor) {
            Anchor direct = constructor.getParameters()[parameter.getIndex()].getAnnotation(Anchor.class);
            Anchor field = fieldAnchor(parameter);
            if (direct != null && field != null && !value(direct).equals(value(field)))
                throw new IllegalArgumentException("Conflicting @Anchor values for " + constructor.getDeclaringClass().getName()
                        + "." + constructor.getParameters()[parameter.getIndex()].getName());
            return direct;
        }
        return null;
    }

    private static Anchor fieldAnchor(Annotated member) {
        String property = null;
        Class<?> owner = null;
        if (member instanceof AnnotatedMethod method) {
            owner = method.getDeclaringClass();
            String name = method.getName();
            if (owner.isRecord() && method.getParameterCount() == 0) property = name;
            else if (name.startsWith("get") && name.length() > 3 && method.getParameterCount() == 0) property = decapitalize(name.substring(3));
            else if (name.startsWith("is") && name.length() > 2 && method.getParameterCount() == 0) property = decapitalize(name.substring(2));
            else if (name.startsWith("set") && name.length() > 3 && method.getParameterCount() == 1) property = decapitalize(name.substring(3));
        } else if (member instanceof AnnotatedParameter parameter
                && parameter.getOwner().getAnnotated() instanceof Executable executable) {
            owner = parameter.getDeclaringClass();
            var javaParameter = executable.getParameters()[parameter.getIndex()];
            if (javaParameter.isNamePresent()) property = javaParameter.getName();
        }
        if (property == null) return null;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { return type.getDeclaredField(property).getAnnotation(Anchor.class); }
            catch (NoSuchFieldException ignored) { /* Try inherited fields. */ }
        }
        return null;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
