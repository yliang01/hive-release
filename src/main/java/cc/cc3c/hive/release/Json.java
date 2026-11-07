package cc.cc3c.hive.release;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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

    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

}
