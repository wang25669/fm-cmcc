package com.fmplayer.preferences;

import android.content.Context;
import android.content.SharedPreferences;

public class ServerPreferences {
    private static final String NAME = "server_config";
    private static ServerPreferences instance;
    private final SharedPreferences p;

    public static ServerPreferences get(Context ctx) {
        if (instance == null) instance = new ServerPreferences(ctx.getApplicationContext());
        return instance;
    }
    private ServerPreferences(Context ctx) {
        p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // 默认必须留空。
    // 之前默认写死成局域网 IP（http://192.168.123.178:3000），配合下面
    // isConfigured() 的"非空即已配置"判断，结果是首次启动永远跳不出设置页——
    // App 直接拿着一个车机根本路由不到的地址去连，10 秒超时后每 5 秒重试一次，
    // 用户只看得到"加载失败"，完全不知道该去哪填地址。
    // 留空之后这条判断才真正成立：没填过 = 没配置过 = 跳设置页。
    public String getBackendUrl() { return p.getString("backend_url", ""); }

    public void setBackendUrl(String v) { p.edit().putString("backend_url", v).apply(); }

    public boolean isConfigured() {
        // 只要填了后端地址就算配置过，网易云登录状态以服务端 /health 为准
        return !getBackendUrl().trim().isEmpty();
    }

    // ── 环境体检的一次性开关 ────────────────────────────────────────────────
    //
    // 体检要枚举全部编解码器、查音频输出属性、读存储状态。这些结果在同一台
    // 车机同一个版本上不会变，每次开 App 都跑纯属浪费启动时间。
    //
    // 记的是版本号而不是布尔值：装了新版本说明播放逻辑动过了，
    // 那就该重新留一份环境记录，免得出问题时手上只有旧版本的日志。

    private static final String KEY_DIAG_VERSION = "diag_done_version";

    public boolean shouldRunDiagnostics(Context ctx) {
        return p.getInt(KEY_DIAG_VERSION, -1) != versionCode(ctx);
    }

    public void markDiagnosticsDone(Context ctx) {
        p.edit().putInt(KEY_DIAG_VERSION, versionCode(ctx)).apply();
    }

    private static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            // 理论上取不到自己的包信息是不可能的。真发生了就当作"没跑过"，
            // 大不了多跑一次体检，总比因为这个崩掉强。
            return -1;
        }
    }
}
