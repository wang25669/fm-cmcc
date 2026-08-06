package com.fmplayer;

import android.app.Application;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class App extends Application {

    private static final String TAG = "FMPlayerCrash";

    @Override
    public void onCreate() {
        super.onCreate();
        // 先把日志系统初始化好，后面 MusicService 的体检才有地方落盘
        com.fmplayer.network.DebugLog.init(this);
        installCrashHandler();
    }

    /**
     * 全局未捕获异常处理器。
     * 崩溃时把完整堆栈写入 SD 卡根目录的 fmplayer_crash.txt，
     * 车机自带文件管理器或拔卡用电脑都能直接打开查看。
     * 排查完这个问题后可以把这段移除，不影响正式使用。
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));

                String timestamp = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

                File dir = Environment.getExternalStorageDirectory();
                File logFile = new File(dir, "fmplayer_crash.txt");

                try (FileWriter fw = new FileWriter(logFile, true)) {
                    fw.write("===== 崩溃时间: " + timestamp + " =====\n");
                    fw.write("设备: " + android.os.Build.MANUFACTURER + " "
                            + android.os.Build.MODEL + "\n");
                    fw.write("系统: Android " + android.os.Build.VERSION.RELEASE
                            + " (API " + android.os.Build.VERSION.SDK_INT + ")\n");
                    fw.write(sw.toString());
                    fw.write("\n\n");
                }

                Log.e(TAG, "崩溃日志已写入: " + logFile.getAbsolutePath());
            } catch (Exception loggingError) {
                Log.e(TAG, "写入崩溃日志失败", loggingError);
            }

            // 仍然交给系统默认处理器，保持原有的"应用已停止"行为
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }
}

