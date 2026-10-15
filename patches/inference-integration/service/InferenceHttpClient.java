/*
 * AI Native OS - 推理 HTTP 客户端
 *
 * 统一的 OpenAI 兼容 /chat/completions 客户端，同时服务于：
 *   - 云端档位（TIER_CLOUD）      -> DeepSeek（https://api.deepseek.com）
 *   - 端侧决策档位（TIER_LOCAL_*）-> NeoHorse-Jev-4B（本地 llama.cpp 服务，127.0.0.1）
 *
 * 配置来源：/vendor/ainative/config/inference_providers.json
 */
package com.android.server.ainative;

import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class InferenceHttpClient {

    private static final String TAG = "InferenceHttpClient";
    private static final String CONFIG_PATH =
            "/vendor/ainative/config/inference_providers.json";

    /** 配置节名 */
    static final String SECTION_CLOUD = "cloud";
    static final String SECTION_LOCAL = "localDecision";

    private InferenceHttpClient() {}

    static final class ProviderConfig {
        String provider = "";
        String endpoint = "";
        String model = "";
        String apiKey = "";
        boolean enabled = false;
        int timeoutMs = 60000;

        /** 是否具备真实调用条件（apiKey 可为空：本地服务无需鉴权） */
        boolean usable() {
            return enabled
                    && endpoint != null && !endpoint.isEmpty()
                    && model != null && !model.isEmpty();
        }

        @Override
        public String toString() {
            return "provider=" + provider + ", model=" + model + ", endpoint=" + endpoint
                    + ", enabled=" + enabled + ", hasKey=" + (apiKey != null && !apiKey.isEmpty());
        }
    }

    interface DeltaSink {
        void onDelta(String deltaText);
    }

    /** 读取指定节的提供者配置 */
    static ProviderConfig loadConfig(String section) {
        ProviderConfig cfg = new ProviderConfig();
        String raw = readFile(CONFIG_PATH);
        if (raw == null) {
            Slog.w(TAG, "config file not found: " + CONFIG_PATH);
            return cfg;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject s = root.optJSONObject(section);
            if (s == null) {
                Slog.w(TAG, "config section missing: " + section);
                return cfg;
            }
            cfg.provider = s.optString("provider", "");
            cfg.endpoint = s.optString("endpoint", "");
            cfg.model = s.optString("model", "");
            cfg.apiKey = s.optString("apiKey", "");
            cfg.enabled = s.optBoolean("enabled", false);
            cfg.timeoutMs = s.optInt("timeoutMs", 60000);
        } catch (Throwable t) {
            Slog.e(TAG, "loadConfig failed: " + section, t);
        }
        return cfg;
    }

    /**
     * 同步调用 OpenAI 兼容 chat 接口。
     *
     * @param prompt    用户输入
     * @param maxTokens 最大生成 token（<=0 表示不限制）
     * @param sink      流式回调（可为 null）；按句/空白分块投递
     * @return 模型返回的完整文本（失败抛异常）
     */
    static String chat(ProviderConfig cfg, String prompt, int maxTokens, DeltaSink sink)
            throws Exception {
        if (!cfg.usable()) {
            throw new IllegalStateException("provider not usable (" + cfg + ")");
        }

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", prompt == null ? "" : prompt);
        messages.put(msg);
        body.put("messages", messages);
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        body.put("stream", false);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(cfg.endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Math.min(cfg.timeoutMs, 20000));
            conn.setReadTimeout(cfg.timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String resp = readStream(is);

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " - " + truncate(resp, 300));
            }

            String text = extractContent(resp);
            if (sink != null && text != null && !text.isEmpty()) {
                for (String part : splitForStreaming(text)) {
                    sink.onDelta(part);
                }
            }
            return text;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String extractContent(String resp) throws Exception {
        if (resp == null || resp.isEmpty()) {
            return "";
        }
        JSONObject o = new JSONObject(resp);
        JSONArray choices = o.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c = choices.getJSONObject(0);
            JSONObject m = c.optJSONObject("message");
            if (m != null) {
                String content = m.optString("content", "");
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }
            String t = c.optString("text", "");
            if (t != null && !t.isEmpty()) {
                return t;
            }
        }
        // 部分本地服务直接返回 {"content": "..."} 或 {"response": "..."}
        String c1 = o.optString("content", "");
        if (!c1.isEmpty()) {
            return c1;
        }
        String c2 = o.optString("response", "");
        if (!c2.isEmpty()) {
            return c2;
        }
        return "";
    }

    /** 按中英文句末标点或空白切分，形成流式投递粒度 */
    private static String[] splitForStreaming(String text) {
        String[] parts = text.split("(?<=[。！？；.!?;\\n])|(?<=\\s)");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out.toArray(new String[0]);
    }

    private static String readFile(String path) {
        try (InputStream is = new FileInputStream(path)) {
            return readStream(is);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
