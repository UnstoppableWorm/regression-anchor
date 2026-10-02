package io.github.unstoppableworm.anchor.spring.http;

import io.github.unstoppableworm.anchor.execution.RegressionAnchor;
import io.github.unstoppableworm.anchor.snapshot.Snapshot;
import io.github.unstoppableworm.anchor.snapshot.SnapshotCodec;
import io.github.unstoppableworm.anchor.spring.autoconfigure.AnchorProperties;
import io.github.unstoppableworm.anchor.spring.testsupport.TestSnapshotStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnchorHttpInterceptorTest {
    SnapshotCodec codec = new SnapshotCodec();

    @Test void recordsRestTemplateAndPreservesErrorStatusAndBody() throws Exception {
        var store = new TestSnapshotStore();
        var anchor = new RegressionAnchor(codec, store);
        var client = new RestTemplate();
        client.getInterceptors().add(new AnchorHttpInterceptor(anchor, new AnchorProperties()));
        var server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("https://example.test/api")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid\"}"));
        assertThatThrownBy(() -> anchor.record("error", "https://example.test/api", uri -> client.getForObject(uri, String.class)))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        Snapshot recorded = anchor.loadSnapshot("error");
        assertThat(recorded.calls()).hasSize(1);
        assertThat(recorded.calls().getFirst().outcome().value().path("status").asInt()).isEqualTo(400);
        assertThat(recorded.calls().getFirst().outcome().value().at("/body/error").asText()).isEqualTo("invalid");
        // No second mock response is registered: replay must never execute the transport.
        anchor.replay("error", String.class, uri -> client.getForObject(uri, String.class)).assertMatches();
        server.verify();
    }

    @Test void enforcesBodyLimitWithoutSavingPartialBaseline() {
        var store = new TestSnapshotStore();
        var anchor = new RegressionAnchor(codec, store);
        var properties = new AnchorProperties();
        properties.setMaxHttpBodyBytes(4);
        var client = new RestTemplate();
        client.getInterceptors().add(new AnchorHttpInterceptor(anchor, properties));
        var server = MockRestServiceServer.bindTo(client).build();
        var closed = new AtomicInteger();
        var response = new MockClientHttpResponse("too large".getBytes(StandardCharsets.UTF_8), HttpStatus.OK) {
            @Override public void close() { closed.incrementAndGet(); super.close(); }
        };
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        server.expect(requestTo("https://example.test/api")).andRespond(request -> response);
        assertThatThrownBy(() -> anchor.record("limit", "https://example.test/api", uri -> {
            try { return client.getForObject(uri, String.class); } catch (IllegalStateException e) { return "ignored"; }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-http-body-bytes");
        assertThat(store.exists("limit")).isFalse();
        assertThat(closed).hasValue(1);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/problem+json", "text/plain;charset=UTF-8", "application/octet-stream"})
    void buffersTheBodyAndTransfersCloseOwnershipWithoutCallingTransportDuringReplay(String contentType) throws Exception {
        var anchor = new RegressionAnchor(codec, new TestSnapshotStore());
        var properties = new AnchorProperties();
        properties.setResponseHeaders(Set.of("content-type", "x-trace"));
        var interceptor = new AnchorHttpInterceptor(anchor, properties);
        byte[] bytes = contentType.startsWith("application/problem") ? "{\"message\":\"한글\"}".getBytes(StandardCharsets.UTF_8)
                : contentType.startsWith("text") ? "한글".getBytes(StandardCharsets.UTF_8) : new byte[]{0, 1, (byte) 255};
        var closed = new AtomicInteger();
        var calls = new AtomicInteger();
        var response = new MockClientHttpResponse(bytes, HttpStatus.CREATED) {
            @Override public void close() { closed.incrementAndGet(); super.close(); }
        };
        response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        response.getHeaders().set("x-trace", "trace-1");
        ClientHttpRequestExecution transport = (request, body) -> {
            if (calls.incrementAndGet() != 1) throw new AssertionError("Transport executed during replay");
            return response;
        };
        RegressionAnchor.Trigger<String, String> work = uri -> {
            var request = new MockClientHttpRequest(HttpMethod.GET, URI.create(uri));
            try (var reply = interceptor.intercept(request, new byte[0], transport)) {
                if (!anchor.isReplaying()) assertThat(closed).hasValue(0);
                assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(reply.getHeaders().getFirst("x-trace")).isEqualTo("trace-1");
                assertThat(reply.getBody().readAllBytes()).isEqualTo(bytes);
                return Base64.getEncoder().encodeToString(reply.getBody().readAllBytes());
            }
        };
        anchor.record("body", "https://example.test/api", work);
        assertThat(closed).hasValue(1);
        anchor.replay("body", String.class, work).assertMatches();
        assertThat(calls).hasValue(1);
        assertThat(closed).hasValue(1);
    }

    @Test void closesTransportResponseWhenReadingFailsAndReplaysTheFailureWithoutIo() throws Exception {
        var anchor = new RegressionAnchor(codec, new TestSnapshotStore());
        var interceptor = new AnchorHttpInterceptor(anchor, new AnchorProperties());
        var closed = new AtomicInteger();
        var calls = new AtomicInteger();
        var response = new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
            @Override public InputStream getBody() {
                return new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("response read failed"); }
                };
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        RegressionAnchor.Trigger<String, Integer> work = uri -> {
            var request = new MockClientHttpRequest(HttpMethod.GET, URI.create(uri));
            try (var reply = interceptor.intercept(request, new byte[0], (ignored, body) -> {
                if (calls.incrementAndGet() != 1) throw new AssertionError("Transport executed during replay");
                return response;
            })) { return reply.getStatusCode().value(); }
        };
        assertThatThrownBy(() -> anchor.record("read-failure", "https://example.test/api", work))
                .isInstanceOf(IOException.class).hasMessage("response read failed");
        assertThat(closed).hasValue(1);
        anchor.replay("read-failure", String.class, work).assertMatches();
        assertThat(calls).hasValue(1);
        assertThat(closed).hasValue(1);
    }
}
