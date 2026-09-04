package com.company.agentgateway.interfaces.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenApiBundleController 契约测试（Round 10 OpenSpec GW-OAB-001 ~ 006）。
 */
class OpenApiBundleControllerTest {

    private final OpenApiBundleController controller = new OpenApiBundleController();

    @Test
    void downloadPythonReturnsZipBytesWithOctetStreamAndAttachmentHeader() {
        ResponseEntity<byte[]> resp = controller.download("python");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("agent-gateway-python-sdk.zip");
        assertThat(resp.getBody()).isNotNull();
        // ZIP magic: "PK\x03\x04"
        assertThat(new String(resp.getBody(), 0, 4)).isEqualTo("PK");
        assertThat(resp.getBody().length).isGreaterThan(500);
    }

    @Test
    void downloadTypescriptReturnsZipBytes() {
        ResponseEntity<byte[]> resp = controller.download("typescript");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("agent-gateway-typescript-sdk.zip");
        assertThat(resp.getBody()).isNotNull();
        assertThat(new String(resp.getBody(), 0, 4)).isEqualTo("PK");
    }

    @Test
    void downloadGoReturnsZipBytes() {
        ResponseEntity<byte[]> resp = controller.download("go");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("agent-gateway-go-sdk.zip");
        assertThat(resp.getBody()).isNotNull();
        assertThat(new String(resp.getBody(), 0, 4)).isEqualTo("PK");
    }

    @Test
    void langCaseInsensitive() {
        ResponseEntity<byte[]> resp = controller.download("PYTHON");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void unknownLangReturns400() {
        assertThatThrownBy(() -> controller.download("rust"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void missingLangReturns400() {
        // 模拟空字符串：Map.get("") 返回 null → 走未知 lang 分支 → 400
        assertThatThrownBy(() -> controller.download(""))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void langsReturnsAllThreeAvailability() {
        var resp = controller.langs();

        assertThat(resp).containsKeys("langs", "available");
        @SuppressWarnings("unchecked")
        var langs = (java.util.List<String>) resp.get("langs");
        assertThat(langs).containsExactly("python", "typescript", "go");

        @SuppressWarnings("unchecked")
        var avail = (java.util.Map<String, Boolean>) resp.get("available");
        assertThat(avail).containsEntry("python", true)
                .containsEntry("typescript", true)
                .containsEntry("go", true);
    }
}
