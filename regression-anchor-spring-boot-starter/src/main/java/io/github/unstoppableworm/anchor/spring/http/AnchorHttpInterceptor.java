package io.github.unstoppableworm.anchor.spring.http;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.replay.ReplayValues;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorProperties;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * RestClient·RestTemplate의 HTTP 요청과 응답을 기록하거나 재생하는 인터셉터다.
 * 메서드·전체 URI·선택한 헤더·본문을 저장하며 JSON·텍스트·바이너리 본문을 구분한다.
 * record에서는 실제 응답을 크기 제한 안에서 읽고, 클라이언트가 다시 읽을 수 있게 버퍼링한다.
 * replay에서는 네트워크 실행 대신 저장된 상태 코드·헤더·본문으로 응답을 만든다.
 * 기록 중 응답 읽기에 실패하면 즉시 닫고, 성공하면 버퍼 응답의 close를 통해 호출자가 원래 응답을 닫는다.
 * HTTP 클라이언트에 이 인터셉터가 연결된 호출만 담당하며, 저장 위치는 결정하지 않는다.
 */
public final class AnchorHttpInterceptor implements ClientHttpRequestInterceptor {
    private final RegressionAnchor anchor;
    private final AnchorProperties properties;

    public AnchorHttpInterceptor(RegressionAnchor anchor, AnchorProperties properties) {
        this.anchor = anchor; this.properties = properties;
    }

    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (!anchor.isActive()) {
            anchor.requireReplayScope();
            return execution.execute(request, body);
        }
        if (body.length > properties.getMaxHttpBodyBytes()) throw anchor.unsupported("HTTP request body exceeds anchor max-http-body-bytes");
        Request captured = new Request(request.getMethod().name(), request.getURI().toString(),
                selectedHeaders(request.getHeaders(), properties.getRequestHeaders()), snapshotBody(body, request.getHeaders()));
        try {
            Reply reply = anchor.captureBoundary("HTTP", request.getMethod().name() + " " + request.getURI(), new Object[]{captured},
                    this::restoreReply, () -> recordReply(request, body, execution));
            return reply.response();
        } catch (IOException | RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IOException("Cannot record HTTP exchange", e); }
    }

    private Reply restoreReply(Snapshot.Call recorded, Object[] arguments, SnapshotCodec codec) throws Exception {
        ReplayValues.throwIfFailed(recorded.outcome().failure());
        Reply saved = codec.mapper().convertValue(recorded.outcome().value(), Reply.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        saved.headers().forEach(responseHeaders::put);
        byte[] bytes = restoreBody(saved.body(), responseHeaders);
        return new Reply(saved.status(), saved.headers(), saved.body(),
                new ReplayedResponse(saved.status(), responseHeaders, bytes));
    }

    private Reply recordReply(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        try {
            byte[] bytes = response.getBody().readNBytes(properties.getMaxHttpBodyBytes() + 1);
            if (bytes.length > properties.getMaxHttpBodyBytes())
                throw anchor.unsupported("HTTP response body exceeds anchor max-http-body-bytes");
            // On success, the caller owns the buffered response and closes the original connection.
            return new Reply(response.getStatusCode().value(), selectedHeaders(response.getHeaders(), properties.getResponseHeaders()),
                    snapshotBody(bytes, response.getHeaders()), new BufferedResponse(response, bytes));
        } catch (Throwable error) {
            response.close();
            throw error;
        }
    }

    private byte[] restoreBody(JsonNode body, HttpHeaders headers) throws IOException {
        var type = headers.getContentType();
        if (isJson(type)) {
            if (body.isObject() && body.size() == 1 && body.has("base64"))
                throw anchor.unsupported("Ambiguous recorded HTTP JSON/base64 body; use a typed boundary for this response");
            return anchor.codec().mapper().writeValueAsBytes(body);
        }
        if (body.has("text")) return body.path("text").asText().getBytes(type == null || type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset());
        if (body.has("base64")) return Base64.getDecoder().decode(body.path("base64").asText());
        throw anchor.unsupported("HTTP replay requires the recorded content-type header for a JSON body");
    }

    /**
     * 기록된 HTTP 상태 코드·헤더·본문으로 만들어 네트워크 연결 없이 반환하는 응답이다.
     * 본문을 요청할 때마다 새 메모리 스트림을 제공하며 닫을 실제 연결은 없다.
     */
    private record ReplayedResponse(int status, HttpHeaders headers, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(status); }
        @Override public String getStatusText() { var known = HttpStatus.resolve(status); return known == null ? "" : known.getReasonPhrase(); }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public void close() {}
    }

    private Map<String, List<String>> selectedHeaders(HttpHeaders source, Set<String> included) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String header : included) {
            List<String> values = source.get(header);
            if (values != null) result.put(header.toLowerCase(Locale.ROOT), List.copyOf(values));
        }
        return result;
    }

    private JsonNode snapshotBody(byte[] bytes, HttpHeaders headers) throws IOException {
        var type = headers.getContentType();
        if (bytes.length > 0 && isJson(type)) {
            try { return anchor.codec().mapper().readTree(bytes); }
            catch (IOException ignored) { /* Invalid JSON is still observable as a raw body. */ }
        }
        if (type != null && (type.getType().equals("text") || type.getSubtype().contains("xml") || type.getSubtype().contains("form"))) {
            return anchor.codec().freeze(Map.of("text", new String(bytes, type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset())));
        }
        return anchor.codec().freeze(Map.of("base64", Base64.getEncoder().encodeToString(bytes)));
    }

    private static boolean isJson(MediaType type) {
        return type != null && (type.getSubtype().equals("json") || type.getSubtype().endsWith("+json"));
    }

    /**
     * HTTP 요청을 재생 입력과 비교하기 위한 저장 모델이다.
     * HTTP 메서드, 전체 URI, 설정에서 선택한 헤더, 정규화한 JSON·텍스트·바이너리 본문을 담는다.
     */
    public record Request(String method, String uri, Map<String, List<String>> headers, JsonNode body) {}
    /**
     * HTTP 응답의 기록값과 클라이언트에 돌려줄 런타임 응답을 묶는다.
     * 상태 코드·선택한 헤더·본문만 JSON에 저장하고 response 객체는 JsonIgnore로 제외한다.
     * 실제 실행에서는 버퍼 응답을, replay에서는 합성한 응답을 전달한다.
     */
    public record Reply(int status, Map<String, List<String>> headers, JsonNode body, @JsonIgnore ClientHttpResponse response) {}

    /**
     * 기록 과정에서 이미 읽은 실제 HTTP 본문을 클라이언트가 다시 읽을 수 있도록 감싼 응답이다.
     * 본문은 메모리 버퍼에서 제공하고 상태·헤더 조회와 close는 원래 응답에 위임한다.
     */
    private record BufferedResponse(ClientHttpResponse delegate, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public void close() { delegate.close(); }
    }
}
