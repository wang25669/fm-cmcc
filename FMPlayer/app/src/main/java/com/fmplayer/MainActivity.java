package com.fmplayer;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
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
    private ImageButton btnPlayPause, btnNext, btnPrev, btnPlaylist, btnSettings;

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
    }

    private void setupButtons() {
        btnPlayPause.setOnClickListener(v -> send(MusicService.ACTION_TOGGLE_PLAY));
        btnNext.setOnClickListener(v      -> send(MusicService.ACTION_NEXT));
        btnPrev.setOnClickListener(v      -> send(MusicService.ACTION_PREV));
        btnSettings.setOnClickListener(v  -> startActivity(new Intent(this, SettingsActivity.class)));
        btnPlaylist.setOnClickListener(v  -> showPlaylist());
        // 进度条只做展示，不接受拖动：三个回调全是空实现，注册它等于没注册。
        // 真要支持拖动得给 MusicService 加一个 ACTION_SEEK，这里先保持只读。
        seekBar.setEnabled(false);
    }

    private void send(String action) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        startService(i);
    }

    private void showPlaylist() {
        // 直接读内存里的会话播放列表，不发任何网络请求，秒开
        List<SongInfo> hist = MusicService.getSessionHistorySnapshot();

        if (hist.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("本次播放列表").setMessage("还没有播放记录")
                    .setPositiveButton("关闭", null).show();
            return;
        }

        String[] items = new String[hist.size()];
        for (int i = 0; i < hist.size(); i++)
            items[i] = (i + 1) + ".  " + hist.get(i).displayName();

        new AlertDialog.Builder(this)
                .setTitle("本次播放列表（共 " + hist.size() + " 首）")
                .setItems(items, (d, which) -> {
                    Intent intent = new Intent(this, MusicService.class);
                    intent.setAction(MusicService.ACTION_PLAY_HISTORY);
                    intent.putExtra(MusicService.EXTRA_HISTORY_IDX, which);
                    startService(intent);
                })
                .setNegativeButton("关闭", null).show();
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

            if (title  != null && !title.isEmpty())  tvTitle.setText(title);
            if (artist != null && !artist.isEmpty())  tvArtist.setText(artist);

            if (status != null && !status.isEmpty()) {
                tvStatus.setVisibility(View.VISIBLE); tvStatus.setText(status);
            } else {
                tvStatus.setVisibility(View.GONE);
            }

            btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

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
