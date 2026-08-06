package com.fmplayer.network;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量调试日志，写入 SD 卡根目录的 fmplayer_debug.txt。
 * 用于排查播放失败但又不崩溃、拿不到 adb logcat 的情况。
 * 车机上用文件管理器打开，或拔卡用电脑看。
 */
public class DebugLog {

    private static final String TAG      = "FMPlayerDebug";
    private static final String FILENAME = "fmplayer_debug.txt";

    /**
     * 日志文件的落地位置，第一次写的时候确定下来。
     *
     * 原来无条件往 SD 卡根目录写。这在车机（API 17/19，装机即授权）上没问题，
     * 但在 targetSdk 35 的现代手机上 WRITE_EXTERNAL_STORAGE 拿不到，
     * 写入静默失败，结果就是手机上永远看不到任何日志。
     * 现在改成"根目录写不了就退到应用自己的外部目录"，
     * 保证任何设备上都一定有一份日志留下来。
     */
    private static volatile File target;
    private static volatile Context appCtx;

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    private static File target() {
        File t = target;
        if (t != null) return t;
        synchronized (DebugLog.class) {
            if (target != null) return target;

            File root = new File(Environment.getExternalStorageDirectory(), FILENAME);
            if (canWrite(root)) {
                target = root;
            } else if (appCtx != null) {
                File dir = appCtx.getExternalFilesDir(null);
                if (dir == null) dir = appCtx.getFilesDir();
                target = new File(dir, FILENAME);
            } else {
                target = root;   // 没有 Context 可用，只能硬试根目录
            }
            Log.i(TAG, "日志文件位置: " + target.getAbsolutePath());
            return target;
        }
    }

    private static boolean canWrite(File f) {
        try (FileWriter fw = new FileWriter(f, true)) {
            fw.write("");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 日志文件的绝对路径，用于在界面上告诉用户去哪儿找 */
    public static String path() { return target().getAbsolutePath(); }

    public static void log(String tag, String msg) {
        Log.d(TAG, tag + ": " + msg);
        try {
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            try (FileWriter fw = new FileWriter(target(), true)) {
                fw.write("[" + ts + "] " + tag + ": " + msg + "\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "写调试日志失败: " + e.getMessage());
        }
    }

    public static void logException(String tag, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage());
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            sb.append(" ← 原因: ").append(cause.getClass().getName())
              .append(": ").append(cause.getMessage());
            cause = cause.getCause();
            depth++;
        }
        log(tag, sb.toString());
    }

}
