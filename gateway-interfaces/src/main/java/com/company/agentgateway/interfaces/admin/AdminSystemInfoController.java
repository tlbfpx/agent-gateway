package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.interfaces.info.InfoController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /v1/admin/system-info — JVM / runtime 信息（spec §system-info round 82）。
 *
 * <p>SRE 排障用：堆内存、CPU、线程、类路径、启动时间、版本号。
 * 给客服 / 售前在客户报问题时能快速「一查」网关环境。
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminSystemInfoController {

    @GetMapping("/system-info")
    public Map<String, Object> info(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        Runtime runtime = Runtime.getRuntime();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("version", System.getProperty("java.version", "unknown"));
        jvm.put("vendor", System.getProperty("java.vendor", "unknown"));
        jvm.put("runtimeName", System.getProperty("java.runtime.name", "unknown"));
        jvm.put("vmName", System.getProperty("java.vm.name", "unknown"));
        jvm.put("vmVersion", System.getProperty("java.vm.version", "unknown"));

        Map<String, Object> memory = new LinkedHashMap<>();
        long maxBytes = runtime.maxMemory();
        long totalBytes = runtime.totalMemory();
        long freeBytes = runtime.freeMemory();
        long usedBytes = totalBytes - freeBytes;
        memory.put("max", maxBytes);
        memory.put("total", totalBytes);
        memory.put("free", freeBytes);
        memory.put("used", usedBytes);
        memory.put("maxHuman", human(maxBytes));
        memory.put("usedHuman", human(usedBytes));

        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("count", ManagementFactory.getThreadMXBean().getThreadCount());
        threads.put("peakCount", ManagementFactory.getThreadMXBean().getPeakThreadCount());
        threads.put("daemonCount", ManagementFactory.getThreadMXBean().getDaemonThreadCount());

        Map<String, Object> osInfo = new LinkedHashMap<>();
        osInfo.put("name", System.getProperty("os.name"));
        osInfo.put("arch", System.getProperty("os.arch"));
        osInfo.put("version", System.getProperty("os.version"));
        osInfo.put("availableProcessors", runtime.availableProcessors());
        // loadAverage 是双精度 0~1（1=满载），JDK 9+ 支持
        java.lang.management.OperatingSystemMXBean osBean =
                ManagementFactory.getOperatingSystemMXBean();
        try {
            osInfo.put("loadAverage", osBean.getSystemLoadAverage());
        } catch (Exception e) {
            osInfo.put("loadAverage", -1);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", InfoController.class.getPackage().getImplementationVersion());
        out.put("jvm", jvm);
        out.put("memory", memory);
        out.put("threads", threads);
        out.put("os", osInfo);
        out.put("uptimeSeconds",
                (System.currentTimeMillis()
                        - ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000);
        return out;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return Math.round(bytes / (1024.0 * 1024)) + " MB";
        return Math.round(bytes / (1024.0 * 1024 * 1024)) + " GB";
    }
}