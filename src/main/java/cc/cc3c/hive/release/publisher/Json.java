package cc.cc3c.hive.release.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static void write(Path file, Object value) throws IOException {
        Files.write(file, bytes(value));
    }

    static byte[] bytes(Object value) throws IOException {
        return (MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

}
