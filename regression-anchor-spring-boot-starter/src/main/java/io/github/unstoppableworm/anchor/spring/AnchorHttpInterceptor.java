package io.github.unstoppableworm.anchor.spring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.unstoppableworm.anchor.RegressionAnchor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
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

/** Captures wire requests/responses and returns a fresh stream to the client decoder. */
public final class AnchorHttpInterceptor implements ClientHttpRequestInterceptor {
    private final RegressionAnchor anchor;
    private final AnchorProperties properties;

    public AnchorHttpInterceptor(RegressionAnchor anchor, AnchorProperties properties) {
        this.anchor = anchor; this.properties = properties;
    }

    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (!anchor.isActive()) return execution.execute(request, body);
        if (body.length > properties.getMaxHttpBodyBytes()) throw anchor.unsupported("HTTP request body exceeds anchor max-http-body-bytes");
        Request captured = new Request(request.getMethod().name(), request.getURI().toString(),
                headers(request.getHeaders(), properties.getRequestHeaders()), body(body, request.getHeaders()));
        try {
            Reply reply = anchor.capture("HTTP", request.getMethod().name() + " " + request.getURI(), new Object[]{captured}, () -> {
                ClientHttpResponse response = execution.execute(request, body);
                try {
                    byte[] bytes = response.getBody().readNBytes(properties.getMaxHttpBodyBytes() + 1);
                    if (bytes.length > properties.getMaxHttpBodyBytes()) throw anchor.unsupported("HTTP response body exceeds anchor max-http-body-bytes");
                    return new Reply(response.getStatusCode().value(), headers(response.getHeaders(), properties.getResponseHeaders()),
                            body(bytes, response.getHeaders()), new BufferedResponse(response, bytes));
                } catch (Throwable error) { response.close(); throw error; }
            });
            return reply.response();
        } catch (IOException | RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IOException("Cannot record HTTP exchange", e); }
    }

    private Map<String, List<String>> headers(HttpHeaders source, Set<String> included) {
        Map<String, List<String>> result = new TreeMap<>();
        for (String header : included) {
            List<String> values = source.get(header);
            if (values != null) result.put(header.toLowerCase(Locale.ROOT), List.copyOf(values));
        }
        return result;
    }

    private JsonNode body(byte[] bytes, HttpHeaders headers) throws IOException {
        var type = headers.getContentType();
        if (bytes.length > 0 && type != null && (type.getSubtype().equals("json") || type.getSubtype().endsWith("+json"))) {
            try { return anchor.codec().mapper().readTree(bytes); }
            catch (IOException ignored) { /* Invalid JSON is still observable as a raw body. */ }
        }
        if (type != null && (type.getType().equals("text") || type.getSubtype().contains("xml") || type.getSubtype().contains("form"))) {
            return anchor.codec().freeze(Map.of("text", new String(bytes, type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset())));
        }
        return anchor.codec().freeze(Map.of("base64", Base64.getEncoder().encodeToString(bytes)));
    }

    public record Request(String method, String uri, Map<String, List<String>> headers, JsonNode body) {}
    public record Reply(int status, Map<String, List<String>> headers, JsonNode body, @JsonIgnore ClientHttpResponse response) {}

    private record BufferedResponse(ClientHttpResponse delegate, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public void close() { delegate.close(); }
    }
}
