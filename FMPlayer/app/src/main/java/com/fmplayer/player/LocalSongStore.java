package com.fmplayer.player;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import com.fmplayer.model.SongInfo;
import com.fmplayer.network.BackendClient;
import com.fmplayer.network.DebugLog;
import com.fmplayer.preferences.ServerPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地已缓存歌曲的持久化索引。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────────
 * 以前所有歌曲元数据（歌名、歌手等）都只放在内存的 SESSION_HISTORY 里。
 * 车机断电或 App 重启后列表变空；断网时由于取不到 /next，陷入死循环重试，
 * 哪怕本地磁盘里已经下了 500MB（约50首）歌曲也无法播放。
 *
 * 本类维护一份本地轻量索引文件 songs_index.json：
 *   1. 启动或切换离线模式时，若本地已有 MP3 未建立索引（如更新前已下载的 500MB 歌曲），
 *      使用 Android 原生 MediaMetadataRetriever 提取 MP3 内嵌 ID3 标签（Title, Artist）
 *      自动补建元数据，确保已有歌曲立即可见、可播。
 *   2. 在线听歌/预取成功时自动更新持久化索引。
 *   3. 缓存淘汰（SongCache.trim）删除老文件时同步移出索引。
 */
public class LocalSongStore {

    private static LocalSongStore instance;

    private final Context ctx;
    private final File indexFile;
    private final Map<String, SongInfo> indexMap = new LinkedHashMap<>();
    private final Object lock = new Object();
    private boolean loaded = false;

    public static synchronized LocalSongStore get(Context context) {
        if (instance == null) {
            instance = new LocalSongStore(context.getApplicationContext());
        }
        return instance;
    }

    private LocalSongStore(Context context) {
        this.ctx = context;
        File base = ctx.getExternalCacheDir();
        if (base == null) base = ctx.getCacheDir();
        File songsDir = new File(base, "songs");
        if (!songsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            songsDir.mkdirs();
        }
        this.indexFile = new File(songsDir, "songs_index.json");
        loadIndex();
    }

    /** 从 songs_index.json 读取索引到内存 */
    private void loadIndex() {
        synchronized (lock) {
            if (loaded) return;
            indexMap.clear();
            if (indexFile.exists() && indexFile.length() > 0) {
                try (FileInputStream fis = new FileInputStream(indexFile);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    JSONArray array = new JSONArray(sb.toString());
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.optJSONObject(i);
                        if (obj != null) {
                            SongInfo s = SongInfo.fromJson(obj);
                            if (s.id != null && !s.id.isEmpty()) {
                                indexMap.put(s.id, s);
                            }
                        }
                    }
                    DebugLog.log("LocalSongStore", "加载本地歌曲索引成功，共 " + indexMap.size() + " 首");
                } catch (Exception e) {
                    DebugLog.log("LocalSongStore", "加载索引异常: " + e.getMessage());
                }
            }
            loaded = true;
        }
    }

    /** 将当前内存索引落盘到 songs_index.json（临时文件改名保证原子性） */
    private void saveIndex() {
        synchronized (lock) {
            File tmp = new File(indexFile.getParentFile(), "songs_index.json.tmp");
            try {
                JSONArray array = new JSONArray();
                for (SongInfo s : indexMap.values()) {
                    array.put(s.toJson());
                }
                try (FileOutputStream fos = new FileOutputStream(tmp);
                     Writer writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(array.toString());
                    writer.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                indexFile.delete();
                if (!tmp.renameTo(indexFile)) {
                    DebugLog.log("LocalSongStore", "索引文件重命名失败");
                }
            } catch (Exception e) {
                DebugLog.log("LocalSongStore", "保存索引异常: " + e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /**
     * 判断字符串是否为乱码（如为 null、全空格、全是问号 "?" / "？" 等）。
     */
    public static boolean isGarbage(String s) {
        if (s == null) return true;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return true;
        boolean allQ = true;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c != '?' && c != '？' && !Character.isWhitespace(c)) {
                allQ = false;
                break;
            }
        }
        return allQ;
    }

    /**
     * 判断歌名是否为占位符或乱码（如 "本地歌曲 ID"、"[本地歌曲 ID]" 或 "???" 等）。
     */
    public static boolean isPlaceholderOrGarbage(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("本地歌曲 ") || t.startsWith("歌曲 ")
                || t.startsWith("[本地歌曲") || t.startsWith("[歌曲")) {
            return true;
        }
        return isGarbage(t);
    }

    /**
     * 扫描磁盘上的歌曲缓存并与索引做双向同步：
     * 1. 发现磁盘上有、但索引里没有的 .mp3（例如之前下载的 500MB 歌曲），
     *    或之前索引中只有占位符/乱码（"本地歌曲 ID" / "???"）的歌曲，
     *    优先使用纯 Java ID3 解析器（支持 GBK/UTF-8 字符集）重读元数据。
     * 2. 发现索引里有、但磁盘文件已被删掉的，从索引移除。
     */
    public void scanAndSync(SongCache cache) {
        synchronized (lock) {
            loadIndex();
            File dir = cache.getDir();
            File[] files = dir.listFiles();
            boolean dirty = false;

            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (!name.endsWith(".mp3")) continue;
                    String id = name.substring(0, name.length() - 4);
                    if (id.isEmpty()) continue;

                    SongInfo existing = indexMap.get(id);
                    boolean needParse = (existing == null)
                            || isPlaceholderOrGarbage(existing.title)
                            || isGarbage(existing.artist)
                            || existing.artist == null
                            || existing.artist.isEmpty();

                    if (needParse) {
                        SongInfo song = readId3Tag(f, id);
                        if (existing == null) {
                            indexMap.put(id, song);
                            dirty = true;
                            DebugLog.log("LocalSongStore", "识别到本地缓存: " + song.displayName() + " (id=" + id + ")");
                        } else {
                            boolean updated = false;
                            if (isPlaceholderOrGarbage(existing.title) && !isPlaceholderOrGarbage(song.title)) {
                                existing.title = song.title;
                                updated = true;
                            }
                            if ((isGarbage(existing.artist) || existing.artist == null || existing.artist.isEmpty())
                                    && !isGarbage(song.artist) && !song.artist.isEmpty()) {
                                existing.artist = song.artist;
                                updated = true;
                            }
                            if (isGarbage(existing.album) && !isGarbage(song.album)) {
                                existing.album = song.album;
                                updated = true;
                            }
                            if (updated) {
                                dirty = true;
                                DebugLog.log("LocalSongStore", "通过 ID3 标签修复本地元数据: " + existing.displayName() + " (id=" + id + ")");
                            }
                        }
                    }
                }
            }

            // 清理磁盘上已不存在的条目
            List<String> toRemove = new ArrayList<>();
            for (String id : indexMap.keySet()) {
                if (!cache.has(id)) {
                    toRemove.add(id);
                }
            }
            if (!toRemove.isEmpty()) {
                for (String id : toRemove) {
                    indexMap.remove(id);
                }
                dirty = true;
            }

            if (dirty) {
                saveIndex();
                DebugLog.log("LocalSongStore", "同步完成，当前有效本地歌曲: " + indexMap.size() + " 首");
            }
        }
    }

    /**
     * 检查是否有本地缓存歌曲的元数据不完整（如歌名是"本地歌曲 ID"、"???"或歌手为空）。
     * 若有，向后端 /songs/batch_info 批量拉取权威元数据并持久化到 songs_index.json。
     * @return 成功更新的歌曲数量
     */
    public int syncMissingWithBackend() {
        List<String> needSyncIds = new ArrayList<>();
        synchronized (lock) {
            loadIndex();
            for (Map.Entry<String, SongInfo> entry : indexMap.entrySet()) {
                SongInfo s = entry.getValue();
                if (s == null || isPlaceholderOrGarbage(s.title)
                        || s.artist == null || s.artist.isEmpty() || isGarbage(s.artist)) {
                    needSyncIds.add(entry.getKey());
                }
            }
        }
        if (needSyncIds.isEmpty()) return 0;
        if (!ServerPreferences.get(ctx).isConfigured()) return 0;

        DebugLog.log("LocalSongStore", "发现 " + needSyncIds.size() + " 首歌曲元数据缺失，向后端批量请求信息...");
        try {
            List<SongInfo> remoteSongs = BackendClient.get(ctx).getSongsBatchInfo(needSyncIds);
            if (remoteSongs == null || remoteSongs.isEmpty()) return 0;

            int updated = 0;
            synchronized (lock) {
                for (SongInfo remote : remoteSongs) {
                    if (remote == null || remote.id == null || remote.id.isEmpty()) continue;
                    SongInfo local = indexMap.get(remote.id);
                    if (local == null) continue;

                    boolean changed = false;
                    if (!isPlaceholderOrGarbage(remote.title) && !remote.title.equals(local.title)) {
                        local.title = remote.title;
                        changed = true;
                    }
                    if (remote.artist != null && !remote.artist.isEmpty() && !isGarbage(remote.artist)
                            && !remote.artist.equals(local.artist)) {
                        local.artist = remote.artist;
                        changed = true;
                    }
                    if (remote.album != null && !remote.album.isEmpty() && !isGarbage(remote.album)
                            && !remote.album.equals(local.album)) {
                        local.album = remote.album;
                        changed = true;
                    }
                    if (changed) {
                        updated++;
                        DebugLog.log("LocalSongStore", "已补全本地元数据: " + local.displayName() + " (id=" + local.id + ")");
                    }
                }
                if (updated > 0) {
                    saveIndex();
                    DebugLog.log("LocalSongStore", "批量补全完成，共更新 " + updated + " 首");
                }
            }
            return updated;
        } catch (Exception e) {
            DebugLog.log("LocalSongStore", "批量获取元数据失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 提取 MP3 元数据：纯 Java ID3v2/ID3v1 优先（兼容 GBK/UTF-8 汉字编码），
     * 原生 MediaMetadataRetriever 兜底。
     */
    private SongInfo readId3Tag(File f, String id) {
        String[] meta = new String[3]; // [title, artist, album]

        // 1. 纯 Java ID3v2 解析（支持 GBK/UTF-8/UTF-16）
        parseId3v2(f, meta);

        // 2. 若歌名或歌手仍缺失，尝试纯 Java ID3v1 解析（GBK 编码）
        if (isPlaceholderOrGarbage(meta[0]) || isGarbage(meta[1])) {
            parseId3v1(f, meta);
        }

        // 3. 若仍缺失，尝试原生 MediaMetadataRetriever 兜底
        if (isPlaceholderOrGarbage(meta[0]) || isGarbage(meta[1])) {
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(f.getAbsolutePath());
                String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                if (isPlaceholderOrGarbage(meta[0]) && !isGarbage(t)) meta[0] = t.trim();
                if (isGarbage(meta[1]) && !isGarbage(a)) meta[1] = a.trim();
                if (isGarbage(meta[2]) && !isGarbage(al)) meta[2] = al.trim();
            } catch (Throwable ignored) {
            } finally {
                if (mmr != null) {
                    try { mmr.release(); } catch (Throwable ignored) {}
                }
            }
        }

        SongInfo song = new SongInfo();
        song.id = id;
        song.title = !isPlaceholderOrGarbage(meta[0]) ? meta[0].trim() : ("本地歌曲 " + id);
        song.artist = !isGarbage(meta[1]) ? meta[1].trim() : "";
        song.album = !isGarbage(meta[2]) ? meta[2].trim() : "";
        song.filename = f.getName();
        return song;
    }

    private static void parseId3v1(File f, String[] out) {
        if (f.length() < 128) return;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            raf.seek(f.length() - 128);
            byte[] buf = new byte[128];
            raf.readFully(buf);
            if (buf[0] == 'T' && buf[1] == 'A' && buf[2] == 'G') {
                String title = extractFixedString(buf, 3, 30);
                String artist = extractFixedString(buf, 33, 30);
                String album = extractFixedString(buf, 63, 30);
                if (!isGarbage(title) && isPlaceholderOrGarbage(out[0])) out[0] = title;
                if (!isGarbage(artist) && isGarbage(out[1])) out[1] = artist;
                if (!isGarbage(album) && isGarbage(out[2])) out[2] = album;
            }
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String extractFixedString(byte[] buf, int offset, int len) {
        int end = offset + len;
        while (end > offset && (buf[end - 1] == 0 || (buf[end - 1] & 0xFF) <= 32)) {
            end--;
        }
        int start = offset;
        while (start < end && (buf[start] == 0 || (buf[start] & 0xFF) <= 32)) {
            start++;
        }
        if (start >= end) return "";
        byte[] slice = new byte[end - start];
        System.arraycopy(buf, start, slice, 0, slice.length);
        return decodeGbkOrUtf8(slice);
    }

    private static void parseId3v2(File f, String[] out) {
        if (f.length() < 10) return;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] header = new byte[10];
            int read = fis.read(header);
            if (read < 10) return;
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return;
            int version = header[3] & 0xFF;
            if (version != 3 && version != 4) return;
            int flags = header[5] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21)
                    | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7)
                    | (header[9] & 0x7F);
            if (tagSize <= 0 || tagSize > 2 * 1024 * 1024) return;

            byte[] data = new byte[tagSize];
            int totalRead = 0;
            while (totalRead < tagSize) {
                int r = fis.read(data, totalRead, tagSize - totalRead);
                if (r <= 0) break;
                totalRead += r;
            }
            if (totalRead < tagSize) return;

            int pos = 0;
            if ((flags & 0x40) != 0) {
                if (version == 3 && tagSize >= 4) {
                    int extSize = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    pos = 4 + extSize;
                } else if (version == 4 && tagSize >= 4) {
                    int extSize = ((data[0] & 0x7F) << 21) | ((data[1] & 0x7F) << 14) | ((data[2] & 0x7F) << 7) | (data[3] & 0x7F);
                    pos = extSize;
                }
            }

            while (pos + 10 <= tagSize) {
                if (data[pos] == 0) break;
                String frameId = new String(data, pos, 4, "ISO-8859-1");
                int frameSize;
                if (version == 4) {
                    frameSize = ((data[pos + 4] & 0x7F) << 21)
                            | ((data[pos + 5] & 0x7F) << 14)
                            | ((data[pos + 6] & 0x7F) << 7)
                            | (data[pos + 7] & 0x7F);
                } else {
                    frameSize = ((data[pos + 4] & 0xFF) << 24)
                            | ((data[pos + 5] & 0xFF) << 16)
                            | ((data[pos + 6] & 0xFF) << 8)
                            | (data[pos + 7] & 0xFF);
                }
                pos += 10;
                if (frameSize <= 0 || pos + frameSize > tagSize) break;

                int encoding = data[pos] & 0xFF;
                int textOffset = pos + 1;
                int textLen = frameSize - 1;

                if ("TIT2".equals(frameId)) {
                    String title = decodeString(data, textOffset, textLen, encoding);
                    if (!isGarbage(title) && isPlaceholderOrGarbage(out[0])) out[0] = title;
                } else if ("TPE1".equals(frameId)) {
                    String artist = decodeString(data, textOffset, textLen, encoding);
                    if (!isGarbage(artist) && isGarbage(out[1])) out[1] = artist;
                } else if ("TALB".equals(frameId)) {
                    String album = decodeString(data, textOffset, textLen, encoding);
                    if (!isGarbage(album) && isGarbage(out[2])) out[2] = album;
                }
                pos += frameSize;
            }
        } catch (Throwable ignored) {
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String decodeString(byte[] data, int offset, int length, int encoding) {
        if (length <= 0 || offset >= data.length) return "";
        length = Math.min(length, data.length - offset);
        while (length > 0 && data[offset + length - 1] == 0) {
            length--;
        }
        if (length <= 0) return "";
        try {
            if (encoding == 1) {
                return new String(data, offset, length, "UTF-16").trim();
            } else if (encoding == 2) {
                return new String(data, offset, length, "UTF-16BE").trim();
            } else if (encoding == 3) {
                return new String(data, offset, length, "UTF-8").trim();
            } else {
                byte[] slice = new byte[length];
                System.arraycopy(data, offset, slice, 0, length);
                return decodeGbkOrUtf8(slice).trim();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean hasUtf8Multibyte(byte[] bytes) {
        int i = 0;
        boolean seen = false;
        int n = bytes.length;
        while (i < n) {
            int b = bytes[i] & 0xFF;
            if (b <= 0x7F) {
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= n || (bytes[i + 1] & 0xC0) != 0x80) return false;
                seen = true;
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= n || (bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80) return false;
                seen = true;
                i += 3;
            } else if ((b & 0xF8) == 0xF0) {
                if (i + 3 >= n || (bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80 || (bytes[i + 3] & 0xC0) != 0x80) return false;
                seen = true;
                i += 4;
            } else {
                return false;
            }
        }
        return seen;
    }

    private static String decodeGbkOrUtf8(byte[] b) {
        if (b == null || b.length == 0) return "";
        if (hasUtf8Multibyte(b)) {
            try {
                return new String(b, "UTF-8");
            } catch (Exception ignored) {}
        }
        try {
            return new String(b, "GBK");
        } catch (Exception e) {
            try {
                return new String(b, "GB2312");
            } catch (Exception e2) {
                return new String(b);
            }
        }
    }

    /** 记录或更新一首歌的元数据 */
    public void saveSong(SongInfo song) {
        if (song == null || song.id == null || song.id.isEmpty()) return;
        synchronized (lock) {
            loadIndex();
            indexMap.put(song.id, song);
            saveIndex();
        }
    }

    /** 从索引中移除一首歌（文件被清理时调用） */
    public void removeSong(String songId) {
        if (songId == null || songId.isEmpty()) return;
        synchronized (lock) {
            loadIndex();
            if (indexMap.remove(songId) != null) {
                saveIndex();
            }
        }
    }

    /** 获取所有已缓存本地歌曲的快照列表 */
    public List<SongInfo> getAllSongs() {
        synchronized (lock) {
            loadIndex();
            return new ArrayList<>(indexMap.values());
        }
    }

    /** 获取指定 ID 的歌曲元数据 */
    public SongInfo getSong(String songId) {
        synchronized (lock) {
            loadIndex();
            return indexMap.get(songId);
        }
    }

    /** 当前本地已缓存歌曲数量 */
    public int count() {
        synchronized (lock) {
            loadIndex();
            return indexMap.size();
        }
    }
}
