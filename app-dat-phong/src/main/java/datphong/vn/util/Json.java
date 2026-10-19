package datphong.vn.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được JSON", e);
        }
    }

    public static <T> T read(String s, Class<T> c) {
        try {
            return MAPPER.readValue(s, c);
        } catch (Exception e) {
            throw new IllegalStateException("JSON không hợp lệ", e);
        }
    }

    public static Map<String, Object> readMap(String s) {
        try {
            return MAPPER.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("JSON không hợp lệ", e);
        }
    }
}
