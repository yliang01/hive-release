package cc.cc3c.hive.release;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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
            throw new ReleaseException("Bucket versioning is enabled, suspended, or unrecognized. Publication stopped.");
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
    public String sign(String key, int seconds) throws IOException {
        String output = new String(run(List.of("oss", "sign", object(key), "--timeout", Integer.toString(seconds))), StandardCharsets.UTF_8);
        var urls = output.lines().map(String::strip).filter(line -> line.startsWith("https://")).toList();
        if (urls.size() != 1)
            throw new IOException("Invalid signed URL returned by Alibaba Cloud CLI.");
        return urls.getFirst();
    }

    private String object(String key) { return "oss://" + config.bucket() + "/" + key; }
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
