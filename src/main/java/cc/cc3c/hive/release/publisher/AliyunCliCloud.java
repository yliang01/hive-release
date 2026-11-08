package cc.cc3c.hive.release.publisher;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;

final class AliyunCliCloud implements Cloud {
    @FunctionalInterface
    interface Runner {
        byte[] run(String profile, Map<String, String> credentials, List<String> args) throws IOException;
    }

    private final ReleaseConfig config;
    private final Runner runner;
    private Map<String, String> credentials;

    AliyunCliCloud(ReleaseConfig config, AliyunCli cli) { this(config, cli::run); }
    AliyunCliCloud(ReleaseConfig config, Runner runner) { this.config = config; this.runner = runner; }

    @Override
    public void checkVersioning() throws IOException {
        byte[] result = run(List.of("ossutil", "api", "get-bucket-versioning", "--bucket", config.bucket(), "--output-format", "json", "--quiet"));
        JsonNode body;
        try (var parser = Json.MAPPER.getFactory().createParser(result)) {
            body = Json.MAPPER.readTree(parser);
        }
        if (body == null || !body.isObject()) throw new IOException("Cannot verify bucket versioning state.");
        JsonNode state = body.has("VersioningConfiguration") ? body.path("VersioningConfiguration") : body;
        if (!state.isObject()) throw new IOException("Cannot verify bucket versioning state.");
        JsonNode status = state.path("Status");
        if (!status.isMissingNode() && !status.isNull() && (!status.isTextual() || !status.asText().isEmpty()))
            throw new ReleaseException("Bucket versioning is enabled, suspended, or unrecognized. Operation stopped.");
    }

    @Override
    public List<CloudObject> list(String prefix) throws IOException {
        var objects = new ArrayList<CloudObject>();
        Set<String> seenTokens = new HashSet<>();
        String continuationToken = null;
        while (true) {
            var args = new ArrayList<>(List.of("ossutil", "api", "list-objects-v2", "--bucket", config.bucket(),
                    "--prefix", prefix, "--max-keys", "1000", "--output-format", "json", "--quiet"));
            if (continuationToken != null) {
                args.add("--continuation-token");
                args.add(continuationToken);
            }
            JsonNode body = readJson(run(args), "Invalid OSS object listing response.");
            JsonNode contents = body.path("Contents");
            if (contents.isArray()) {
                for (JsonNode item : contents) {
                    String key = item.path("Key").asText("");
                    if (key.isBlank()) continue;
                    String modified = item.path("LastModified").asText("");
                    Instant lastModified = modified.isBlank() ? null : Instant.parse(modified);
                    objects.add(new CloudObject(key, lastModified));
                }
            }
            if (!body.path("IsTruncated").asBoolean(false)) return List.copyOf(objects);
            continuationToken = body.path("NextContinuationToken").asText("");
            if (continuationToken.isBlank() || !seenTokens.add(continuationToken))
                throw new IOException("OSS object listing pagination did not advance.");
        }
    }

    @Override
    public byte[] download(String key) throws IOException {
        return run(List.of("ossutil", "cat", object(key), "--quiet"));
    }

    @Override
    public void put(String key, Path source) throws IOException {
        run(List.of("ossutil", "api", "put-object", "--bucket", config.bucket(), "--key", key,
                "--body", "file://" + source.toAbsolutePath(), "--forbid-overwrite", "--cache-control", "no-store", "--quiet"));
    }

    @Override
    public void delete(String key) throws IOException {
        run(List.of("ossutil", "api", "delete-object", "--bucket", config.bucket(), "--key", key, "--quiet"));
    }

    @Override
    public String sign(String key, int seconds) throws IOException {
        return sign(key, seconds, null);
    }

    @Override
    public String sign(String key, int seconds, String responseContentDisposition) throws IOException {
        var args = new ArrayList<>(List.of("oss", "sign", object(key), "--timeout", Integer.toString(seconds)));
        if (responseContentDisposition != null && !responseContentDisposition.isBlank()) {
            args.add("--query-param");
            args.add("response-content-disposition:" + responseContentDisposition);
        }
        String output = new String(run(args), StandardCharsets.UTF_8);
        var urls = output.lines().map(String::strip).filter(line -> line.startsWith("https://")).toList();
        if (urls.size() != 1)
            throw new IOException("Invalid signed URL returned by Alibaba Cloud CLI.");
        return urls.getFirst();
    }

    private String object(String key) { return "oss://" + config.bucket() + "/" + key; }

    private JsonNode readJson(byte[] response, String message) throws IOException {
        try (var parser = Json.MAPPER.getFactory().createParser(response)) {
            JsonNode body = Json.MAPPER.readTree(parser);
            if (body == null || !body.isObject()) throw new IOException(message);
            return body;
        } catch (IOException e) {
            throw new IOException(message);
        }
    }

    private byte[] run(List<String> args) throws IOException {
        if (credentials == null) {
            byte[] response = runner.run(config.profile(), Map.of(), List.of("sts", "AssumeRole",
                    "--RoleArn", config.roleArn(), "--RoleSessionName", config.sessionName(),
                    "--DurationSeconds", Long.toString(config.durationSeconds()), "--region", config.region(),
                    "--profile", config.profile()));
            JsonNode result;
            try {
                result = Json.MAPPER.readTree(response);
            } catch (IOException e) {
                throw new IOException("Invalid STS response; details suppressed.");
            }
            JsonNode values = result.required("Credentials");
            credentials = Map.of("ALIBABA_CLOUD_ACCESS_KEY_ID", values.required("AccessKeyId").textValue(),
                    "ALIBABA_CLOUD_ACCESS_KEY_SECRET", values.required("AccessKeySecret").textValue(),
                    "ALIBABA_CLOUD_SECURITY_TOKEN", values.required("SecurityToken").textValue(),
                    "ALIBABA_CLOUD_REGION_ID", config.region());
        }
        var command = new ArrayList<>(args);
        command.add("--region");
        command.add(config.region());
        return runner.run(config.profile(), credentials, command);
    }

    @Override
    public void close() { credentials = null; }
}
