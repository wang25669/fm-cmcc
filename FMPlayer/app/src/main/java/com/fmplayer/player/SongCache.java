package com.fmplayer.player;

import android.content.Context;

import com.fmplayer.network.DebugLog;

import java.io.File;

/**
 * 歌曲文件的本地缓存。
 *
 * ── 为什么要有它 ──────────────────────────────────────────────────────────
 *
 * 改成"下载完再播"之后，如果每次都下载，那"上一首"就从原来的零成本
 * 变成了要重新拉 10MB，这是纯粹的倒退。按歌曲 id 缓存之后：
 *
 *   · 上一首 / 重听听过的歌  → 命中缓存，瞬间开始，零网络
 *   · 下一首                → 后台已经预下载好了，也是瞬间开始
 *
 * 真正需要等待的只剩"听到一首没缓存过的新歌"这一种情况。
 *
 * ── 放在哪 ────────────────────────────────────────────────────────────────
 *
 * 优先用外部缓存目录（车机上在 /mnt/sdcard 下，日志显示还有 3704MB 可用）。
 * 内部存储在这种老车机上通常只有几百 MB，塞几十首 320kbps 的歌就满了，
 * 而且塞满内部存储会连带影响系统本身。
 *
 * 用 cache 目录而不是 files 目录是故意的：系统在空间紧张时可以自己回收，
 * 不至于因为这个播放器把车机撑死。
 */
public class SongCache {

    /** 缓存上限。一首 320kbps 的歌大约 10MB，500MB 差不多能存 50 首 */
    private static final long MAX_BYTES = 500L * 1024 * 1024;

    private final File dir;

    public SongCache(Context ctx) {
        File base = ctx.getExternalCacheDir();
        if (base == null) base = ctx.getCacheDir();   // SD 卡没挂载时退回内部存储
        dir = new File(base, "songs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        DebugLog.log("缓存", "歌曲缓存目录: " + dir.getAbsolutePath()
                + " 可写=" + dir.canWrite());
    }

    /** 某首歌在缓存里的位置。文件存不存在是另一回事，用 {@link #has} 判断 */
    public File fileFor(String songId) {
        return new File(dir, sanitize(songId) + ".mp3");
    }

    /** 下载中的临时文件。下载完才改名成正式文件，避免半截文件被当成有效缓存 */
    public File tempFor(String songId) {
        return new File(dir, sanitize(songId) + ".part");
    }

    public boolean has(String songId) {
        File f = fileFor(songId);
        return f.exists() && f.length() > 0;
    }

    /**
     * 标记这个文件刚被用过。LRU 淘汰靠 lastModified 排序，
     * 不 touch 的话"经常重听的老歌"会因为下载时间早而先被删掉。
     */
    public void touch(String songId) {
        File f = fileFor(songId);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        }
    }

    /**
     * 超出上限就按"最久没用过"的顺序删，直到降回上限以下。
     * 每次下载完成后调一次。
     *
     * @param keepId 绝对不能删的那一首（正在播的）。哪怕它是最老的也得留着。
     */
    public void trim(String keepId) {
        File[] files = dir.listFiles();
        if (files == null) return;

        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_BYTES) return;

        // 按最后使用时间从旧到新排。用插入排序，几十个文件的量级足够了，
        // 也省得为了 Comparator 去纠结 API 17 上有没有 Arrays.sort 的某个重载。
        java.util.Arrays.sort(files, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));

        String keepName = keepId == null ? null : fileFor(keepId).getName();
        int deleted = 0;
        long freed = 0;

        for (File f : files) {
            if (total <= MAX_BYTES) break;
            if (keepName != null && keepName.equals(f.getName())) continue;
            // .part 是别的线程正在写的下载。删掉它不会腾出空间（对方还在往
            // 那个 inode 里写），只会让那次下载在最后 rename 时白白失败。
            // 下载失败时它自己会清理，这里不插手。
            if (f.getName().endsWith(".part")) continue;
            long size = f.length();
            if (f.delete()) {
                total -= size;
                freed += size;
                deleted++;
            }
        }
        if (deleted > 0) {
            DebugLog.log("缓存", "清理了 " + deleted + " 个旧文件，释放 "
                    + (freed >> 20) + "MB，当前占用 " + (total >> 20) + "MB");
        }
    }

    /** 歌曲 id 理论上是数字串，但不能指望后端永远如此，非法字符一律换成下划线 */
    private static String sanitize(String id) {
        if (id == null || id.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }
}
