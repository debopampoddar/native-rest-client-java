package io.declarative.http.api.converters;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class ConvertersTest {

    record Foo(String name) {}

    @Test
    void stringConverter_canConvertOnlyString() {
        StringConverter converter = new StringConverter();
        ObjectMapper mapper = new ObjectMapper();
        JavaType stringType = mapper.constructType(String.class);
        JavaType intType = mapper.constructType(Integer.class);

        assertThat(converter.canConvert(stringType)).isTrue();
        assertThat(converter.canConvert(intType)).isFalse();
    }

    @Test
    void stringConverter_readsUtf8Body() throws Exception {
        StringConverter converter = new StringConverter();
        String body = "héllo\nworld";
        InputStream is = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));

        Object result = converter.convert(is, new ObjectMapper().constructType(String.class));

        assertThat(result).isInstanceOf(String.class);
        assertThat(result).isEqualTo(body);
    }

    @Test
    void jacksonConverter_canConvertAllButStringAndInputStream() {
        ObjectMapper mapper = new ObjectMapper();
        JacksonConverter converter = new JacksonConverter(mapper);

        JavaType stringType = mapper.constructType(String.class);
        JavaType streamType = mapper.constructType(InputStream.class);
        JavaType fooType = mapper.constructType(Foo.class);
        JavaType intType = mapper.constructType(Integer.class);

        assertThat(converter.canConvert(stringType)).isFalse();
        assertThat(converter.canConvert(streamType)).isFalse();
        assertThat(converter.canConvert(fooType)).isTrue();
        assertThat(converter.canConvert(intType)).isTrue();
    }

    @Test
    void jacksonConverter_deserialisesJsonToPojo() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JacksonConverter converter = new JacksonConverter(mapper);

        String json = "{\"name\":\"Alice\"}";
        InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        JavaType fooType = mapper.constructType(Foo.class);

        Object result = converter.convert(is, fooType);

        assertThat(result).isInstanceOf(Foo.class);
        assertThat(((Foo) result).name()).isEqualTo("Alice");
    }
}
