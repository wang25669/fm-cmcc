package com.fmplayer.player;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import com.fmplayer.model.SongInfo;
import com.fmplayer.network.DebugLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
     * 扫描磁盘上的歌曲缓存并与索引做双向同步：
     * 1. 发现磁盘上有、但索引里没有的 .mp3（例如版本升级前就已经下载好缓存的那 500MB 歌曲），
     *    调用 MediaMetadataRetriever 解析其 ID3 标签补全歌名和歌手并加入索引。
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

                    if (!indexMap.containsKey(id)) {
                        // 发现未索引的 MP3 文件，读取 ID3 标签补齐信息
                        SongInfo song = readId3Tag(f, id);
                        indexMap.put(id, song);
                        dirty = true;
                        DebugLog.log("LocalSongStore", "识别到已有本地缓存: " + song.displayName() + " (id=" + id + ")");
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

    /** 从 MP3 文件中通过 MediaMetadataRetriever 提取元数据（API 10+ 原生支持） */
    private SongInfo readId3Tag(File f, String id) {
        MediaMetadataRetriever mmr = null;
        String title = null;
        String artist = null;
        String album = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(f.getAbsolutePath());
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        } catch (Throwable t) {
            DebugLog.log("LocalSongStore", "读取 " + f.getName() + " ID3 失败: " + t.getMessage());
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Throwable ignored) {}
            }
        }

        SongInfo song = new SongInfo();
        song.id = id;
        song.title = (title != null && !title.trim().isEmpty()) ? title.trim() : ("本地歌曲 " + id);
        song.artist = (artist != null && !artist.trim().isEmpty()) ? artist.trim() : "";
        song.album = (album != null) ? album.trim() : "";
        song.filename = f.getName();
        return song;
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
