package com.fmplayer.network;

import android.os.Build;
import android.util.Log;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

public class AppOkHttpClient {
    private static final String TAG = "AppOkHttpClient";
    private static OkHttpClient instance;

    public static synchronized OkHttpClient get() {
        if (instance == null) instance = build();
        return instance;
    }

    private static OkHttpClient build() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(10,  TimeUnit.SECONDS)
                .readTimeout(20,     TimeUnit.SECONDS)  // 音频流式传输，不用等太久
                .writeTimeout(10,    TimeUnit.SECONDS);

        // Android 4.x（API <= 19）需要两个补丁才能正常访问现代 HTTPS 服务：
        //   1. 强制启用 TLS 1.2（系统默认不开）
        //   2. 放宽证书信任链（系统自带的十年前根证书库认不出移动云盘CDN用的新证书）
        //
        // 第2点的取舍：放宽证书校验意味着不再防中间人攻击，
        // 但这里的使用场景是车机播放自己账号下的音乐流，
        // 老设备的系统证书库又没法自行更新，两权相较这是唯一可行方案。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            try {
                X509TrustManager trustAll = new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                };

                SSLContext sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, new X509TrustManager[]{trustAll}, new SecureRandom());
                SSLSocketFactory tf = new TlsSocketFactory(sc.getSocketFactory());

                b.sslSocketFactory(tf, trustAll);
                b.hostnameVerifier((hostname, session) -> true);

                Log.i(TAG, "旧设备兼容补丁已启用：TLS1.2 + 宽松证书校验，API " + Build.VERSION.SDK_INT);
            } catch (Exception e) {
                Log.w(TAG, "TLS补丁注入失败: " + e.getMessage());
            }
        }
        return b.build();
    }
}
