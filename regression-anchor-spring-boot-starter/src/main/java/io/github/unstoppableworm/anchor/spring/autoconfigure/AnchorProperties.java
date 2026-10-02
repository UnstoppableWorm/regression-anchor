package io.github.unstoppableworm.anchor.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code regression-anchor} 설정 중 비교 제외 경로와 HTTP 캡처 옵션을 바인딩한다.
 * 요청·응답에서 보존할 헤더와 본문 최대 크기를 정하고 본문 크기 제한을 검증한다.
 * 실행 모드는 프로필 또는 run 인자로 선택하며, 저장 위치는 SnapshotStore 구현이 결정한다.
 */
@ConfigurationProperties("regression-anchor")
public class AnchorProperties {
    private Set<String> ignoredPaths = new LinkedHashSet<>();
    private Set<String> requestHeaders = Set.of("content-type", "accept");
    private Set<String> responseHeaders = Set.of("content-type");
    private int maxHttpBodyBytes = 1_048_576;

    public Set<String> getIgnoredPaths() { return ignoredPaths; }
    public void setIgnoredPaths(Set<String> value) { ignoredPaths = value; }
    public Set<String> getRequestHeaders() { return requestHeaders; }
    public void setRequestHeaders(Set<String> value) { requestHeaders = value; }
    public Set<String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Set<String> value) { responseHeaders = value; }
    public int getMaxHttpBodyBytes() { return maxHttpBodyBytes; }
    public void setMaxHttpBodyBytes(int value) {
        if (value < 1 || value == Integer.MAX_VALUE) throw new IllegalArgumentException("max-http-body-bytes must be between 1 and 2147483646");
        maxHttpBodyBytes = value;
    }
}
