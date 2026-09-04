package com.company.agentgateway.application.plugin.builtin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginCapability;
import com.company.agentgateway.domain.plugin.PluginDescriptor;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * CompressPlugin —— body > 1KB 自动 gzip (Round 15 §wasm-plugins)。
 *
 * <p>官方样本插件 #2,演示 BODY_TRANSFORM + COMPRESS 能力。
 *
 * <p>P0 用 JDK {@link Deflater}(raw DEFLATE,base64 编码);
 * R15+2 替换为 java.util.zip.GZIPOutputStream 真实 gzip 字节流。
 */
public class CompressPlugin implements Plugin {

    public static final String ID = "builtin-compress";
    private static final int THRESHOLD_BYTES = 1024;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                ID, "Auto Compress", "1.0.0",
                "body > 1KB 时自动 DEFLATE 压缩,标记 Content-Encoding: deflate",
                PluginDescriptor.PluginFormat.JAVA,
                Set.of(PluginCapability.COMPRESS, PluginCapability.BODY_TRANSFORM),
                List.of("perf", "builtin"),
                true);
    }

    @Override
    public PluginResponse handle(PluginRequest request) {
        byte[] body = request.body().getBytes(StandardCharsets.UTF_8);
        if (body.length < THRESHOLD_BYTES) {
            return new PluginResponse(200, request.headers(), request.body(), false, null);
        }
        // raw DEFLATE
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(body);
        deflater.finish();
        byte[] buf = new byte[1024];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        String compressed = Base64.getEncoder().encodeToString(baos.toByteArray());
        Map<String, String> headers = new LinkedHashMap<>(request.headers());
        headers.put("Content-Encoding", "deflate");
        headers.put("X-Original-Size", String.valueOf(body.length));
        headers.put("X-Compressed-Size", String.valueOf(baos.size()));
        return new PluginResponse(200, headers, compressed, false, null);
    }
}