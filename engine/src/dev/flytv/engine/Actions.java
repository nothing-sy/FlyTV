package dev.flytv.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** /action（do=refresh / do=sync 设备同步）与 /proxy（do=pan 网盘转存 302）等协议端点。 */
public final class Actions {
    public static boolean handle(HttpExchange ex, String path) throws Exception {
        if (path.startsWith("/action")) { doAction(ex); return true; }
        if (path.startsWith("/proxy")) { doProxy(ex); return true; }
        if (path.startsWith("/tvbus")) { WebServer.text(ex, 200, ""); return true; }
        if (path.startsWith("/media")) { WebServer.json(ex, "{}"); return true; }
        return false;
    }

    static void doAction(HttpExchange ex) throws Exception {
        try {
            Map<String, String> p = WebServer.params(ex);
            String action = p.getOrDefault("do", "");
            if ("refresh".equals(action)) {
                new Thread(() -> {
                    try { VodConfig.load(Setting.configVod()); } catch (Exception e) { Logger.e("Actions", "refresh 失败: " + e.getMessage()); }
                }, "refresh").start();
            } else if ("sync".equals(action)) {
                sync(p);
            }
        } catch (Exception e) {
            Logger.e("Actions", "action: " + e);
        }
        WebServer.text(ex, 200, "OK");
    }

    /** 设备同步接收端（安卓端 POST）：mode 0/1 合并 body 里的 targets。 */
    static void sync(Map<String, String> p) {
        try {
            String type = p.getOrDefault("type", "");
            String mode = p.getOrDefault("mode", "0");
            int cid = Api.currentCid();
            if ("history".equals(type) && ("0".equals(mode) || "1".equals(mode))) {
                JsonArray targets = JsonUtil.parseArr(p.getOrDefault("targets", ""));
                if (targets != null) {
                    int n = 0;
                    for (JsonElement e : targets) {
                        if (!e.isJsonObject()) continue;
                        JsonObject t = e.getAsJsonObject();
                        String key = JsonUtil.str(t, "key", "");
                        if (key.isEmpty()) continue;
                        JsonObject mine = Stores.findHistory(cid, key);
                        if (mine == null || JsonUtil.lng(t, "createTime", 0) > JsonUtil.lng(mine, "createTime", 0)) {
                            t.addProperty("cid", cid);
                            Stores.saveHistoryKeepTime(t);
                            n++;
                        }
                    }
                    Logger.d("Sync", "历史合并 " + n + " 条（cid=" + cid + "）");
                }
            } else if ("keep".equals(type) && ("0".equals(mode) || "1".equals(mode))) {
                JsonArray targets = JsonUtil.parseArr(p.getOrDefault("targets", ""));
                if (targets != null) {
                    int n = 0;
                    for (JsonElement e : targets) {
                        if (!e.isJsonObject()) continue;
                        JsonObject t = e.getAsJsonObject();
                        String key = JsonUtil.str(t, "key", "");
                        if (key.isEmpty()) continue;
                        if (Stores.findKeep(cid, key) == null) {
                            t.addProperty("cid", cid);
                            Stores.saveKeep(t);
                            n++;
                        }
                    }
                    Logger.d("Sync", "收藏合并 " + n + " 条");
                }
            }
        } catch (Exception e) {
            Logger.e("Sync", String.valueOf(e));
        }
    }

    /** /proxy?do=pan → 网盘转存 → 302 到 jarstream 中继。 */
    static void doProxy(HttpExchange ex) throws Exception {
        Map<String, String> p = WebServer.params(ex);
        String doWhat = p.getOrDefault("do", "");
        String type = p.getOrDefault("type", p.getOrDefault("site", ""));
        String rawQ = ex.getRequestURI().getRawQuery();
        String rawLow = rawQ == null ? "" : rawQ.toLowerCase();
        boolean quark = "pan".equals(doWhat)
                && p.containsKey("siteKey") && p.containsKey("fileId")
                && !rawLow.contains("baidu") && !rawLow.contains("type=bd")
                && !rawLow.contains("type=uc") && !rawLow.contains("type=ali")
                && !type.toLowerCase().contains("baidu") && !"bd".equalsIgnoreCase(type)
                && !"uc".equalsIgnoreCase(type) && !type.toLowerCase().contains("ali");
        if (quark) {
            try {
                String rawQuery = ex.getRequestURI().getRawQuery();
                String full = "http://127.0.0.1/proxy?" + (rawQuery == null ? "" : rawQuery);
                String relay = PanService.resolveToRelay(full);
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Location", relay);
                ex.sendResponseHeaders(302, -1);
                try { ex.close(); } catch (Exception ignored) { }
            } catch (Exception e) {
                Logger.e("Proxy", "pan: " + e.getMessage());
                WebServer.text(ex, 500, "pan error: " + e.getMessage());
            }
            return;
        }
        forwardJarProxy(ex);
    }

    static void forwardJarProxy(HttpExchange ex) throws Exception {
        String q = ex.getRequestURI().getRawQuery();
        HttpURLConnection c = (HttpURLConnection) new URL(JarHost.baseUrl() + "/proxy?" + (q == null ? "" : q)).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(ex.getRequestMethod() == null ? "GET" : ex.getRequestMethod());
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) c.setRequestProperty("Range", range);
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            int code = c.getResponseCode();
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        String loc = c.getHeaderField("Location");
            if (loc != null && !loc.isEmpty()) {
                ex.getResponseHeaders().set("Location", loc);
                ex.sendResponseHeaders(code >= 300 && code < 400 ? code : 302, -1);
                return;
            }
            String mime = c.getContentType();
            if (mime != null) ex.getResponseHeaders().set("Content-Type", mime);
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) ex.getResponseHeaders().set("Content-Range", cr);
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] buf = new byte[16384];
            if (is == null) {
                ex.sendResponseHeaders(code, -1);
                return;
            }
            ex.sendResponseHeaders(code, 0);
            OutputStream os = ex.getResponseBody();
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { ex.close(); } catch (Exception ignored) { }
            c.disconnect();
        }
    }
}
