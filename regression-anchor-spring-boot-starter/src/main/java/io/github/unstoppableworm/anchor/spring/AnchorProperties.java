package io.github.unstoppableworm.anchor.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties("regression-anchor")
public class AnchorProperties {
    private Path baselineDirectory = Path.of("regression-anchor-data/baselines");
    private Path reportDirectory = Path.of("target/regression-anchor-reports");
    private Set<String> ignoredPaths = new LinkedHashSet<>();
    private Set<String> requestHeaders = Set.of("content-type", "accept");
    private Set<String> responseHeaders = Set.of("content-type");
    private int maxHttpBodyBytes = 1_048_576;

    public Path getBaselineDirectory() { return baselineDirectory; }
    public void setBaselineDirectory(Path value) { baselineDirectory = value; }
    public Path getReportDirectory() { return reportDirectory; }
    public void setReportDirectory(Path value) { reportDirectory = value; }
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
