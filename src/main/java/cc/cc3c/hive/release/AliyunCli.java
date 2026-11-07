package cc.cc3c.hive.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Runs the CLI without a shell and never exposes raw process output in errors. */
final class AliyunCli {
    byte[] run(String profile, Map<String, String> credentials, List<String> args) throws IOException {
        var command = new ArrayList<>(List.of("aliyun"));
        command.addAll(args);
        var builder = new ProcessBuilder(command);
        var env = builder.environment();
        if (credentials.isEmpty()) env.put("ALIBABA_CLOUD_PROFILE", profile);
        else {
            env.put("ALIBABA_CLOUD_IGNORE_PROFILE", "TRUE");
            env.putAll(credentials);
        }
        Process process = builder.start();
        process.getOutputStream().close();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var error = executor.submit(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            try {
                byte[] output = process.getInputStream().readAllBytes();
                int code = process.waitFor();
                String stderr = error.get();
                if (code != 0) throw new IOException(errorMessage(code, stderr));
                return output;
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Alibaba Cloud CLI was interrupted.", e);
            } catch (ExecutionException e) {
                process.destroyForcibly();
                throw new IOException("Cannot read Alibaba Cloud CLI error output.", e);
            }
        }
    }

    static String errorMessage(int exitCode, String stderr) {
        var message = new StringBuilder("Alibaba Cloud CLI failed (exit code ").append(exitCode).append(")");
        appendField(message, stderr, "HTTP status", "(?i)Http\\s+Status\\s+Code:\\s*(\\d{3})");
        appendField(message, stderr, "error code", "(?i)Error\\s+Code:\\s*([A-Za-z0-9._-]+)");
        appendField(message, stderr, "request ID", "(?i)Request\\s+Id:\\s*([A-Za-z0-9_-]+)");
        return message.toString();
    }

    private static void appendField(StringBuilder message, String output, String label, String pattern) {
        var match = Pattern.compile(pattern).matcher(output);
        if (match.find()) message.append("; ").append(label).append("=").append(match.group(1));
    }
}
