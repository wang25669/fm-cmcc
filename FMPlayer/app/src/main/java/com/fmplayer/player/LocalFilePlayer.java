package com.fmplayer.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.fmplayer.network.AppOkHttpClient;
import com.fmplayer.network.DebugLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 主播放引擎：先把整首歌下载到本地缓存，再交给系统原生 {@link MediaPlayer} 播。
 *
 * ── 为什么不流式播放 ──────────────────────────────────────────────────────
 *
 * 两个理由，第二个才是决定性的：
 *
 * 1. 用不上。这个播放器没有进度条拖动，只有"上一首/下一首"。
 *    流式唯一的好处是秒开，而秒开的代价（见下）在这台设备上太大了。
 *
 * 2. 只用系统原生 MediaPlayer，没有备用引擎。
 *    MediaPlayer 走的是系统原生 stagefright 管线——那是厂商出厂前
 *    自己测过的路径，车机能从 U 盘放 MP3 就说明这条路是通的，实车也验证过了。
 *
 *    曾经挂过一条 ExoPlayer 退路，在这台车机上是坏的：它走 MediaCodec +
 *    AudioTrack，对厂商 OMX 组件有一整套标准契约假设，而这台魔改 ROM 的
 *    OMX.mtk.audio.decoder.mpeg 不发 INFO_OUTPUT_FORMAT_CHANGED，
 *    于是 audioSink.configure() 从没被调用过，直接 NPE 崩掉。
 *    那条退路已经整体删掉——留着它只会在出问题时多一层要排查的东西。
 *
 * ── 为什么不让 MediaPlayer 直接吃 URL ─────────────────────────────────────
 *
 * 直链是 HTTPS（TLS 1.2）。API 17 的 MediaPlayer 用系统原生 HTTP 栈，
 * 那套栈在 4.x 上连 SNI 和 TLS 1.2 都未必支持，而项目里针对老设备的
 * TLS 补丁全在 OkHttp 那一侧（见 AppOkHttpClient），MediaPlayer 用不到。
 * 用 OkHttp 下载再喂本地路径，顺带把"网络"和"解码"两个变量彻底分开，
 * 出问题时日志能直接指认是哪一头。
 *
 * ── 等待时间怎么办 ────────────────────────────────────────────────────────
 *
 * 整首下载确实比流式慢。靠两件事补回来，见 {@link SongCache}：
 *   · 听过的歌留在缓存里 → 上一首/重听是瞬间的，零网络
 *   · 当前歌一开播就后台预下载下一首 → 按下一首时文件通常已经在本地了
 * 真正要等的只剩"第一首"和"预下载还没跑完就连按下一首"。
 */
public class LocalFilePlayer {

    public interface Listener {
        /** 已经开始出声 */
        void onStarted();
        /** 整首播完 */
        void onCompleted();
        /** 正在下载，还没开始播（用来更新界面提示）*/
        void onBuffering();

        /**
         * 文件根本没拿到（HTTP 失败、连接断了、写不进缓存）。
         * 直链是有时效的，这种失败重新取一次直链很可能就好了，**不该跳歌**。
         */
        void onDownloadFailed(String reason);

        /**
         * 文件完整地躺在本地，但系统原生管线放不出来。
         * 换个直链没有任何意义——问题在这台机器或这个文件本身。
         */
        void onPlaybackFailed(String reason);
    }

    private final Context   ctx;
    private final Listener  listener;
    private final SongCache cache;
    private final Handler   main = new Handler(Looper.getMainLooper());

    /** 当前要播的那一首的下载，单线程串行 */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 预下载走独立线程，绝不能因为排在当前歌后面而被卡住 */
    private final ExecutorService prefetchIo = Executors.newSingleThreadExecutor();

    /**
     * 正在下载中的歌曲。key 是 songId，value 是那次下载的独占锁。
     *
     * 为什么需要它：预下载和前台播放是两个独立线程池，完全可能同时下载同一首歌。
     * 用户按下一首的时机稍微早于预下载完成时就会撞上——两边都往同一个
     * songId.part 写，谁先完成谁把文件 rename 走，另一边再去看 .part
     * 已经不见了，于是抛出"下载下来是 0 字节"。日志里这个假故障出现过 5 次
     * （14:46 / 14:51 / 14:52 / 14:53 / 14:54），每次都伴随一首歌被白下载两遍。
     *
     * 现在同一首歌的第二个请求会在这把锁上等第一个的结果，等到了直接用缓存文件。
     */
    private static final Map<String, Object> INFLIGHT = new HashMap<>();

    private MediaPlayer mp;
    private boolean playWhenReady = true;
    private boolean prepared      = false;

    /**
     * 和 MusicService 里的 playGeneration 同一个思路：下载是异步的，
     * 下载期间用户完全可能已经连按了几次下一首，
     * 回调回来必须先确认自己还是"当前那一首"，否则会把正在播的歌打断。
     */
    private volatile int generation = 0;

    /** 正在预下载的歌 id，避免同一首被重复预下载 */
    private volatile String prefetchingId;

    public LocalFilePlayer(Context ctx, Listener listener) {
        this.ctx      = ctx.getApplicationContext();
        this.listener = listener;
        this.cache    = new SongCache(this.ctx);
    }

    // ── 播放 ────────────────────────────────────────────────────────────────

    public void play(final String songId, final String url) {
        final int myGen = ++generation;
        prepared = false;
        playWhenReady = true;
        releasePlayer();

        // 缓存命中：直接播，一个字节都不用下。
        // 这条路径就是"上一首"和"重听"之所以能瞬间响应的原因。
        if (cache.has(songId)) {
            File f = cache.fileFor(songId);
            cache.touch(songId);
            DebugLog.log("播放", "缓存命中 " + f.length() + " 字节，直接播放");
            startLocal(myGen, songId, f);
            return;
        }

        listener.onBuffering();
        DebugLog.log("播放", "缓存未命中，开始下载: " + shortUrl(url));
        final long startMs = System.currentTimeMillis();

        io.submit(() -> {
            File file;
            try {
                file = download(songId, url);
            } catch (Throwable t) {
                DebugLog.log("播放", "下载失败，耗时"
                        + (System.currentTimeMillis() - startMs) + "ms");
                DebugLog.logException("播放-下载异常", t);
                failDownload(myGen, "下载失败: " + t.getMessage());
                return;
            }

            long ms = System.currentTimeMillis() - startMs;
            DebugLog.log("播放", "下载完成 " + file.length() + " 字节，耗时" + ms + "ms"
                    + "，平均 " + (ms > 0 ? (file.length() / ms) : 0) + " KB/s");
            cache.trim(songId);

            final File f = file;
            main.post(() -> startLocal(myGen, songId, f));
        });
    }

    /**
     * 后台预下载下一首，不影响当前播放。
     *
     * 这是"下载后播放"能用得舒服的关键：用户按下一首的时候，
     * 文件多半已经躺在缓存里了，体验和流式秒开没有区别。
     */
    public void prefetch(final String songId, final String url) {
        if (songId == null || url == null || url.isEmpty()) return;
        if (cache.has(songId)) return;                       // 已经有了
        if (songId.equals(prefetchingId)) return;            // 正在下，别重复提交
        prefetchingId = songId;

        prefetchIo.submit(() -> {
            long t0 = System.currentTimeMillis();
            try {
                if (cache.has(songId)) return;               // 提交之后到执行之前可能已经被下好了
                download(songId, url);
                DebugLog.log("预下载", "下一首已就绪，耗时"
                        + (System.currentTimeMillis() - t0) + "ms");
                cache.trim(null);
            } catch (Throwable t) {
                // 预下载失败完全无害——真播到那首时会再下一次。
                // 记一笔就够了，不打扰用户。
                DebugLog.log("预下载", "失败（不影响播放，真播到时会重试）: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (songId.equals(prefetchingId)) prefetchingId = null;
            }
        });
    }

    // ── 播放控制 ────────────────────────────────────────────────────────────

    public void setPlayWhenReady(boolean play) {
        playWhenReady = play;
        if (mp == null || !prepared) return;
        try {
            if (play) mp.start(); else mp.pause();
        } catch (Throwable t) {
            DebugLog.log("播放", "暂停/恢复失败: " + t);
        }
    }

    public boolean getPlayWhenReady() { return playWhenReady; }

    public boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    public long getDuration() {
        try {
            return (mp != null && prepared) ? Math.max(0, mp.getDuration()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public long getCurrentPosition() {
        try {
            return (mp != null && prepared) ? Math.max(0, mp.getCurrentPosition()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public void release() {
        generation++;          // 让所有在飞的下载回调作废
        releasePlayer();
        io.shutdownNow();
        prefetchIo.shutdownNow();
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /**
     * 下载到 .part 临时文件，完整落盘后再改名成正式文件。
     * 中途断网/断电留下的半截文件不会被当成有效缓存——
     * 那种文件喂给 MediaPlayer 只会得到一个莫名其妙的错误码。
     *
     * 同一首歌同时只会有一次真实下载在跑，见 {@link #INFLIGHT}。
     * 后到的那个线程在锁里等，醒来时文件通常已经在缓存里了，直接用。
     */
    private File download(String songId, String url) throws Exception {
        Object lock;
        synchronized (INFLIGHT) {
            lock = INFLIGHT.get(songId);
            if (lock == null) {
                lock = new Object();
                INFLIGHT.put(songId, lock);
            }
        }

        // 锁住这一首。第二个线程会阻塞在这里，直到第一个下完并 rename。
        synchronized (lock) {
            try {
                // 醒来先看一眼：多半是别人已经替我们下好了，那就一个字节都不用再拉。
                if (cache.has(songId)) {
                    DebugLog.log("下载", "同一首已由另一个线程下好，直接复用");
                    return cache.fileFor(songId);
                }
                return doDownload(songId, url);
            } finally {
                synchronized (INFLIGHT) {
                    INFLIGHT.remove(songId);
                }
            }
        }
    }

    /** 真正的下载动作。调用方必须已经持有这首歌的 INFLIGHT 锁。 */
    private File doDownload(String songId, String url) throws Exception {
        File target = cache.fileFor(songId);
        File temp   = cache.tempFor(songId);

        Request req = new Request.Builder().url(url).build();
        boolean complete = false;
        try (Response resp = AppOkHttpClient.get().newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("HTTP " + resp.code());
            }
            try (InputStream in = resp.body().byteStream();
                 OutputStream os = new FileOutputStream(temp)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                // 判 != -1 而不是 > 0：read 合法地返回 0 时用 > 0 会提前跳出循环，
                // 留下一个截断的文件却当成下载成功。
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            }
            complete = true;
        } finally {
            // 传输中途断网/断电时半截 .part 会留在盘上。它虽然能被 trim() 当普通
            // 文件删掉，但在那之前一直占着 500MB 的缓存预算，等于拿垃圾挤掉
            // 真正能命中的歌。这里直接清掉，不让它有机会积累。
            if (!complete) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
        if (temp.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            // 走到这里说明服务器真的回了个空 body（直链过期常见的表现）。
            // 以前这里还会被"同一首歌被下载两遍"的竞态误触发，那条路已经堵掉了。
            throw new IllegalStateException("下载下来是 0 字节（服务器返回空内容）");
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();                       // rename 在有些实现上不覆盖已存在的文件
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IllegalStateException("缓存文件改名失败");
        }
        return target;
    }

    /** 必须在主线程调用：MediaPlayer 的回调靠创建它的那个线程的 Looper 派发 */
    private void startLocal(int myGen, String songId, File file) {
        if (myGen != generation) {
            DebugLog.log("播放", "下载结果已过期（用户已切歌），丢弃");
            return;
        }
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(file.getAbsolutePath());

            mp.setOnPreparedListener(player -> {
                if (myGen != generation) return;
                prepared = true;
                DebugLog.log("播放", "MediaPlayer 就绪，时长=" + player.getDuration() + "ms");
                if (playWhenReady) {
                    player.start();
                    listener.onStarted();
                }
            });
            mp.setOnCompletionListener(player -> {
                if (myGen != generation) return;
                listener.onCompleted();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (myGen != generation) return true;
                // what/extra 是 MediaPlayer 唯一的错误信息来源，原样记下来。
                // what:  MEDIA_ERROR_UNKNOWN=1  SERVER_DIED=100
                // extra: UNSUPPORTED=-1010  MALFORMED=-1007  IO=-1004  TIMED_OUT=-110
                DebugLog.log("播放", "MediaPlayer 报错 what=" + what + " extra=" + extra
                        + " 文件=" + file.length() + "字节"
                        + " ← 系统原生管线也放不了这个文件");
                // 这个文件很可能是坏的，删掉，别让它一直命中缓存
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                failPlayback(myGen, "播放失败(what=" + what + " extra=" + extra + ")");
                return true;
            });

            mp.prepareAsync();   // 别在主线程上阻塞几百毫秒
        } catch (Throwable t) {
            DebugLog.log("播放", "创建 MediaPlayer 失败");
            DebugLog.logException("播放-创建异常", t);
            failPlayback(myGen, "播放器初始化失败: " + t.getMessage());
        }
    }

    private void failDownload(int myGen, String reason) {
        main.post(() -> {
            if (myGen != generation) return;
            listener.onDownloadFailed(reason);
        });
    }

    private void failPlayback(int myGen, String reason) {
        main.post(() -> {
            if (myGen != generation) return;
            listener.onPlaybackFailed(reason);
        });
    }

    private void releasePlayer() {
        prepared = false;
        if (mp == null) return;
        MediaPlayer old = mp;
        mp = null;
        try { old.reset();   } catch (Throwable ignored) {}
        try { old.release(); } catch (Throwable ignored) {}
    }

    private static String shortUrl(String url) {
        if (url == null) return "null";
        return url.length() > 60 ? url.substring(0, 60) : url;
    }
}
