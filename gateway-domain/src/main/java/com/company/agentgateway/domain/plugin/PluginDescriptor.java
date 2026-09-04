package com.company.agentgateway.domain.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件描述（spec 2026-09-02 §wasm-plugins §3.2）。
 *
 * <p>对应 plugin.yaml:name/version/capabilities/format。
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String description,
        PluginFormat format,
        Set<PluginCapability> capabilities,
        List<String> tags,
        boolean builtin) {

    public enum PluginFormat { JAVA, WASM }

    public PluginDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (version == null || version.isBlank()) version = "1.0.0";
        if (format == null) format = PluginFormat.JAVA;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("version", version);
        m.put("description", description == null ? "" : description);
        m.put("format", format.name());
        m.put("capabilities", capabilities.stream().map(Enum::name).toList());
        m.put("tags", tags);
        m.put("builtin", builtin);
        return m;
    }
}