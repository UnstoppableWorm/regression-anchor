package io.github.unstoppableworm.anchor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class FileSnapshotStore implements SnapshotStore {
    private final Path baselines;
    private final Path reports;
    private final ObjectMapper mapper;

    public FileSnapshotStore(Path baselines, Path reports, SnapshotCodec codec) {
        this.baselines = baselines;
        this.reports = reports;
        this.mapper = codec.mapper();
    }

    private Path path(Path directory, String caseId, String suffix) {
        if (caseId == null || !caseId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("caseId must be 1-128 safe filename characters, starting with a letter or digit");
        }
        return directory.resolve(caseId + suffix);
    }

    @Override public boolean exists(String caseId) { return Files.exists(path(baselines, caseId, ".json")); }

    @Override public Snapshot load(String caseId) throws IOException {
        Snapshot snapshot = mapper.readValue(path(baselines, caseId, ".json").toFile(), Snapshot.class);
        if (snapshot.formatVersion() != Snapshot.FORMAT_VERSION || !caseId.equals(snapshot.caseId())) {
            throw new IOException("Unsupported or inconsistent snapshot: " + caseId);
        }
        return snapshot;
    }

    @Override public void create(Snapshot snapshot) throws IOException {
        Path destination = path(baselines, snapshot.caseId(), ".json");
        Files.createDirectories(baselines);
        Path temporary = Files.createTempFile(baselines, ".anchor-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            // Hard-link creation is atomic and fails if the destination already exists.
            Files.createLink(destination, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override public void report(Snapshot actual, List<Difference> differences) throws IOException {
        Files.createDirectories(reports);
        replace(path(reports, actual.caseId(), ".actual.json"), actual);
        replace(path(reports, actual.caseId(), ".diff.json"), differences);
    }

    private void replace(Path destination, Object value) throws IOException {
        Path temporary = Files.createTempFile(reports, ".anchor-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
