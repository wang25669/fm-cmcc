package com.fmplayer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import com.fmplayer.MainActivity;
import com.fmplayer.R;
import com.fmplayer.model.SongInfo;
import com.fmplayer.network.BackendClient;
import com.fmplayer.network.DebugLog;
import com.fmplayer.network.Diagnostics;
import com.fmplayer.player.LocalFilePlayer;
import com.fmplayer.preferences.ServerPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {

    private static final int    NOTIF_ID   = 1001;
    private static final String CHANNEL_ID = "fm_playback";

    public static final String ACTION_TOGGLE_PLAY = "com.fmplayer.TOGGLE_PLAY";
    public static final String ACTION_NEXT        = "com.fmplayer.NEXT";
    public static final String ACTION_PREV        = "com.fmplayer.PREV";
    public static final String ACTION_STOP        = "com.fmplayer.STOP";
    public static final String ACTION_PLAY_HISTORY= "com.fmplayer.PLAY_HISTORY";
    public static final String EXTRA_HISTORY_IDX  = "history_idx";

    public static final String BROADCAST_STATE  = "com.fmplayer.STATE_CHANGED";
    public static final String EXTRA_TITLE      = "title";
    public static final String EXTRA_ARTIST     = "artist";
    public static final String EXTRA_IS_PLAYING = "is_playing";
    public static final String EXTRA_DURATION   = "duration_ms";
    public static final String EXTRA_POSITION   = "position_ms";
    public static final String EXTRA_STATUS     = "status_msg";

    /**
     * 本次App会话已播放过的歌曲，按播放顺序排列，纯内存维护。
     * 上一首/下一首在这个列表范围内移动是零网络开销的——
     * 这些歌都已经真实播放过，直链早就拿到手了。
     * 只有"下一首"超出这个列表末尾（即将播放一首全新的歌）才需要问后端要。
     */
    private static final List<SongInfo> SESSION_HISTORY =
            Collections.synchronizedList(new ArrayList<>());

    /** 当前播放的是 SESSION_HISTORY 中的第几首（下标）*/
    private static volatile int currentIndex = -1;

    public static List<SongInfo> getSessionHistorySnapshot() {
        synchronized (SESSION_HISTORY) {
            return new ArrayList<>(SESSION_HISTORY);
        }
    }

    private MediaSessionCompat mediaSession;
    private SongInfo           currentSong;
    private int                playRetryCount = 0;
    private static final int   MAX_PLAY_RETRY = 3;

    /**
     * 向后端要新歌的请求正在飞。
     * 预取和"用户按下一首"都会走到这里，必须互斥——/next 在后端是一次
     * 真实的"从网易云下载整首 + 上传移动云盘"，重复发等于白烧两遍带宽。
     */
    private volatile boolean fetchInFlight = false;

    /**
     * 预取的请求还没回来，用户已经按了下一首。
     * 到货时直接播，而不是再发一次 /next。
     */
    private volatile boolean pendingPlay = false;

    /**
     * 连续多少首歌栽在"本机解码失败"上。
     *
     * 解码器坏掉的时候每一首歌都会失败，而"跳下一首"在这个项目里不是免费的：
     * 后端 /next 要去网易云下载整首歌、再整首上传移动云盘，一首就是几秒到几十秒
     * 的 NAS 带宽和上行流量。让它无限跳下去等于拿家里的 NAS 空转，
     * 还会把私人FM的推荐流白白消耗掉。连续 3 首都解不出来就认定是设备问题，
     * 停下来把结论显示给用户，等人工干预。
     */
    private int                decoderFailStreak = 0;
    private static final int   MAX_DECODER_FAIL_STREAK = 3;

    private final Handler      handler     = new Handler();
    private final ExecutorService executor  = Executors.newSingleThreadExecutor();

    /**
     * 播放代次计数器。每次真正切换到一首新歌（playLocal被调用）就自增一次。
     * 异步重试（getPlayUrl之类）发起时会记下当时的代次，
     * 拿到结果准备应用到播放器之前，先检查代次有没有变过——
     * 如果这期间用户已经手动切了歌，说明这次重试的结果已经过时，
     * 直接丢弃，不能覆盖当前正在播的新歌。
     * 之前没有这个机制时，日志里出现过"独角戏的过期重试结果几十秒后
     * 冒出来把正在播的千纸鹤打断"这种混乱情况。
     */
    private volatile int playGeneration = 0;

    /**
     * 本次启动要不要跑体检。onCreate 里一次性判定，本机体检和网络体检共用一个开关。
     * 两半都跑完了才 markDiagnosticsDone——网络体检要等第一条真实直链，
     * 万一这次启动压根没取到歌，下次开机应该重来一遍，而不是留一份残缺记录。
     */
    private boolean runDiagThisLaunch = false;

    /**
     * 唯一的播放引擎：先整首下载到本地缓存，再交给系统原生 MediaPlayer。
     * 见 {@link LocalFilePlayer} 的注释——原生 stagefright 管线是厂商
     * 出厂前测过的路径，实车验证一整轮播放全部正常。
     */
    private LocalFilePlayer localPlayer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaSession();
        startForeground(NOTIF_ID, buildNotification());

        // 体检每个版本只跑一次。它要枚举全部编解码器、查音频输出属性、读存储状态，
        // 结果在同一台车机上不会变，每次开 App 都跑纯属浪费启动时间。
        // 装了新版本会自动再跑一遍——改了播放逻辑之后手上得有一份新的环境记录。
        runDiagThisLaunch = ServerPreferences.get(this).shouldRunDiagnostics(this);
        if (runDiagThisLaunch) {
            executor.submit(() -> Diagnostics.logLocalEnvironment(MusicService.this));
        }

        // 没配置后端地址就什么都别做，等用户在设置页填完再 startService。
        // 以前这里无条件 fetchAndPlayNew()，地址是空的也照发请求，
        // 结果是每 5 秒失败重试一次的死循环。
        if (ServerPreferences.get(this).isConfigured()) {
            fetchNew(true);
        } else {
            broadcast("请先在设置里填写后端地址");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) MediaButtonReceiver.handleIntent(mediaSession, intent);
        if (intent != null && intent.getAction() != null) {
            // 用户亲手按的键：把解码连败计数清零，让上面那个"停止自动跳歌"的
            // 保护解除——自动跳歌要拦，人想再试一次得随时能试。
            switch (intent.getAction()) {
                case ACTION_TOGGLE_PLAY:  togglePlay(); break;
                case ACTION_NEXT:         decoderFailStreak = 0; goNext(); break;
                case ACTION_PREV:         decoderFailStreak = 0; goPrev(); break;
                case ACTION_STOP:         quit(); break;
                case ACTION_PLAY_HISTORY:
                    decoderFailStreak = 0;
                    jumpTo(intent.getIntExtra(EXTRA_HISTORY_IDX, -1)); break;
            }
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (localPlayer != null) { localPlayer.release(); localPlayer = null; }
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        super.onDestroy();
    }

    // ── 主播放通道：下载到本地 + 系统原生 MediaPlayer ────────────────────────

    private LocalFilePlayer localPlayer() {
        if (localPlayer == null) {
            localPlayer = new LocalFilePlayer(this, new LocalFilePlayer.Listener() {

                @Override public void onBuffering() {
                    broadcast("正在下载...");
                }

                @Override public void onStarted() {
                    decoderFailStreak = 0;   // 真的出声了
                    playRetryCount    = 0;
                    broadcast("");
                    updatePlaybackState(true);
                    updateNotification();
                    startProgress();
                    // 当前这首已经在放了，下载通道空出来了，趁现在把下一首备好。
                    // 这一步是"下载完再播"能用得舒服的关键：
                    // 按下一首时文件多半已经在本地，体验和流式秒开没区别。
                    prepareNext();
                }

                @Override public void onCompleted() {
                    stopProgress();
                    onSongEnded();
                }

                /**
                 * 文件没拿到。直链是有时效的，这是它过期时唯一会表现出来的地方，
                 * 所以这里重新问后端要一条新直链再试，而不是跳歌——
                 * 跳歌在这个项目里要后端真去网易云下一整首再传移动云盘，很贵。
                 */
                @Override public void onDownloadFailed(String reason) {
                    stopProgress();
                    if (currentSong == null) {
                        handler.postDelayed(MusicService.this::goNext, 5000);
                        return;
                    }
                    playRetryCount++;
                    if (playRetryCount > MAX_PLAY_RETRY) {
                        DebugLog.log("下载失败", "重试" + MAX_PLAY_RETRY + "次仍拿不到文件，跳到下一首: "
                                + currentSong.title);
                        playRetryCount = 0;
                        broadcast("多次重试失败，跳到下一首");
                        handler.postDelayed(MusicService.this::goNext, 1000);
                        return;
                    }
                    DebugLog.log("下载失败", reason + "，重新取直链重试("
                            + playRetryCount + "/" + MAX_PLAY_RETRY + ")");
                    broadcast("链接可能已过期，重新获取(" + playRetryCount + "/" + MAX_PLAY_RETRY + ")...");
                    refreshUrlAndRetry();
                }

                /**
                 * 文件是完整的，但系统原生管线放不出来。
                 * 换直链没意义——问题在这台机器或这个文件本身，只能跳过这一首。
                 */
                @Override public void onPlaybackFailed(String reason) {
                    stopProgress();
                    DebugLog.log("播放失败", reason + " ← 文件已完整下载，是本机解码/输出的问题");
                    playbackFailed(reason);
                }
            });
        }
        return localPlayer;
    }

    /**
     * 重新向后端要一条新直链，然后用当前引擎重播这一首。
     * 代次检查的作用见 {@link #playGeneration}：拿到结果时用户可能早就切歌了。
     */
    private void refreshUrlAndRetry() {
        final int    myGeneration = playGeneration;
        final String fname        = currentSong.filename;
        final String sid          = currentSong.id;
        final long   startMs      = System.currentTimeMillis();

        executor.submit(() -> {
            try {
                String freshUrl = BackendClient.get(MusicService.this).getPlayUrl(fname, sid);
                long costMs = System.currentTimeMillis() - startMs;
                DebugLog.log("getPlayUrl", "成功，耗时" + costMs + "ms，url前缀=" +
                        (freshUrl != null && freshUrl.length() > 60 ? freshUrl.substring(0, 60) : freshUrl));

                if (myGeneration != playGeneration) {
                    DebugLog.log("getPlayUrl", "结果已过期(代次" + myGeneration
                            + "≠当前" + playGeneration + ")，丢弃");
                    return;
                }
                currentSong.streamUrl = freshUrl;
                handler.post(() -> {
                    if (myGeneration != playGeneration) return;   // 双重检查，UI线程里再确认一次
                    if (currentSong != null) localPlayer().play(currentSong.id, currentSong.streamUrl);
                });
            } catch (Exception ex) {
                long costMs = System.currentTimeMillis() - startMs;
                DebugLog.log("getPlayUrl", "失败，耗时" + costMs + "ms");
                DebugLog.logException("getPlayUrl-异常", ex);

                if (myGeneration != playGeneration) {
                    DebugLog.log("getPlayUrl", "重试回调已过期，不再触发下一步重试");
                    return;
                }
                broadcast("重试失败(" + playRetryCount + "/" + MAX_PLAY_RETRY + ")");
                handler.postDelayed(() -> {
                    if (myGeneration != playGeneration) return;
                    if (currentSong != null) localPlayer().play(currentSong.id, currentSong.streamUrl);
                }, 2000);
            }
        });
    }

    /**
     * 文件完整但放不出来，这首歌在这台机器上就是放不了。
     *
     * 跳歌不是免费的（后端要真去网易云下载整首、再整首传移动云盘），
     * 所以连续失败到一定次数就停下来等人工干预，别拿家里的 NAS 空转，
     * 也别把私人FM的推荐流白白消耗掉。
     */
    private void playbackFailed(String reason) {
        decoderFailStreak++;
        DebugLog.log("播放失败", reason + "（连续第" + decoderFailStreak + "首）");

        if (decoderFailStreak >= MAX_DECODER_FAIL_STREAK) {
            DebugLog.log("播放失败", "连续" + MAX_DECODER_FAIL_STREAK
                    + "首文件完整却放不出来，停止自动跳歌");
            broadcast("本机无法播放音频，已停止。日志: " + DebugLog.path());
            return;
        }
        broadcast("播放失败，跳到下一首");
        handler.postDelayed(MusicService.this::goNext, 1500);
    }

    // ── 播放器状态查询 ──────────────────────────────────────────────────────

    private boolean enginePlaying() {
        return localPlayer != null && localPlayer.isPlaying();
    }

    private long engineDuration() {
        return localPlayer != null ? localPlayer.getDuration() : 0;
    }

    private long enginePosition() {
        return localPlayer != null ? localPlayer.getCurrentPosition() : 0;
    }

    private boolean engineGetPlayWhenReady() {
        return localPlayer != null && localPlayer.getPlayWhenReady();
    }

    /**
     * 暂停/恢复。
     *
     * MediaPlayer 没有"播放状态变了"的回调，所以通知栏、MediaSession
     * 和进度刷新都得在这里手动补上。
     */
    private void engineSetPlayWhenReady(boolean play) {
        if (localPlayer == null) return;
        localPlayer.setPlayWhenReady(play);
        boolean playing = localPlayer.isPlaying();
        updatePlaybackState(playing);
        broadcast("");
        updateNotification();
        if (playing) startProgress(); else stopProgress();
    }

    // ── 核心导航逻辑：本地列表优先，网络只在越过边界时触发 ──────────────────

    /**
     * 下一首。
     * 如果本地历史里已经有"更后面"的记录（比如之前按过上一首现在往回走），
     * 直接播放，零网络。
     * 只有走到本地历史最前沿，才需要向后端要一首全新的歌。
     */
    private void goNext() {
        synchronized (SESSION_HISTORY) {
            if (currentIndex < SESSION_HISTORY.size() - 1) {
                currentIndex++;
                playLocal(SESSION_HISTORY.get(currentIndex));
                return;
            }
        }
        fetchNew(true);
    }

    /**
     * 上一首。
     * 永远是本地操作——这首歌已经真实播放过，直链已经缓存在内存里，
     * 不需要问后端要任何东西。
     */
    private void goPrev() {
        synchronized (SESSION_HISTORY) {
            if (currentIndex > 0) {
                currentIndex--;
                playLocal(SESSION_HISTORY.get(currentIndex));
            } else {
                broadcast("已经是第一首了");
            }
        }
    }

    /** 播放列表里点选某一首，直接用本地缓存的记录 */
    private void jumpTo(int idx) {
        synchronized (SESSION_HISTORY) {
            if (idx < 0 || idx >= SESSION_HISTORY.size()) return;
            currentIndex = idx;
            playLocal(SESSION_HISTORY.get(idx));
        }
    }

    /**
     * 当前歌开播后，把下一首准备好：先向后端要歌曲信息，再后台把文件下下来。
     *
     * 这一步让"下载完再播"用起来不比流式慢——用户按下一首时，
     * 歌曲信息和音频文件通常都已经在本地了，是瞬间开始。
     */
    private void prepareNext() {
        synchronized (SESSION_HISTORY) {
            // 后面已经有歌了（用户之前按过上一首，现在正往回走），
            // 那"下一首"是本地已知的，只需要确保它的文件下好。
            if (currentIndex < SESSION_HISTORY.size() - 1) {
                SongInfo next = SESSION_HISTORY.get(currentIndex + 1);
                localPlayer().prefetch(next.id, next.streamUrl);
                return;
            }
        }
        fetchNew(false);   // 走到历史最前沿了，得向后端要一首全新的
    }

    /**
     * 向后端请求一首全新的歌（真正需要网络的唯一场景）。
     *
     * @param playNow true = 用户在等着听（马上播）；
     *                false = 后台预取，只把信息和文件备好，不打断当前播放
     */
    private void fetchNew(boolean playNow) {
        if (fetchInFlight) {
            // 已经有一个请求在飞了。如果那是后台预取而用户此刻按了下一首，
            // 记一笔，等它回来直接播——绝不能再发一次 /next，
            // 那在后端是一次真实的"网易云下载整首 + 上传移动云盘"。
            if (playNow) {
                pendingPlay = true;
                broadcast("正在准备歌曲...");
            }
            return;
        }
        fetchInFlight = true;
        pendingPlay   = playNow;
        if (playNow) broadcast("正在准备歌曲...");

        final long startMs      = System.currentTimeMillis();
        final int  myGeneration = playGeneration;

        executor.submit(() -> {
            SongInfo fetched = null;
            Exception error   = null;
            try {
                fetched = BackendClient.get(this).next();
            } catch (Exception e) {
                error = e;
            }
            long costMs = System.currentTimeMillis() - startMs;

            final SongInfo  song = fetched;
            final Exception err  = error;

            if (err != null) {
                DebugLog.log("BackendClient.next", "失败，耗时" + costMs + "ms");
                DebugLog.logException("BackendClient.next-异常", err);
            } else {
                DebugLog.log("BackendClient.next", "成功，耗时" + costMs + "ms，歌曲=" + song.title);
            }

            // 所有状态位的读写都收拢到主线程，避免 fetchInFlight / pendingPlay
            // 被两个线程同时改——那种竞态的表现是"偶尔连发两次 /next"，
            // 而每一次 /next 在后端都是一整首歌的下载加上传，代价太高。
            handler.post(() -> {
                fetchInFlight = false;
                boolean playIt = pendingPlay;   // 期间用户按过下一首的话，这里是 true
                pendingPlay = false;

                if (err != null) {
                    // 预取失败无害：用户真按到下一首时会再要一次。
                    // 只有用户正在干等的时候才需要报错和重试。
                    if (!playIt) {
                        DebugLog.log("BackendClient.next", "（后台预取失败，不影响当前播放）");
                        return;
                    }
                    if (myGeneration != playGeneration) return;
                    broadcast("加载失败: " + err.getMessage());
                    handler.postDelayed(() -> fetchNew(true), 5000);
                    return;
                }

                // 追加到历史末尾。这一步对预取很关键：
                // 用户按下一首时 goNext() 会在历史里发现它，直接本地播放，
                // 不会再发一次请求。
                int idx;
                synchronized (SESSION_HISTORY) {
                    SESSION_HISTORY.add(song);
                    idx = SESSION_HISTORY.size() - 1;
                }

                if (!playIt) {
                    // 后台预取：只把文件下下来，不动当前播放
                    DebugLog.log("预下载", "已排入下一首: " + song.title);
                    localPlayer().prefetch(song.id, song.streamUrl);
                    return;
                }
                // 代次变了说明用户在这期间手动切了歌，那就只留在历史里，
                // 等他自己按到它，别把正在播的打断。
                if (myGeneration != playGeneration) {
                    DebugLog.log("BackendClient.next",
                            "结果已过期（用户已手动切歌），只入库不播: " + song.title);
                    return;
                }
                currentIndex = idx;
                playLocal(song);
            });

            // 网络侧体检：跑在播放已经启动之后，不占用首播的时间。
            // 放在这里而不是 onCreate 里，是因为它需要一条真实的直链——
            // 直链是后端在这次请求里现生成的，提前无从获得
            // （这个"绝不预生成直链"的约定不能破）。
            if (song != null && runDiagThisLaunch) {
                runDiagThisLaunch = false;
                Diagnostics.logNetworkEnvironment(
                        ServerPreferences.get(MusicService.this).getBackendUrl(),
                        song.streamUrl);
                // 两半都跑完了，这个版本的体检就算交差了
                ServerPreferences.get(MusicService.this).markDiagnosticsDone(MusicService.this);
            }
        });
    }

    /** 播放一首已知信息的歌（本地列表里的），直接用缓存的直链，不发请求 */
    private void playLocal(SongInfo song) {
        playRetryCount = 0;
        playGeneration++;   // 新的一次真正切歌，之前所有代次的异步回调都作废
        DebugLog.log("playLocal", "开始播放: " + song.title + " id=" + song.id + " 代次=" + playGeneration +
                " url前缀=" + (song.streamUrl != null && song.streamUrl.length() > 60
                        ? song.streamUrl.substring(0, 60) : song.streamUrl));
        currentSong = song;
        updateMediaSessionMetadata();
        localPlayer().play(song.id, song.streamUrl);
        broadcast("");
    }

    private void onSongEnded() {
        if (currentSong != null) {
            // 必须扔到后台线程：played() 是同步 OkHttp 调用，而这里是主线程
            // （MediaPlayer 的 onCompletion 回调在主线程）。
            // 它内部 catch(Exception) 会把 NetworkOnMainThreadException 一并吞掉，
            // 所以这个上报一直是静默失败的，日志里连个响都没有。
            final String id   = currentSong.id;
            final int    secs = (int) (enginePosition() / 1000);
            executor.submit(() -> BackendClient.get(MusicService.this).played(id, secs));
        }
        goNext();
    }

    private void togglePlay() { engineSetPlayWhenReady(!engineGetPlayWhenReady()); }


    private void quit() { stopForeground(true); stopSelf(); }

    // ── 进度广播 ───────────────────────────────────────────────────────────

    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            if (enginePlaying()) {
                broadcast("");
                handler.postDelayed(this, 1000);
            }
        }
    };
    private void startProgress() { handler.removeCallbacks(progressRunnable); handler.post(progressRunnable); }
    private void stopProgress()  { handler.removeCallbacks(progressRunnable); }

    private void broadcast(String status) {
        Intent i = new Intent(BROADCAST_STATE);
        i.putExtra(EXTRA_TITLE,      currentSong != null ? currentSong.title   : "");
        i.putExtra(EXTRA_ARTIST,     currentSong != null ? currentSong.artist  : "");
        i.putExtra(EXTRA_IS_PLAYING, enginePlaying());
        i.putExtra(EXTRA_DURATION,   (int) engineDuration());
        i.putExtra(EXTRA_POSITION,   (int) enginePosition());
        i.putExtra(EXTRA_STATUS,     status != null ? status : "");
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }


    // ── MediaSession ───────────────────────────────────────────────────────

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "FMPlayer");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()           { engineSetPlayWhenReady(true);  }
            @Override public void onPause()          { engineSetPlayWhenReady(false); }
            @Override public void onSkipToNext()     { goNext(); }
            @Override public void onSkipToPrevious() { goPrev(); }
            @Override public void onStop()           { quit(); }
        });
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        updatePlaybackState(false);
        mediaSession.setActive(true);
    }

    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        long pos = enginePosition();
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING
                                  : PlaybackStateCompat.STATE_PAUSED, pos, 1.0f)
                .build());
    }

    private void updateMediaSessionMetadata() {
        if (currentSong == null || mediaSession == null) return;
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                .build());
    }

    // ── 通知 ───────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "FM播放器", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        boolean playing = enginePlaying();
        String  title   = currentSong != null ? currentSong.title  : "FM播放器";
        String  artist  = currentSong != null ? currentSong.artist : "正在加载...";
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;

        PendingIntent piOpen   = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flag);
        PendingIntent piToggle = PendingIntent.getService(this, 1, new Intent(this, MusicService.class).setAction(ACTION_TOGGLE_PLAY), flag);
        PendingIntent piNext   = PendingIntent.getService(this, 2, new Intent(this, MusicService.class).setAction(ACTION_NEXT), flag);
        PendingIntent piPrev   = PendingIntent.getService(this, 4, new Intent(this, MusicService.class).setAction(ACTION_PREV), flag);
        PendingIntent piStop   = PendingIntent.getService(this, 3, new Intent(this, MusicService.class).setAction(ACTION_STOP), flag);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title).setContentText(artist)
                .setSmallIcon(R.drawable.ic_notification).setContentIntent(piOpen)
                .addAction(android.R.drawable.ic_media_previous, "上一首", piPrev)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "暂停" : "播放", piToggle)
                .addAction(android.R.drawable.ic_media_next, "下一首", piNext)
                .addAction(android.R.drawable.ic_delete, "停止", piStop)
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
    }
}
