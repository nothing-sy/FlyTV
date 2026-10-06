package dev.flytv.engine;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网盘扫码登录（目前实现：夸克）。
 * 流程：getTokenForQrcodeLogin 取 token → 前端展示二维码（夸克 App 扫）
 *      → 轮询 getServiceTicketByQrcodeToken 取 service_ticket
 *      → /account/info?st=&lw=scan 换 cookie → 写入三处存储（config.json / jarcache / TEMP）。
 * 仅用 java.base（HttpURLConnection），兼容精简 JRE。
 */
public final class PanLogin {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final ConcurrentHashMap<String, String> SESSIONS = new ConcurrentHashMap<>(); // session -> qr token
    private static final ConcurrentHashMap<String, JsonObject> QR_OK = new ConcurrentHashMap<>(); // session -> 已成功结果（防重叠轮询误报失效）

    /** 极简 HTTP：手动跟随重定向 + 收集 Set-Cookie。 */
    static class Resp {
        int code;
        String body = "";
    }

    static Resp get(String url, LinkedHashMap<String, String> jar) throws Exception {
        Resp out = new Resp();
        for (int hop = 0; hop <= 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "*/*");
                if (!jar.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> e : jar.entrySet()) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(e.getKey()).append("=").append(e.getValue());
                    }
                    c.setRequestProperty("Cookie", sb.toString());
                }
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                Map<String, List<String>> hs = c.getHeaderFields();
                for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                    if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
                    for (String v : e.getValue()) {
                        int eq = v.indexOf('=');
                        if (eq <= 0) continue;
                        String name = v.substring(0, eq).trim();
                        String val = v.substring(eq + 1);
                        int sc = val.indexOf(';');
                        if (sc >= 0) val = val.substring(0, sc);
                        if (!name.isEmpty()) jar.put(name, val.trim());
                    }
                }
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        out.code = code;
                        return out;
                    }
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                out.body = is == null ? "" : readAll(is);
                out.code = code;
                return out;
            } finally {
                c.disconnect();
            }
        }
        out.code = 0;
        return out;
    }

    static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 生成二维码会话（drive: quark/uc/baidu/ali/bili）。 */
    public static JsonObject qrStart(String drive) throws Exception {
        if ("baidu".equals(drive)) return qrStartBaidu();
        if ("ali".equals(drive) || "aliyun".equals(drive)) return qrStartAli();
        if ("uc".equals(drive)) return qrStartUC();
        if ("bili".equals(drive) || "bilibili".equals(drive)) return qrStartBili();
        return qrStartQuark();
    }

    /** 轮询扫码状态（与 qrStart 的 drive 对应）。 */
    public static JsonObject qrPoll(String drive, String session) throws Exception {
        if ("baidu".equals(drive)) return qrPollBaidu(session);
        if ("ali".equals(drive) || "aliyun".equals(drive)) return qrPollAli(session);
        if ("uc".equals(drive)) return qrPollUC(session);
        if ("bili".equals(drive) || "bilibili".equals(drive)) return qrPollBili(session);
        return qrPollQuark(session);
    }

    /** 生成二维码会话：返回 {session, url}（url 由前端渲染成二维码图片）。 */
    static JsonObject qrStartQuark() throws Exception {
        Resp r = get("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=" + UUID.randomUUID(),
                new LinkedHashMap<>());
        JsonObject o = JsonUtil.parseObj(r.body);
        JsonObject data = o == null ? null : o.getAsJsonObject("data");
        JsonObject members = data == null ? null : data.getAsJsonObject("members");
        String token = members == null ? "" : JsonUtil.str(members, "token", "");
        if (token.isEmpty()) throw new Exception("获取二维码失败（接口返回异常，稍后再试）");
        String session = UUID.randomUUID().toString().replace("-", "");
        SESSIONS.put(session, token);
        JsonObject out = new JsonObject();
        out.addProperty("session", session);
        out.addProperty("url", qrUrl(token));
        return out;
    }

    static String qrUrl(String token) {
        return "https://su.quark.cn/4_eMHBJ?token=" + enc(token)
                + "&client_id=532&ssb=weblogin&uc_param_str="
                + "&uc_biz_str=" + enc("S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0");
    }

    /** 轮询扫码状态：{status: wait|ok|expired, message, nickname?, member?}。 */
    static JsonObject qrPollQuark(String session) throws Exception {
        JsonObject out = new JsonObject();
        String token = session == null ? null : SESSIONS.get(session);
        if (token == null) {
            JsonObject cached = session == null ? null : QR_OK.get(session);
            if (cached != null) return cached;
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码会话已失效，请刷新");
            return out;
        }
        Resp r = get("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token="
                + enc(token) + "&request_id=" + UUID.randomUUID(), new LinkedHashMap<>());
        JsonObject o = JsonUtil.parseObj(r.body);
        int status = o == null ? 0 : JsonUtil.integer(o, "status", 0);
        JsonObject data = o == null ? null : o.getAsJsonObject("data");
        JsonObject members = data == null ? null : data.getAsJsonObject("members");
        String ticket = members == null ? "" : JsonUtil.str(members, "service_ticket", "");
        if (status == 2000000 && !ticket.isEmpty()) {
            SESSIONS.remove(session);
            String[] info = exchangeTicket(ticket); // {cookie, nickname, member}
            if (info[0].isEmpty()) throw new Exception("登录成功但换取 Cookie 失败，请重试");
            saveQuarkCookie(info[0], info[1], info[2]);
            out.addProperty("status", "ok");
            out.addProperty("nickname", info[1]);
            out.addProperty("member", info[2]);
            out.addProperty("message", "登录成功");
            QR_OK.put(session, out);
            return out;
        }
        if (status == 50004002 || status == 50004003 || status == 50004004) {
            SESSIONS.remove(session);
            out.addProperty("status", "expired");
            out.addProperty("message", status == 50004002 ? "二维码已过期，请刷新"
                    : status == 50004004 ? "已取消登录" : "登录失败，请重试");
            return out;
        }
        String msg = o == null ? "" : JsonUtil.str(o, "message", "");
        out.addProperty("status", "wait");
        boolean generic = msg.isEmpty() || "ok".equals(msg) || "Query result is empty".equalsIgnoreCase(msg);
        out.addProperty("message", generic ? "等待扫码…" : msg);
        return out;
    }

    // ================= 百度网盘扫码 =================
    // 协议：getqrcode 取 sign → 手机百度 App 扫 qrcode?sign=… → unicast 轮询拿 BDUSS
    //      → qrbdusslogin 换完整 Cookie（BDUSS/STOKEN 等）
    private static final ConcurrentHashMap<String, Object[]> BAIDU = new ConcurrentHashMap<>(); // session -> [sign, gid, jar]

    static JsonObject qrStartBaidu() throws Exception {
        String gid = UUID.randomUUID().toString().toUpperCase();
        LinkedHashMap<String, String> jar = new LinkedHashMap<>();
        Resp r = get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=pc&gid=" + enc(gid), jar);
        JsonObject o = JsonUtil.parseObj(r.body);
        String sign = o == null ? "" : JsonUtil.str(o, "sign", "");
        String imgurl = o == null ? "" : JsonUtil.str(o, "imgurl", "");
        if (sign.isEmpty() && imgurl.contains("sign=")) {
            try {
                String q = imgurl.substring(imgurl.indexOf("sign=") + 5);
                int amp = q.indexOf('&');
                sign = amp > 0 ? q.substring(0, amp) : q;
            } catch (Exception ignored) { }
        }
        if (sign.isEmpty()) throw new Exception("获取百度二维码失败（接口返回异常，稍后再试）");
        String session = UUID.randomUUID().toString().replace("-", "");
        BAIDU.put(session, new Object[]{sign, gid, jar});
        String png = imgurl.replace("\\/", "/");
        if (!png.isEmpty() && !png.startsWith("http")) png = "https://" + png;
        if (png.isEmpty()) png = "https://passport.baidu.com/v2/api/qrcode?sign=" + enc(sign) + "&lp=pc";
        String link = o == null ? "" : JsonUtil.str(o, "link", "");
        if (link.isEmpty()) link = "https://wappass.baidu.com/wp/?qrloginfrom=pc&tpl=netdisk&qrcode=1&sign=" + enc(sign);
        JsonObject out = new JsonObject();
        out.addProperty("session", session);
        out.addProperty("img", png);
        out.addProperty("url", link);
        return out;
    }

    static JsonObject qrPollBaidu(String session) throws Exception {
        JsonObject out = new JsonObject();
        Object[] st = session == null ? null : BAIDU.get(session);
        if (st == null) {
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码会话已失效，请刷新");
            return out;
        }
        String sign = String.valueOf(st[0]);
        String gid = String.valueOf(st[1]);
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, String> jar = (LinkedHashMap<String, String>) st[2];
        long ts = System.currentTimeMillis();
        Resp r;
        try {
            // 百度 unicast 长轮询：必须等状态变化；超时后立刻由前端重连，避免漏掉扫码事件
            r = getTO("https://passport.baidu.com/channel/unicast?channel_id=" + enc(sign) + "&callback=&tpl=netdisk&gid=" + enc(gid)
                    + "&apiver=v3&tt=" + ts + "&_=" + ts, jar, 35000);
        } catch (java.net.SocketTimeoutException te) {
            out.addProperty("status", "wait");
            out.addProperty("message", "等待扫码…");
            return out;
        } catch (Exception ioe) {
            out.addProperty("status", "wait");
            out.addProperty("message", "网络波动，继续等待…");
            return out;
        }
        String raw = r.body == null ? "" : r.body.trim();
        if (raw.startsWith("(") || (raw.length() > 0 && raw.charAt(0) != '{' && raw.contains("{"))) {
            int lb = raw.indexOf('{');
            int rb = raw.lastIndexOf('}');
            if (lb >= 0 && rb > lb) raw = raw.substring(lb, rb + 1);
        }
        JsonObject o = JsonUtil.parseObj(raw);
        int errno = o == null ? -1 : JsonUtil.integer(o, "errno", -1);
        String cv = o == null ? "" : JsonUtil.str(o, "channel_v", "");
        int status = 0;
        String v = "";
        if (!cv.isEmpty()) {
            JsonObject c = JsonUtil.parseObj(cv);
            if (c != null) {
                status = JsonUtil.integer(c, "status", 0);
                v = JsonUtil.str(c, "v", "");
            }
        }
        // 官方：errno=1 未扫码；status=1 已扫码未确认；status=0 且 v 为确认登录；status=2 取消/失效
        if (!v.isEmpty() && (status == 0 || status == 1)) {
            try {
                get("https://passport.baidu.com/v3/login/main/qrbdusslogin?bduss=" + enc(v) + "&tpl=netdisk&apiver=v3&u="
                        + enc("https://pan.baidu.com/"), jar);
            } catch (Exception ignored) { }
            if (!jar.containsKey("BDUSS")) jar.put("BDUSS", v);
            String cookie = joinCookies(jar);
            saveManual("baidu", cookie);
            BAIDU.remove(session);
            out.addProperty("status", "ok");
            out.addProperty("message", "登录成功");
            out.addProperty("nickname", "");
            out.addProperty("member", "");
            return out;
        }
        if (status == 1) {
            out.addProperty("status", "wait");
            out.addProperty("message", "已扫码，请在手机上确认登录");
            return out;
        }
        if (status == 2) {
            BAIDU.remove(session);
            out.addProperty("status", "expired");
            out.addProperty("message", "已取消登录，请刷新二维码");
            return out;
        }
        if (errno == 1 || errno == 0 || o == null) {
            out.addProperty("status", "wait");
            out.addProperty("message", "等待扫码…");
            return out;
        }
        BAIDU.remove(session);
        out.addProperty("status", "expired");
        out.addProperty("message", "二维码已失效，请刷新");
        return out;
    }

    // ================= 阿里云盘扫码 =================
    // 协议：generate.do 取二维码（base64 PNG）与 ck/t → query.do 轮询 → CONFIRMED 时
    //      bizExt（base64 JSON）内含 refreshToken（作为长期凭证保存）
    private static final ConcurrentHashMap<String, Object[]> ALI = new ConcurrentHashMap<>(); // session -> [ck, t]

    static JsonObject qrStartAli() throws Exception {
        LinkedHashMap<String, String> jar = new LinkedHashMap<>();
        Resp r = get("https://passport.aliyundrive.com/newlogin/qrcode/generate.do?appName=aliyun_drive&fromSite=52&appEntrance=web&_bx-v=2.0.31", jar);
        JsonObject o = JsonUtil.parseObj(r.body);
        JsonObject content = o == null ? null : o.getAsJsonObject("content");
        JsonObject data = content == null ? null : content.getAsJsonObject("data");
        String codeContent = data == null ? "" : JsonUtil.str(data, "codeContent", "");
        String ck = data == null ? "" : JsonUtil.str(data, "ck", "");
        String t = data == null ? "" : JsonUtil.str(data, "t", "");
        if (codeContent.isEmpty()) throw new Exception("获取阿里云盘二维码失败（接口返回异常，稍后再试）");
        String session = UUID.randomUUID().toString().replace("-", "");
        ALI.put(session, new Object[]{ck, t});
        JsonObject out = new JsonObject();
        out.addProperty("session", session);
        if (codeContent.startsWith("http")) {
            out.addProperty("url", codeContent);
        } else {
            out.addProperty("img", codeContent.startsWith("data:") ? codeContent : "data:image/png;base64," + codeContent);
        }
        return out;
    }

    static JsonObject qrPollAli(String session) throws Exception {
        JsonObject out = new JsonObject();
        Object[] st = session == null ? null : ALI.get(session);
        if (st == null) {
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码会话已失效，请刷新");
            return out;
        }
        String ck = String.valueOf(st[0]);
        String t = String.valueOf(st[1]);
        Resp r = post("https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&appEntrance=web&_bx-v=2.0.31",
                "ck=" + enc(ck) + "&t=" + enc(t) + "&appName=aliyun_drive&fromSite=52&appEntrance=web&bizParams=&_bx-v=2.0.31",
                "application/x-www-form-urlencoded", new LinkedHashMap<>());
        JsonObject o = JsonUtil.parseObj(r.body);
        JsonObject content = o == null ? null : o.getAsJsonObject("content");
        JsonObject data = content == null ? null : content.getAsJsonObject("data");
        String qs = data == null ? "" : JsonUtil.str(data, "qrCodeStatus", "");
        if ("CONFIRMED".equalsIgnoreCase(qs)) {
            String bizExt = data == null ? "" : JsonUtil.str(data, "bizExt", "");
            String decoded = "";
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(bizExt);
                decoded = new String(raw, StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            String refresh = "";
            String nick = "";
            java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"refreshToken\"\\s*:\\s*\"([^\"]+)\"").matcher(decoded);
            if (m1.find()) refresh = m1.group(1);
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"nickName\"\\s*:\\s*\"([^\"]*)\"").matcher(decoded);
            if (m2.find()) nick = m2.group(1);
            if (refresh.isEmpty()) {
                out.addProperty("status", "wait");
                out.addProperty("message", "已确认，但未取到令牌，请重试或改用粘贴方式");
                return out;
            }
            saveManual("ali", refresh);
            ALI.remove(session);
            out.addProperty("status", "ok");
            out.addProperty("nickname", nick);
            out.addProperty("member", "");
            out.addProperty("message", "登录成功");
            return out;
        }
        if ("SCANED".equalsIgnoreCase(qs)) {
            out.addProperty("status", "wait");
            out.addProperty("message", "已扫码，请在手机上确认登录");
            return out;
        }
        if ("EXPIRED".equalsIgnoreCase(qs)) {
            ALI.remove(session);
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码已失效，请刷新");
            return out;
        }
        out.addProperty("status", "wait");
        out.addProperty("message", "等待扫码…");
        return out;
    }

    // ================= UC网盘扫码 =================
    // 与夸克同源（api.open.uc.cn CAS，client_id=381）：token → 手机 UC 扫 → service_ticket
    //      → drive.uc.cn/account/info 换 Cookie（缺 __puus 时补 config 一次）
    private static final ConcurrentHashMap<String, Object[]> UC = new ConcurrentHashMap<>(); // session -> [token, jar]

    static JsonObject qrStartUC() throws Exception {
        LinkedHashMap<String, String> jar = new LinkedHashMap<>();
        String requestId = UUID.randomUUID().toString();
        Resp r = post("https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?__dt=" + (1000 + (int) (Math.random() * 99000)) + "&__t=" + System.currentTimeMillis(),
                "client_id=381&v=1.2&request_id=" + enc(requestId), "application/x-www-form-urlencoded", jar);
        JsonObject o = JsonUtil.parseObj(r.body);
        JsonObject data = o == null ? null : o.getAsJsonObject("data");
        JsonObject members = data == null ? null : data.getAsJsonObject("members");
        String token = members == null ? "" : JsonUtil.str(members, "token", "");
        if (token.isEmpty()) throw new Exception("获取UC二维码失败（接口返回异常，稍后再试）");
        String session = UUID.randomUUID().toString().replace("-", "");
        UC.put(session, new Object[]{token, jar});
        JsonObject out = new JsonObject();
        out.addProperty("session", session);
        out.addProperty("url", "https://su.uc.cn/1_n0ZCv?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt&token=" + enc(token)
                + "&client_id=381&uc_biz_str=" + enc("S:custom|C:titlebar_fix"));
        return out;
    }

    static JsonObject qrPollUC(String session) throws Exception {
        JsonObject out = new JsonObject();
        Object[] st = session == null ? null : UC.get(session);
        if (st == null) {
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码会话已失效，请刷新");
            return out;
        }
        String token = String.valueOf(st[0]);
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, String> jar = (LinkedHashMap<String, String>) st[1];
        Resp r = post("https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?__dt=" + (1000 + (int) (Math.random() * 99000)) + "&__t=" + System.currentTimeMillis(),
                "client_id=381&v=1.2&request_id=" + enc(UUID.randomUUID().toString()) + "&token=" + enc(token),
                "application/x-www-form-urlencoded", jar);
        JsonObject o = JsonUtil.parseObj(r.body);
        int status = o == null ? 0 : JsonUtil.integer(o, "status", 0);
        String ticket = "";
        if (status == 2000000) {
            JsonObject data = o.getAsJsonObject("data");
            JsonObject members = data == null ? null : data.getAsJsonObject("members");
            ticket = members == null ? "" : JsonUtil.str(members, "service_ticket", "");
        }
        if (!ticket.isEmpty()) {
            get("https://drive.uc.cn/account/info?st=" + enc(ticket), jar);
            if (!joinCookies(jar).contains("__puus")) {
                try { get("https://drive.uc.cn/1/clouddrive/config?pr=ucpro&fr=pc&uc_param_str=", jar); } catch (Exception ignored) { }
            }
            String cookie = joinCookies(jar);
            saveManual("uc", cookie);
            UC.remove(session);
            out.addProperty("status", "ok");
            out.addProperty("message", "登录成功");
            out.addProperty("nickname", "");
            out.addProperty("member", "");
            return out;
        }
        if (status == 50004002 || status == 50004003 || status == 50004004) {
            UC.remove(session);
            out.addProperty("status", "expired");
            out.addProperty("message", status == 50004002 ? "二维码已过期，请刷新" : status == 50004004 ? "已取消登录" : "登录失败，请重试");
            return out;
        }
        String msg = status != 0 && o != null ? JsonUtil.str(o, "message", "") : "";
        boolean generic = msg.isEmpty() || "ok".equals(msg) || "Query result is empty".equalsIgnoreCase(msg);
        out.addProperty("status", "wait");
        out.addProperty("message", generic ? "等待扫码…" : msg);
        return out;
    }

    // ================= 哔哩哔哩扫码 =================
    // 官方 Web 扫码：generate 取 qrcode_key → poll 轮询 → 成功后 Set-Cookie 含 SESSDATA 等
    private static final ConcurrentHashMap<String, Object[]> BILI = new ConcurrentHashMap<>(); // session -> [qrcode_key, jar]

    static JsonObject qrStartBili() throws Exception {
        LinkedHashMap<String, String> jar = new LinkedHashMap<>();
        Resp r = get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-mini", jar);
        JsonObject o = JsonUtil.parseObj(r.body);
        JsonObject d = o == null ? null : o.getAsJsonObject("data");
        String url = d == null ? "" : JsonUtil.str(d, "url", "");
        String key = d == null ? "" : JsonUtil.str(d, "qrcode_key", "");
        if (url.isEmpty() || key.isEmpty()) throw new Exception("获取B站二维码失败（接口返回异常，稍后再试）");
        String session = UUID.randomUUID().toString().replace("-", "");
        BILI.put(session, new Object[]{key, jar});
        JsonObject out = new JsonObject();
        out.addProperty("session", session);
        out.addProperty("url", url);
        return out;
    }

    static JsonObject qrPollBili(String session) throws Exception {
        JsonObject out = new JsonObject();
        Object[] st = session == null ? null : BILI.get(session);
        if (st == null) {
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码会话已失效，请刷新");
            return out;
        }
        String key = String.valueOf(st[0]);
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, String> jar = (LinkedHashMap<String, String>) st[1];
        Resp r = get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + enc(key) + "&source=main-mini", jar);
        JsonObject o = JsonUtil.parseObj(r.body);
        JsonObject d = o == null ? null : o.getAsJsonObject("data");
        int code = d == null ? -1 : JsonUtil.integer(d, "code", -1);
        if (code == 0) {
            String cookie = joinCookies(jar);
            if (cookie.isEmpty() || !cookie.contains("SESSDATA")) {
                out.addProperty("status", "wait");
                out.addProperty("message", "已确认，Cookie 未取到，请重试");
                return out;
            }
            String nick = "";
            try {
                Resp nav = get("https://api.bilibili.com/x/web-interface/nav", jar);
                JsonObject no = JsonUtil.parseObj(nav.body);
                JsonObject nd = no == null ? null : no.getAsJsonObject("data");
                if (nd != null) nick = JsonUtil.str(nd, "uname", "");
            } catch (Exception ignored) { }
            saveManual("bili", cookie);
            BILI.remove(session);
            out.addProperty("status", "ok");
            out.addProperty("nickname", nick);
            out.addProperty("member", "");
            out.addProperty("message", "登录成功");
            return out;
        }
        if (code == 86090) {
            out.addProperty("status", "wait");
            out.addProperty("message", "已扫码，请在手机上确认登录");
            return out;
        }
        if (code == 86038) {
            BILI.remove(session);
            out.addProperty("status", "expired");
            out.addProperty("message", "二维码已失效，请刷新");
            return out;
        }
        out.addProperty("status", "wait");
        out.addProperty("message", "等待扫码…");
        return out;
    }

    /** 带自定义读超时的 GET（用于长轮询接口，如百度 unicast）。 */
    static Resp getTO(String url, LinkedHashMap<String, String> jar, int readMs) throws Exception {        Resp out = new Resp();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (!jar.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : jar.entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(e.getKey()).append("=").append(e.getValue());
                }
                c.setRequestProperty("Cookie", sb.toString());
            }
            c.setConnectTimeout(10000);
            c.setReadTimeout(readMs);
            int code = c.getResponseCode();
            Map<String, List<String>> hs = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
                for (String v : e.getValue()) {
                    int eq = v.indexOf('=');
                    if (eq <= 0) continue;
                    String name = v.substring(0, eq).trim();
                    String val = v.substring(eq + 1);
                    int sc = val.indexOf(';');
                    if (sc >= 0) val = val.substring(0, sc);
                    if (!name.isEmpty()) jar.put(name, val.trim());
                }
            }
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            out.body = is == null ? "" : readAll(is);
            out.code = code;
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** 简易 POST（表单），带 Set-Cookie 收集。 */
    static Resp post(String url, String body, String contentType, LinkedHashMap<String, String> jar) throws Exception {        Resp out = new Resp();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            if (!jar.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : jar.entrySet()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(e.getKey()).append("=").append(e.getValue());
                }
                c.setRequestProperty("Cookie", sb.toString());
            }
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (body != null) c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            Map<String, List<String>> hs = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
                for (String v : e.getValue()) {
                    int eq = v.indexOf('=');
                    if (eq <= 0) continue;
                    String name = v.substring(0, eq).trim();
                    String val = v.substring(eq + 1);
                    int sc = val.indexOf(';');
                    if (sc >= 0) val = val.substring(0, sc);
                    if (!name.isEmpty()) jar.put(name, val.trim());
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            out.body = is == null ? "" : readAll(is);
            out.code = code;
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** service_ticket 换 cookie：GET /account/info?st=&lw=scan（跟随跳转，收集 quark.cn 域 Cookie）。 */
    static String[] exchangeTicket(String ticket) {
        try {
            LinkedHashMap<String, String> jar = new LinkedHashMap<>();
            Resp r = get("https://pan.quark.cn/account/info?st=" + enc(ticket) + "&lw=scan", jar);
            // 关键补全：夸克在请求 drive config 时才下发 __puus 会话 Cookie（缺它则 CDN 拉流 412）
            try { get("https://drive-pc.quark.cn/1/clouddrive/config?pr=ucpro&fr=pc&uc_param_str=", jar); } catch (Exception ignored) { }
            try { get("https://pan.quark.cn/", jar); } catch (Exception ignored) { }
            // 护照域补全：浏览器完整登录态里的 _UP_* 等长期 Cookie 在 uop/网页域下发，收齐会话更耐用
            try { get("https://uop.quark.cn/", jar); } catch (Exception ignored) { }
            try { get("https://pan.quark.cn/list", jar); } catch (Exception ignored) { }
            String cookie = joinCookies(jar);
            String nickname = "";
            String member = "";
            JsonObject o = JsonUtil.parseObj(r.body);
            if (o != null) {
                nickname = JsonUtil.str(o, "nickname", "");
                JsonObject d = o.getAsJsonObject("data");
                if (nickname.isEmpty() && d != null) nickname = JsonUtil.str(d, "nickname", "");
            }
            try {
                Resp m = get("https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=&fetch_subscribe=true&_ch=home&fetch_identity=true", jar);
                JsonObject mo = JsonUtil.parseObj(m.body);
                JsonObject md = mo == null ? null : mo.getAsJsonObject("data");
                if (md != null) {
                    if (nickname.isEmpty()) nickname = JsonUtil.str(md, "nickname", "");
                    member = JsonUtil.str(md, "member_type", "");
                }
                String cookie2 = joinCookies(jar);
                if (cookie2.length() > cookie.length()) cookie = cookie2;
            } catch (Exception ignored) { }
            if (!cookie.contains("__puus") || cookie.split(";").length < 10) {
                // 再补一刀：config + 护照域，尽量凑齐完整字段
                try { get("https://drive-pc.quark.cn/1/clouddrive/config?pr=ucpro&fr=pc&uc_param_str=", jar); } catch (Exception ignored) { }
                try { get("https://uop.quark.cn/", jar); } catch (Exception ignored) { }
                String cookie3 = joinCookies(jar);
                if (cookie3.length() > cookie.length()) cookie = cookie3;
            }
            Logger.d("PanLogin", "登录 Cookie 长度=" + cookie.length() + " 字段数=" + cookie.split(";").length + " 含__puus=" + cookie.contains("__puus"));
            return new String[]{cookie, nickname, member};
        } catch (Exception e) {
            return new String[]{"", "", ""};
        }
    }

    static String joinCookies(LinkedHashMap<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    /** 保存夸克 cookie 到三处：jarcache 文件、config.json 字段、TEMP\\TVBox（jar 宿主用）。 */
    public static void saveQuarkCookie(String cookie, String nickname, String member) throws Exception {
        File dir = new File(AppPaths.JarCache, "files" + File.separator + "Pizazz");
        if (!dir.exists()) dir.mkdirs();
        JsonObject j = new JsonObject();
        j.addProperty("cookie", cookie);
        j.addProperty("nickname", nickname == null ? "" : nickname);
        j.addProperty("member_type", member == null ? "" : member);
        j.addProperty("time", System.currentTimeMillis());
        String text = j.toString();
        writeUtf8(new File(dir, "quark_cookie.txt"), text);
        writeUtf8(new File(dir, "quark_cookie"), text);
        File cfg = new File(dir, "config.json");
        if (cfg.exists()) {
            try {
                JsonObject c = JsonUtil.parseObj(new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8));
                if (c != null) {
                    c.addProperty("quark_cookie", cookie);
                    writeUtf8(cfg, c.toString());
                }
            } catch (Exception ignored) { }
        }
        try {
            File tmp = new File(System.getenv("TEMP") == null ? "." : System.getenv("TEMP"), "TVBox");
            if (!tmp.exists()) tmp.mkdirs();
            writeUtf8(new File(tmp, "quark_cookie.txt"), text);
            writeUtf8(new File(tmp, "quark_cookie"), text);
        } catch (Exception ignored) { }
    }

    /** 各网盘 Cookie/Token 的文件名（jar 仓库读取的名字 + 兼容名）。 */
    public static String[] cookieNames(String drive) {
        switch (drive == null ? "" : drive) {
            case "quark": return new String[]{"quark_cookie.txt", "quark_cookie"};
            case "uc": return new String[]{"uc_cookie.txt", "uc_cookie"};
            case "uctoken": return new String[]{"uc_token.txt", "uc_token"};
            case "baidu": return new String[]{"baidu_cookie.txt", "baidu_cookie", "baidu.txt", "baidu"};
            case "ali": case "aliyun": return new String[]{"ali_cookie.txt", "ali_cookie"};
            case "cloud123": return new String[]{"cloud123.txt", "cloud123"};
            case "xunlei": return new String[]{"xunlei.txt", "xunlei"};
            case "guangya": return new String[]{"guangya.txt", "guangya"};
            case "cloud189": return new String[]{"cloud189.txt", "cloud189"};
            case "bili": return new String[]{"bili_cookie.txt", "bili_cookie"};
            case "115": return new String[]{"115.txt", "115"};
            default: return new String[]{drive + "_cookie.txt", drive + "_cookie"};
        }
    }

    /** 手动粘贴 cookie/token 保存（任意网盘：quark/uc/baidu/ali/uctoken/bili/xunlei/guangya/cloud189/cloud123/115）。 */
    public static JsonObject saveManual(String drive, String cookie) throws Exception {
        cookie = cookie == null ? "" : cookie.trim().replace("\r", "").replace("\n", " ");
        JsonObject out = new JsonObject();
        if (cookie.isEmpty()) {
            out.addProperty("error", "Cookie 不能为空");
            return out;
        }
        String[] names = cookieNames(drive);
        String base = names[0].replace(".txt", "");
        JsonObject j = new JsonObject();
        j.addProperty("cookie", cookie);
        j.addProperty("time", System.currentTimeMillis());
        String text = j.toString();
        String[] dirs = {
                AppPaths.JarCache + "\\files" + File.separator + "Pizazz",
                AppPaths.JarCache + "\\files" + File.separator + "lzxw",
                (System.getenv("TEMP") == null ? "." : System.getenv("TEMP")) + File.separator + "TVBox"
        };
        for (String dirPath : dirs) {
            try {
                File dir = new File(dirPath);
                if (!dir.exists()) dir.mkdirs();
                for (String name : names) writeUtf8(new File(dir, name), text);
            } catch (Exception ignored) { }
        }
        File cfg = new File(new File(AppPaths.JarCache, "files" + File.separator + "Pizazz"), "config.json");
        if (cfg.exists()) {
            try {
                JsonObject c = JsonUtil.parseObj(new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8));
                if (c != null) {
                    c.addProperty(base, cookie);
                    writeUtf8(cfg, c.toString());
                }
            } catch (Exception ignored) { }
        }
        out.addProperty("ok", true);
        out.addProperty("message", "已保存");
        return out;
    }

    static void writeUtf8(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
