package com.fmplayer.network;

import android.content.Context;
import android.util.Log;

import com.fmplayer.model.SongInfo;
import com.fmplayer.preferences.ServerPreferences;

import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 所有与 fm-backend 的通信集中在这里。
 * Android 只知道一个后端地址，其余服务对客户端完全透明。
 */
public class BackendClient {

    private static final String TAG  = "BackendClient";
    private static final MediaType JSON = MediaType.get("application/json");

    private static BackendClient instance;
    private final ServerPreferences prefs;

    /**
     * /next 是同步处理接口，最坏情况下会串联三段耗时：
     *   网易云下载(最多60s) + 上传移动云盘(最多120s) + 现取直链
     * 留够余量到 240 秒。绝大多数情况几秒就返回（歌曲已在云盘上），
     * 这个超时只是兜底，不代表每次都要等这么久。
     * 用 newBuilder() 派生，和主 client 共用连接池和 TLS 配置，只改读超时。
     */
    private static volatile OkHttpClient longPollClient;

    private static OkHttpClient longPoll() {
        if (longPollClient == null) {
            synchronized (BackendClient.class) {
                if (longPollClient == null) {
                    longPollClient = AppOkHttpClient.get().newBuilder()
                            .readTimeout(240, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return longPollClient;
    }

    public static synchronized BackendClient get(Context ctx) {
        if (instance == null) instance = new BackendClient(ctx.getApplicationContext());
        return instance;
    }
    private BackendClient(Context ctx) { this.prefs = ServerPreferences.get(ctx); }

    private String base() { return prefs.getBackendUrl().trim().replaceAll("/+$", ""); }

    // ── 获取下一首（阻塞，后端最多处理 240s）─────────────────────────────

    public SongInfo next() throws IOException {
        Request req = new Request.Builder().url(base() + "/next").build();
        long startMs = System.currentTimeMillis();
        try (Response resp = longPoll().newCall(req).execute()) {
            String body = resp.body().string();
            long costMs = System.currentTimeMillis() - startMs;
            DebugLog.log("next", "HTTP " + resp.code() + "，耗时" + costMs + "ms");
            if (resp.code() == 504) throw new IOException("队列暂无就绪歌曲");
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return SongInfo.fromJson(new JSONObject(body));
        } catch (IOException e) {
            long costMs = System.currentTimeMillis() - startMs;
            DebugLog.log("next", "异常，耗时" + costMs + "ms: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new IOException("解析失败: " + e.getMessage());
        }
    }

    // ── 上报已播放 ────────────────────────────────────────────────────────

    public void played(String songId, int seconds) {
        try {
            JSONObject body = new JSONObject();
            body.put("id", songId);
            body.put("seconds", seconds);
            Request req = new Request.Builder()
                    .url(base() + "/played")
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            AppOkHttpClient.get().newCall(req).execute().close();
        } catch (Exception e) {
            Log.w(TAG, "played 上报失败: " + e.getMessage());
        }
    }

    // ── 点播/重取直链：后端实时生成一条新鲜直链 ───────────────────────────

    public String getPlayUrl(String filename, String songId) throws IOException {
        // 文件名必须转义。命名格式是「歌手-歌名-ID.mp3」，后端只过滤了
        // /\:*?"<>| 这几个字符，& # + 这些在 URL 查询串里有特殊含义的都会原样保留——
        // 遇到 "Simon & Garfunkel" 这种歌手名，不转义就会把查询串截断，
        // 后端收到的 filename 只剩半截，直接 500。
        Request req = new Request.Builder()
                .url(base() + "/play?filename=" + enc(filename) + "&id=" + enc(songId)).build();
        try (Response resp = AppOkHttpClient.get().newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return new JSONObject(resp.body().string()).optString("stream_url");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("getPlayUrl 失败: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";   // UTF-8 在任何 JVM 上都存在，走不到这里
        }
    }

    // ── 二维码登录 ────────────────────────────────────────────────────────

    /** 返回 {key, qr_image(base64)} */
    public JSONObject getQrCode() throws IOException {
        Request req = new Request.Builder().url(base() + "/auth/qr").build();
        try (Response resp = AppOkHttpClient.get().newCall(req).execute()) {
            return new JSONObject(resp.body().string());
        } catch (Exception e) {
            throw new IOException("获取二维码失败: " + e.getMessage());
        }
    }

    /** 返回扫码状态码：801=等待 802=已扫 803=成功 800=过期 */
    public int checkQrStatus(String key) throws IOException {
        Request req = new Request.Builder()
                .url(base() + "/auth/check?key=" + key).build();
        try (Response resp = AppOkHttpClient.get().newCall(req).execute()) {
            return new JSONObject(resp.body().string()).optInt("status", -1);
        } catch (Exception e) {
            throw new IOException("check 失败: " + e.getMessage());
        }
    }

    // ── 健康检查 ──────────────────────────────────────────────────────────

    public JSONObject health() throws IOException {
        Request req = new Request.Builder().url(base() + "/health").build();
        try (Response resp = AppOkHttpClient.get().newCall(req).execute()) {
            return new JSONObject(resp.body().string());
        } catch (Exception e) {
            throw new IOException("health 失败: " + e.getMessage());
        }
    }
}
