package com.company.agentgateway.interfaces.openapi;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * OpenAPI 客户端产物下载（Round 10 OpenSpec：2026-09-01-round10-openapi-download）。
 *
 * <p>提供两个端点：
 * <ul>
 *   <li>{@code GET /v1/openapi/bundle?lang=python|typescript|go} —— 流式返回预生成的 zip</li>
 *   <li>{@code GET /v1/openapi/bundle/langs} —— 列出可用语种 + 资源存在性</li>
 * </ul>
 *
 * <p>产物以 classpath 资源形式打入 jar（{@code openapi-bundles/{lang}.zip}）。缺失时返回 503，
 * 不是 500 —— 优雅降级，便于运营知晓「该语言的 SDK 暂未发布」。
 */
@RestController
@RequestMapping("/v1/openapi/bundle")
public class OpenApiBundleController {

    static final Map<String, String> BUNDLE_PATHS = new LinkedHashMap<>(Map.of(
            "python", "openapi-bundles/python.zip",
            "typescript", "openapi-bundles/typescript.zip",
            "go", "openapi-bundles/go.zip"));

    @GetMapping
    public ResponseEntity<byte[]> download(@RequestParam String lang) {
        if (lang == null) {
            throw new ResponseStatusException(BAD_REQUEST, "lang is required");
        }
        String key = lang.trim().toLowerCase();
        String path = BUNDLE_PATHS.get(key);
        if (path == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "unsupported lang: " + lang + " (supported: python, typescript, go)");
        }
        Resource res = new ClassPathResource(path);
        if (!res.exists()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "{\"error\":\"bundle_unavailable\",\"lang\":\"" + key + "\"}");
        }
        byte[] bytes;
        try (InputStream in = res.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "failed to read bundle: " + e.getMessage());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "agent-gateway-" + key + "-sdk.zip");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    @GetMapping("/langs")
    public Map<String, Object> langs() {
        // 固定顺序：python → typescript → go（与 UI 按钮展示顺序一致）
        List<String> langs = List.of("python", "typescript", "go");
        Map<String, Boolean> availability = new LinkedHashMap<>();
        for (String lang : langs) {
            availability.put(lang, new ClassPathResource(BUNDLE_PATHS.get(lang)).exists());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("langs", langs);
        out.put("available", availability);
        return out;
    }
}
