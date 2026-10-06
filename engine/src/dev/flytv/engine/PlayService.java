package dev.flytv.engine;

import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** /api/play 编排：爬虫 player → 网盘 URL 走转存中继；直链原样返回。 */
public final class PlayService {
    public static JsonObject play(String siteKey, String flag, String epUrl) {
        try {
            VodConfig.Site site = findSite(siteKey);
            if (site == null) return err("站点不存在: " + siteKey);
            JsonObject pr = SiteService.player(site, flag, epUrl);
            if (pr.has("error")) return pr;
            String url = JsonUtil.str(pr, "url", "");
            if (url.isEmpty()) {
                // 优先用蜘蛛给的具体原因（未登录/资源异常），避免只报"未获取到播放地址"
                String m = JsonUtil.str(pr, "msg", "");
                if (m.isEmpty()) m = JsonUtil.str(pr, "errMsg", "");
                return err(m.isEmpty() ? "未获取到播放地址" : m);
            }
            if (url.contains("/proxy") && !url.contains("siteKey=") && isAsciiKey(site.key)) {
                url += (url.contains("?") ? "&" : "?") + "siteKey=" + enc(site.key);
                pr.addProperty("url", url);
            }
            if (isQuarkPanUrl(url)) {
                String relay = PanService.resolveToRelay(url);
                JsonObject o = new JsonObject();
                o.addProperty("url", relay);
                o.add("headers", new JsonObject());
                o.addProperty("format", "");
                Logger.d("PlayService", "网盘中继就绪: " + siteKey);
                return o;
            }
            return pr;
        } catch (Exception e) {
            Logger.e("PlayService", "play(" + siteKey + ") " + e.getMessage());
            return err(e.getMessage());
        }
    }

    /**
     * 播放前预检：拿中继地址试探 1KB，提前识别"登录过期 / 文件失效"，避免盲目起播+无效重试。
     * 只探测本机中继（jarstream / proxy），其它地址一律放行。
     */
    public static JsonObject check(String url) {
        JsonObject o = new JsonObject();
        boolean local = url != null && (url.contains("127.0.0.1:9790/jarstream")
                || url.contains("127.0.0.1:9978/proxy") || url.contains("/proxy?do=pan"));
        if (!local) { o.addProperty("ok", true); o.addProperty("skip", true); return o; }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Range", "bytes=0-1023");
            c.setConnectTimeout(8000);
            c.setReadTimeout(40000);
            int code = c.getResponseCode();
            if (code == 200 || code == 206 || (code >= 300 && code < 400)) { o.addProperty("ok", true); return o; }
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = is == null ? "" : readSnippet(is, 1500);
            String lower = body.toLowerCase();
            o.addProperty("ok", false);
            o.addProperty("code", code);
            if (lower.contains("auth expired") || lower.contains("require login") || body.contains("请先登录") || code == 412 || code == 401) {
                o.addProperty("reason", "login");
                o.addProperty("message", code == 412 ? "网盘登录状态已失效（412），请重新登录后再播放" : "网盘登录已过期，请重新登录后再播放");
            } else if (lower.contains("file not found") || lower.contains("21001") || body.contains("文件被删除") || body.contains("不存在")) {
                o.addProperty("reason", "dead");
                o.addProperty("message", "文件已从网盘失效（被删除或分享变动），请换线路");
            } else {
                o.addProperty("reason", "http");
                o.addProperty("message", "拉流被拒绝（HTTP " + code + "），请稍后重试或换线路");
            }
            Logger.d("PlayService", "预检未通过: code=" + code + " reason=" + JsonUtil.str(o, "reason", ""));
            return o;
        } catch (Exception e) {
            // 探测本身失败（超时/瞬时网络）→ 不拦截，交给播放器自身逻辑
            o.addProperty("ok", true);
            o.addProperty("skip", true);
            o.addProperty("message", e.getMessage() == null ? "预检异常" : e.getMessage());
            return o;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readSnippet(InputStream is, int max) throws Exception {
        byte[] buf = new byte[512];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) > 0 && bos.size() < max) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg == null ? "播放失败" : msg);
        return o;
    }

    static boolean isQuarkPanUrl(String url) {
        if (url == null || !PanService.isPanUrl(url)) return false;
        if (!url.contains("fileId=") || !url.contains("siteKey=")) return false;
        String u = url.toLowerCase();
        if (u.contains("baidu") || u.contains("type=bd") || u.contains("type=uc") || u.contains("type=ali")) return false;
        return true;
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static boolean isAsciiKey(String k) {
        if (k == null || k.isEmpty()) return false;
        for (int i = 0; i < k.length(); i++) if (k.charAt(i) < 33 || k.charAt(i) > 126) return false;
        return true;
    }

    public static VodConfig.Site findSite(String key) {
        if (key == null || key.isEmpty()) {
            for (VodConfig.Site s : VodConfig.sites())
                if (!s.hidden) return s;
            return null;
        }
        for (VodConfig.Site s : VodConfig.sites())
            if (key.equals(s.key) || key.equals(s.name)) return s;
        return null;
    }
}
