package com.databridge.app.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** The API's JSON settings: ISO dates, and unknown properties rejected rather than ignored. */
public final class Json {

    private Json() { }

    public static ObjectMapper mapper() {
        SimpleModule time = new SimpleModule("databridge-time");
        time.addSerializer(Instant.class, ToStringSerializer.instance);
        time.addSerializer(LocalDate.class, ToStringSerializer.instance);
        time.addDeserializer(LocalDate.class, new IsoDeserializer<>(LocalDate.class) {
            private static final long serialVersionUID = 1L;
            @Override LocalDate parse(String s) { return LocalDate.parse(s); }
        });
        time.addDeserializer(Instant.class, new IsoDeserializer<>(Instant.class) {
            private static final long serialVersionUID = 1L;
            @Override Instant parse(String s) { return Instant.parse(s); }
        });
        return new ObjectMapper()
                .registerModule(time)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    private abstract static class IsoDeserializer<T> extends StdDeserializer<T> {
        private static final long serialVersionUID = 1L;

        IsoDeserializer(Class<T> type) {
            super(type);
        }

        abstract T parse(String s);

        @Override
        public T deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String s = p.getValueAsString();
            if (s == null || s.isBlank()) return null;
            try {
                return parse(s.trim());
            } catch (DateTimeParseException e) {
                return ctx.reportInputMismatch(handledType(), "'%s' is not an ISO date (use 2027-01-31)", s);
            }
        }
    }
}
