package com.fmplayer.network;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Handshake;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 环境体检。每个版本只在首次启动时跑一次（开关见 ServerPreferences）。
 *
 * 这台车机是国产魔改 ROM（AC822X / build JDQ39E 是 Android 4.2.2 的 build 号，
 * 系统却自报 4.4.2），任何"标准 Android 应该如何"的假设都不能信，
 * 所以留一份设备/音频输出/存储/解码器的底账，出问题时才有得对比。
 *
 * 早期为了定位问题这里是"宁可多打不可漏打"：52 个解码器逐行列举、
 * 真的 new 出 MediaCodec 做解码自测、建 AudioTrack 试采样率。
 * 实车验证原生 MediaPlayer 一整轮播放全部正常之后，那些自测已经删掉了——
 * 它们加起来要跑一秒多，而结论在同一台机器上不会再变。
 */
public class Diagnostics {

    private static final String MP3     = "audio/mpeg";
    private static final String MP3_ALT = "audio/mp3";   // 厂商私有写法，MTK 用这个

    // ══ 入口一：不需要网络，Service 一起来就能跑 ═══════════════════════════

    public static void logLocalEnvironment(Context ctx) {
        line();
        DebugLog.log("体检", "===== 本机环境体检开始 =====");
        DebugLog.log("体检", "本次日志文件: " + DebugLog.path());
        logBuild();
        logAudioOutput(ctx);
        logStorage();
        logCodecList();
        DebugLog.log("体检", "===== 本机环境体检结束 =====");
        line();
    }

    // ══ 入口二：需要网络，拿到第一条直链之后跑（必须在子线程）═══════════════

    public static void logNetworkEnvironment(String backendUrl, String streamUrl) {
        line();
        DebugLog.log("体检", "===== 网络体检开始 =====");
        probeHttp("后端", backendUrl + "/health", false);
        probeHttp("直链", streamUrl, true);
        DebugLog.log("体检", "===== 网络体检结束 =====");
        line();
    }


    // ── 设备 ──────────────────────────────────────────────────────────────

    private static void logBuild() {
        DebugLog.log("设备", "MODEL=" + Build.MODEL + " PRODUCT=" + Build.PRODUCT
                + " DEVICE=" + Build.DEVICE + " BOARD=" + Build.BOARD);
        DebugLog.log("设备", "BRAND=" + Build.BRAND + " MANUFACTURER=" + Build.MANUFACTURER
                + " HARDWARE=" + Build.HARDWARE);
        // 系统自报的版本号和 build 号经常对不上，两个都记下来交叉比对
        DebugLog.log("设备", "RELEASE=" + Build.VERSION.RELEASE
                + " SDK_INT=" + Build.VERSION.SDK_INT
                + " INCREMENTAL=" + Build.VERSION.INCREMENTAL
                + " ID=" + Build.ID);
        DebugLog.log("设备", "FINGERPRINT=" + Build.FINGERPRINT);

        StringBuilder abi = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (String s : Build.SUPPORTED_ABIS) abi.append(s).append(' ');
        } else {
            abi.append(Build.CPU_ABI).append(' ').append(Build.CPU_ABI2);
        }
        DebugLog.log("设备", "ABI=" + abi.toString().trim());

        Runtime rt = Runtime.getRuntime();
        DebugLog.log("设备", "JVM内存 max=" + (rt.maxMemory() >> 20) + "MB"
                + " total=" + (rt.totalMemory() >> 20) + "MB"
                + " free=" + (rt.freeMemory() >> 20) + "MB"
                + " CPU核心=" + rt.availableProcessors());
    }

    // ── 音频输出链路 ──────────────────────────────────────────────────────
    //
    // 解码只是第一步。就算 MP3 解出来了，AudioTrack 建不起来或者音量是 0
    // 一样是"没声音"，而这两种情况在日志里长得完全不一样，必须分开确认。

    private static void logAudioOutput(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) { DebugLog.log("音频输出", "拿不到 AudioManager"); return; }

            DebugLog.log("音频输出", "音乐音量=" + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    + " 铃声模式=" + am.getRingerMode()
                    + " 音乐是否活跃=" + am.isMusicActive());
            DebugLog.log("音频输出", "有线耳机=" + am.isWiredHeadsetOn()
                    + " 蓝牙A2DP=" + am.isBluetoothA2dpOn()
                    + " 扬声器=" + am.isSpeakerphoneOn());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                String rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                String size = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                // 这两个值很关键：如果原生输出采样率是 48000，
                // 那 44.1kHz 的 MP3 在这台设备上要经过重采样，
                // 有些魔改 ROM 的重采样器本身就是坏的
                DebugLog.log("音频输出", "原生输出采样率=" + rate + " 每缓冲帧数=" + size);
            }
        } catch (Throwable t) {
            DebugLog.log("音频输出", "查询失败: " + t);
        }
    }

    // ── 存储 ──────────────────────────────────────────────────────────────

    private static void logStorage() {
        try {
            java.io.File ext = Environment.getExternalStorageDirectory();
            StatFs fs = new StatFs(ext.getAbsolutePath());
            long free = (long) fs.getAvailableBlocks() * fs.getBlockSize();
            DebugLog.log("存储", "日志目录=" + ext.getAbsolutePath()
                    + " 状态=" + Environment.getExternalStorageState()
                    + " 可写=" + ext.canWrite()
                    + " 剩余=" + (free >> 20) + "MB");
        } catch (Throwable t) {
            DebugLog.log("存储", "查询失败: " + t);
        }
    }

    // ── 解码器清单 ────────────────────────────────────────────────────────

    /**
     * 音频解码器概况。
     *
     * 原来这里把 52 个组件逐行打出来，占了日志三分之一还多。
     * 实车验证原生 MediaPlayer 一切正常之后，这份清单的用处只剩
     * "万一以后放不出声了，对比一下解码器有没有变"，
     * 所以压成一行汇总 + 一行 MP3 解码器名单就够了。
     */
    private static void logCodecList() {
        List<String> mp3Decoders = new ArrayList<>();
        try {
            int count = MediaCodecList.getCodecCount();
            int audioDecoders = 0;

            for (int i = 0; i < count; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                String[] types = info.getSupportedTypes();

                boolean isAudio = false;
                for (String t : types) {
                    if (t.toLowerCase().startsWith("audio/")) { isAudio = true; break; }
                }
                if (!isAudio || info.isEncoder()) continue;
                audioDecoders++;

                for (String t : types) {
                    // 除了标准的 audio/mpeg，还要收 audio/mp3 这种厂商私有写法。
                    // 车机上 OMX.mtk.audio.decoder.mp3 声明的就是 audio/mp3。
                    if (MP3.equalsIgnoreCase(t) || MP3_ALT.equalsIgnoreCase(t)) {
                        mp3Decoders.add(info.getName());
                    }
                }
            }
            DebugLog.log("解码器", "共 " + count + " 个组件，其中音频解码器 " + audioDecoders + " 个");
            DebugLog.log("解码器", "支持 MP3 的 " + mp3Decoders.size() + " 个: " + mp3Decoders);
        } catch (Throwable t) {
            DebugLog.log("解码器", "枚举失败: " + t);
        }
    }


    // ── 网络 + TLS + 音频流真实字节 ───────────────────────────────────────

    private static void probeHttp(String label, String url, boolean sniffAudio) {
        if (url == null || url.isEmpty()) {
            DebugLog.log("网络体检", label + ": URL 为空，跳过");
            return;
        }
        long start = System.currentTimeMillis();
        Request.Builder rb = new Request.Builder().url(url);
        // 只取前 64KB 就够验证格式了，不用把整首歌拉下来
        if (sniffAudio) rb.header("Range", "bytes=0-65535");

        try (Response resp = AppOkHttpClient.get().newCall(rb.build()).execute()) {
            long cost = System.currentTimeMillis() - start;
            DebugLog.log("网络体检", label + " HTTP " + resp.code() + " 耗时" + cost + "ms"
                    + " 协议=" + resp.protocol());

            Handshake hs = resp.handshake();
            if (hs != null) {
                // TLS 版本和加密套件——老设备最容易卡在这里，
                // 而 OkHttp 那套补丁到底生没生效，只有这行能证明
                DebugLog.log("网络体检", label + " TLS=" + hs.tlsVersion()
                        + " 加密套件=" + hs.cipherSuite());
            } else {
                DebugLog.log("网络体检", label + " 明文 HTTP（无 TLS 握手）");
            }

            DebugLog.log("网络体检", label + " Content-Type=" + resp.header("Content-Type")
                    + " Content-Length=" + resp.header("Content-Length")
                    + " Accept-Ranges=" + resp.header("Accept-Ranges")
                    + " Content-Range=" + resp.header("Content-Range"));

            if (sniffAudio && resp.body() != null) {
                byte[] head = readUpTo(resp.body().byteStream(), 8192);
                DebugLog.log("网络体检", label + " 实际读到 " + head.length + " 字节");
                analyzeMp3(head);
            }
        } catch (Throwable t) {
            long cost = System.currentTimeMillis() - start;
            DebugLog.log("网络体检", label + " 失败，耗时" + cost + "ms → "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static byte[] readUpTo(InputStream in, int max) throws Exception {
        byte[] buf = new byte[max];
        int off = 0;
        while (off < max) {
            int n = in.read(buf, off, max - off);
            if (n < 0) break;
            off += n;
        }
        byte[] out = new byte[off];
        System.arraycopy(buf, 0, out, 0, off);
        return out;
    }

    // ── MP3 帧头解析 ──────────────────────────────────────────────────────
    //
    // 这是整套体检里最要命的一项。播放器报的格式信息只说了
    // "audio/mpeg [2, 44100]"，但没说这是 MPEG-1 还是 MPEG-2.5、
    // 是不是 VBR、前面挂了多大一个 ID3v2 标签。
    // 老 OMX 解码器挑食恰恰就挑在这些地方：MPEG-2 LSF 不认、
    // VBR 的 Xing 头不认、ID3v2 里塞了几百 KB 封面图直接把它顶爆。
    // 后端要不要转码、转成什么样，全看这里解出来是什么。

    private static void analyzeMp3(byte[] b) {
        if (b.length < 16) { DebugLog.log("音频格式", "数据太少，无法分析"); return; }

        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(16, b.length); i++) hex.append(String.format("%02X ", b[i]));
        DebugLog.log("音频格式", "前16字节: " + hex.toString().trim());

        int offset = 0;
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            // ID3v2 头长度是 4 个 synchsafe 字节（每字节只用低 7 位）
            int size = ((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14)
                     | ((b[8] & 0x7F) << 7)  |  (b[9] & 0x7F);
            offset = 10 + size;
            DebugLog.log("音频格式", "有 ID3v2." + (b[3] & 0xFF) + " 标签，长度 "
                    + size + " 字节，音频数据从 " + offset + " 开始"
                    + (size > 65536 ? "  ← 标签超大，很可能内嵌了封面图，老解码器容易在这里翻车" : ""));
        } else {
            DebugLog.log("音频格式", "无 ID3v2 标签");
        }

        // 从 offset 开始找第一个同步字 11111111 111xxxxx
        int i = offset;
        while (i + 4 < b.length) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xE0) == 0xE0) break;
            i++;
        }
        if (i + 4 >= b.length) {
            DebugLog.log("音频格式", "在前 " + b.length + " 字节里没找到 MP3 同步帧头"
                    + "  ← 这说明拿到的根本不是 MP3（可能是错误页面或别的容器格式）");
            return;
        }
        if (i != offset) DebugLog.log("音频格式", "同步帧头在偏移 " + i + "（跳过了 " + (i - offset) + " 字节垃圾）");

        int h1 = b[i + 1] & 0xFF, h2 = b[i + 2] & 0xFF, h3 = b[i + 3] & 0xFF;

        int verBits = (h1 >> 3) & 0x03;
        String version = verBits == 3 ? "MPEG-1" : verBits == 2 ? "MPEG-2" : verBits == 0 ? "MPEG-2.5" : "保留(非法)";
        int layerBits = (h1 >> 1) & 0x03;
        String layer = layerBits == 1 ? "Layer III" : layerBits == 2 ? "Layer II" : layerBits == 3 ? "Layer I" : "保留(非法)";

        int[] rateV1 = {44100, 48000, 32000};
        int[] rateV2 = {22050, 24000, 16000};
        int[] rateV25 = {11025, 12000, 8000};
        int rateIdx = (h2 >> 2) & 0x03;
        int sampleRate = rateIdx == 3 ? -1
                : verBits == 3 ? rateV1[rateIdx] : verBits == 2 ? rateV2[rateIdx] : rateV25[rateIdx];

        int[] brV1L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1};
        int[] brV2L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1};
        int brIdx = (h2 >> 4) & 0x0F;
        int bitrate = verBits == 3 ? brV1L3[brIdx] : brV2L3[brIdx];

        int modeBits = (h3 >> 6) & 0x03;
        String mode = modeBits == 0 ? "立体声" : modeBits == 1 ? "联合立体声"
                : modeBits == 2 ? "双声道" : "单声道";

        DebugLog.log("音频格式", version + " " + layer
                + " 采样率=" + sampleRate + "Hz"
                + " 比特率=" + bitrate + "kbps"
                + " 声道=" + mode
                + " CRC=" + (((h1 & 0x01) == 0) ? "有" : "无")
                + " 填充=" + (((h2 >> 1) & 0x01))
                + (verBits != 3 ? "  ← 不是 MPEG-1，老解码器对 MPEG-2/2.5 支持普遍很差" : ""));

        // Xing/Info（VBR）或 VBRI 头就紧跟在第一帧头后面的固定偏移里
        for (int probe : new int[]{i + 4 + 17, i + 4 + 32, i + 4 + 9, i + 4 + 21}) {
            if (probe + 4 <= b.length) {
                String tag = new String(b, probe, 4);
                if ("Xing".equals(tag) || "Info".equals(tag) || "VBRI".equals(tag)) {
                    DebugLog.log("音频格式", "发现 " + tag + " 头"
                            + ("Xing".equals(tag) ? "（VBR 变比特率）"
                             : "Info".equals(tag) ? "（CBR 恒定比特率）" : "（VBR）"));
                    break;
                }
            }
        }
    }

    private static void line() {
        DebugLog.log("体检", "--------------------------------------------------");
    }

}
