package io.github.unstoppableworm.anchor;

import com.fasterxml.jackson.databind.JsonNode;

/** JSON Pointer path; absent values are distinct from explicit JSON nulls. */
public record Difference(String path, JsonNode expected, JsonNode actual) {
    @com.fasterxml.jackson.annotation.JsonProperty
    public boolean expectedMissing() { return expected.isMissingNode(); }
    @com.fasterxml.jackson.annotation.JsonProperty
    public boolean actualMissing() { return actual.isMissingNode(); }
}
