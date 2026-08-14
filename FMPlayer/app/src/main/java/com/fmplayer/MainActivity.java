package com.fmplayer;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fmplayer.model.SongInfo;
import com.fmplayer.preferences.ServerPreferences;
import com.fmplayer.service.MusicService;

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView    tvTitle, tvArtist, tvStatus, tvPosition, tvDuration;
    private SeekBar     seekBar;
    private ImageButton btnPlayPause, btnNext, btnPrev, btnPlaylist, btnSettings, btnLike, btnTrash;

    /** 当前歌是否已红心，驱动 btnLike 的图标/着色。由广播更新。 */
    private boolean currentLiked = false;
    /** 当前是否有可操作的歌（拿到过 song_id），无歌时喜欢/不感兴趣按钮置灰。 */
    private boolean hasSong = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupButtons();
        tvTitle.setSelected(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                receiver, new IntentFilter(MusicService.BROADCAST_STATE));

        if (!ServerPreferences.get(this).isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            startService(new Intent(this, MusicService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
    }

    private void bindViews() {
        tvTitle      = findViewById(R.id.tv_title);
        tvArtist     = findViewById(R.id.tv_artist);
        tvStatus     = findViewById(R.id.tv_status);
        tvPosition   = findViewById(R.id.tv_position);
        tvDuration   = findViewById(R.id.tv_duration);
        seekBar      = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnNext      = findViewById(R.id.btn_next);
        btnPrev      = findViewById(R.id.btn_prev);
        btnPlaylist  = findViewById(R.id.btn_playlist);
        btnSettings  = findViewById(R.id.btn_settings);
        btnLike      = findViewById(R.id.btn_like);
        btnTrash     = findViewById(R.id.btn_trash);
    }

    private void setupButtons() {
        btnPlayPause.setOnClickListener(v -> send(MusicService.ACTION_TOGGLE_PLAY));
        btnNext.setOnClickListener(v      -> send(MusicService.ACTION_NEXT));
        btnPrev.setOnClickListener(v      -> send(MusicService.ACTION_PREV));
        btnSettings.setOnClickListener(v  -> startActivity(new Intent(this, SettingsActivity.class)));
        btnPlaylist.setOnClickListener(v  -> showPlaylist());
        btnLike.setOnClickListener(v      -> { if (hasSong) send(MusicService.ACTION_LIKE); });
        btnTrash.setOnClickListener(v     -> { if (hasSong) send(MusicService.ACTION_TRASH); });
        // 进度条只做展示，不接受拖动：三个回调全是空实现，注册它等于没注册。
        // 真要支持拖动得给 MusicService 加一个 ACTION_SEEK，这里先保持只读。
        seekBar.setEnabled(false);
        applyLikeIcon();
        applyEnabled();
    }

    private void send(String action) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        startService(i);
    }

    /** 根据 currentLiked 切换红心图标与着色（红=已喜欢，灰空心=未喜欢）。 */
    private void applyLikeIcon() {
        if (currentLiked) {
            btnLike.setImageResource(R.drawable.ic_heart_filled);
            btnLike.setColorFilter(getResources().getColor(R.color.like_red));
        } else {
            btnLike.setImageResource(R.drawable.ic_heart_outline);
            btnLike.setColorFilter(getResources().getColor(R.color.text_secondary));
        }
    }

    /** 无歌可操作时把喜欢/不感兴趣按钮置半透明并禁用。 */
    private void applyEnabled() {
        float a = hasSong ? 1f : 0.35f;
        btnLike.setEnabled(hasSong);   btnLike.setAlpha(a);
        btnTrash.setEnabled(hasSong);  btnTrash.setAlpha(a);
    }

    // ── 播放列表弹窗：自定义暗色卡片，倒序（最新在最上），高亮正在播放 ──────────

    private void showPlaylist() {
        List<SongInfo> hist = MusicService.getSessionHistorySnapshot();

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_history, null);
        TextView     subtitle = content.findViewById(R.id.dialog_subtitle);
        LinearLayout container = content.findViewById(R.id.list_container);
        View         closeBtn = content.findViewById(R.id.dialog_close);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content).create();

        if (hist.isEmpty()) {
            subtitle.setText(R.string.empty_history);
        } else {
            subtitle.setText("共 " + hist.size() + " 首");
            int current = MusicService.getCurrentIndex();

            // 倒序展示：最新播放的在最上面。真实下标 realIdx 从末尾往前，
            // 用于点击后传给 ACTION_PLAY_HISTORY——播放定位必须用真实下标，
            // 不能用倒序后的显示序号。
            LayoutInflater inf = LayoutInflater.from(this);
            for (int realIdx = hist.size() - 1; realIdx >= 0; realIdx--) {
                SongInfo song = hist.get(realIdx);
                boolean isCurrent = (realIdx == current);

                View row = inf.inflate(R.layout.item_history, container, false);
                TextView tvIndex = row.findViewById(R.id.tv_index);
                TextView tvSong  = row.findViewById(R.id.tv_song);
                TextView tvSub   = row.findViewById(R.id.tv_sub);

                if (isCurrent) {
                    tvIndex.setText("▶");
                    tvIndex.setTextColor(getResources().getColor(R.color.accent));
                    tvSong.setTextColor(getResources().getColor(R.color.accent));
                } else {
                    // 显示真实播放序号：最早播放的那首(realIdx=0)是 1，每播一首 +1。
                    // 倒序只影响排列(最新在最上)，序号仍按真实播放顺序编号。
                    tvIndex.setText(String.valueOf(realIdx + 1));
                    tvIndex.setTextColor(getResources().getColor(R.color.text_secondary));
                    tvSong.setTextColor(getResources().getColor(R.color.text_primary));
                }
                tvSong.setText(song.title != null && !song.title.isEmpty() ? song.title : song.displayName());
                tvSub.setText(song.artist != null ? song.artist : "");

                final int target = realIdx;
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(this, MusicService.class);
                    intent.setAction(MusicService.ACTION_PLAY_HISTORY);
                    intent.putExtra(MusicService.EXTRA_HISTORY_IDX, target);
                    startService(intent);
                    dialog.dismiss();
                });
                container.addView(row);
            }
        }

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        // 消除 AlertDialog 默认的浅色窗口边框——否则深色卡片外会露出一圈白边。
        // setBackgroundDrawable 是 API 1 的老方法，车机 API 17 稳。
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String  title   = intent.getStringExtra(MusicService.EXTRA_TITLE);
            String  artist  = intent.getStringExtra(MusicService.EXTRA_ARTIST);
            String  status  = intent.getStringExtra(MusicService.EXTRA_STATUS);
            boolean playing = intent.getBooleanExtra(MusicService.EXTRA_IS_PLAYING, false);
            int     dur     = intent.getIntExtra(MusicService.EXTRA_DURATION, 0);
            int     pos     = intent.getIntExtra(MusicService.EXTRA_POSITION, 0);
            String  songId  = intent.getStringExtra(MusicService.EXTRA_SONG_ID);
            boolean liked   = intent.getBooleanExtra(MusicService.EXTRA_LIKED, false);

            if (title  != null && !title.isEmpty())  tvTitle.setText(title);
            if (artist != null && !artist.isEmpty())  tvArtist.setText(artist);

            if (status != null && !status.isEmpty()) {
                tvStatus.setVisibility(View.VISIBLE); tvStatus.setText(status);
            } else {
                tvStatus.setVisibility(View.GONE);
            }

            btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

            // 红心态与按钮可用性
            boolean newHasSong = songId != null && !songId.isEmpty();
            if (newHasSong != hasSong) { hasSong = newHasSong; applyEnabled(); }
            if (liked != currentLiked) { currentLiked = liked; applyLikeIcon(); }

            if (dur > 0) {
                seekBar.setMax(dur); seekBar.setProgress(pos);
                tvPosition.setText(fmt(pos)); tvDuration.setText(fmt(dur));
            }
        }
    };

    private String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }
}
