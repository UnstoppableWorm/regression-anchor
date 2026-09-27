package io.github.unstoppableworm.anchor.spring;

import io.github.unstoppableworm.anchor.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnchorHttpInterceptorTest {
    @TempDir Path directory;
    SnapshotCodec codec = new SnapshotCodec();

    @Test void recordsRestTemplateAndPreservesErrorStatusAndBody() throws Exception {
        var store = new FileSnapshotStore(directory.resolve("baseline"), directory.resolve("reports"), codec);
        var anchor = new RegressionAnchor(codec, store);
        var client = new RestTemplate();
        client.getInterceptors().add(new AnchorHttpInterceptor(anchor, new AnchorProperties()));
        var server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("https://example.test/api")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid\"}"));
        assertThatThrownBy(() -> anchor.record("error", "https://example.test/api", uri -> client.getForObject(uri, String.class)))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        Snapshot recorded = store.load("error");
        assertThat(recorded.calls()).hasSize(1);
        assertThat(recorded.calls().getFirst().outcome().value().path("status").asInt()).isEqualTo(400);
        assertThat(recorded.calls().getFirst().outcome().value().at("/body/error").asText()).isEqualTo("invalid");
        server.verify();
    }

    @Test void enforcesBodyLimitWithoutSavingPartialBaseline() {
        var store = new FileSnapshotStore(directory.resolve("baseline"), directory.resolve("reports"), codec);
        var anchor = new RegressionAnchor(codec, store);
        var properties = new AnchorProperties();
        properties.setMaxHttpBodyBytes(4);
        var client = new RestTemplate();
        client.getInterceptors().add(new AnchorHttpInterceptor(anchor, properties));
        var server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("https://example.test/api")).andRespond(withSuccess("too large", MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> anchor.record("limit", "https://example.test/api", uri -> {
            try { return client.getForObject(uri, String.class); } catch (IllegalStateException e) { return "ignored"; }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-http-body-bytes");
        assertThat(store.exists("limit")).isFalse();
    }
}
