package com.stockflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Customization templates saved the way the back office saves them: multipart, the template's JSON in part
 * {@code template} and its {@code .glb} in part {@code file}, with every print area mapped to its {@code print:<code>}
 * mesh. A template is created only with its file, so every test that needs one goes through here.
 *
 * <p>The file is the smallest glTF 2.0 binary there is: the 12-byte header and one JSON chunk. The server reads only
 * the signature and version ({@code ContentTypePolicy}); upload inspection is a mock in the tests that save templates.
 * </p>
 */
public final class TemplateModelFiles {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TemplateModelFiles() {
    }

    /** A glTF 2.0 binary holding one JSON chunk and nothing to draw. */
    public static byte[] glb() {
        byte[] chunk = "{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.UTF_8);
        int padded = (chunk.length + 3) / 4 * 4;
        ByteBuffer buffer = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'g', 'l', 'T', 'F'}).putInt(2).putInt(12 + 8 + padded);
        buffer.putInt(padded).put(new byte[] {'J', 'S', 'O', 'N'}).put(chunk);
        while (buffer.hasRemaining()) {
            buffer.put((byte) ' ');
        }
        return buffer.array();
    }

    /** {@link #glb} as the {@code file} part, typed as a browser sends it. */
    public static MockMultipartFile glbPart() {
        return new MockMultipartFile("file", "model.glb", MediaType.APPLICATION_OCTET_STREAM_VALUE, glb());
    }

    /**
     * {@code templateJson} as the {@code template} part, with {@code model3d.surfaces} mapping each of its print areas
     * to {@code print:<code>} unless it names surfaces itself.
     */
    public static MockMultipartFile templatePart(String templateJson) {
        return new MockMultipartFile("template", "", MediaType.APPLICATION_JSON_VALUE,
                withSurfaces(templateJson).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code POST base}: a template created with its {@code .glb}. */
    public static MockMultipartHttpServletRequestBuilder create(String base, String templateJson) {
        return multipart(base).file(templatePart(templateJson))
                .file(glbPart());
    }

    /** {@code PUT templateUrl} with a new {@code .glb}; {@code templateJson} carries the version. */
    public static MockMultipartHttpServletRequestBuilder updateWithFile(String templateUrl, String templateJson) {
        return multipart(HttpMethod.PUT, templateUrl)
                .file(templatePart(templateJson)).file(glbPart());
    }

    /** {@code templateJson} with {@code model3d.surfaces} filled in from its {@code printAreas}, if absent. */
    public static String withSurfaces(String templateJson) {
        try {
            ObjectNode template = (ObjectNode) JSON.readTree(templateJson);
            JsonNode model3d = template.get("model3d");
            if (model3d != null && model3d.has("surfaces")) {
                return templateJson;
            }
            ArrayNode surfaces = JSON.createArrayNode();
            for (JsonNode area : template.path("printAreas")) {
                String code = area.path("code").asText();
                surfaces.addObject().put("printAreaCode", code).put("meshName", "print:" + code);
            }
            template.putObject("model3d").set("surfaces", surfaces);
            return JSON.writeValueAsString(template);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
