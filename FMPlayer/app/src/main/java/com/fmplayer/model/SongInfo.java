package com.fmplayer.model;

import org.json.JSONObject;

public class SongInfo {
    public String id;
    public String title;
    public String artist;
    public String album;
    public String streamUrl;
    public String filename;
    /** 是否已红心。仅客户端会话内维护——用户点了喜欢就置 true，用于按钮态展示。 */
    public boolean liked = false;

    public static SongInfo fromJson(JSONObject j) {
        SongInfo s    = new SongInfo();
        s.id          = j.optString("id",         "");
        s.title       = j.optString("title",      "");
        s.artist      = j.optString("artist",     "");
        s.album       = j.optString("album",      "");
        s.streamUrl   = j.optString("stream_url", "");
        s.filename    = j.optString("filename",   "");
        return s;
    }

    public String displayName() {
        if (artist.isEmpty()) return title;
        return artist + " - " + title;
    }
}
