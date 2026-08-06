package com.fmplayer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fmplayer.network.BackendClient;
import com.fmplayer.preferences.ServerPreferences;
import com.fmplayer.service.MusicService;

import org.json.JSONObject;

public class SettingsActivity extends AppCompatActivity {

    private EditText     etUrl;
    private Button       btnTest, btnQr, btnSave;
    private LinearLayout llQrArea;
    private ImageView    ivQr;
    private TextView     tvQrHint, tvResult;

    private final Handler pollHandler = new Handler();
    private Runnable      pollRunnable;
    private boolean       ncmLoggedIn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etUrl     = findViewById(R.id.et_backend_url);
        btnTest   = findViewById(R.id.btn_test);
        btnQr     = findViewById(R.id.btn_get_qr);
        btnSave   = findViewById(R.id.btn_save);
        llQrArea  = findViewById(R.id.ll_qr_area);
        ivQr      = findViewById(R.id.iv_qr);
        tvQrHint  = findViewById(R.id.tv_qr_hint);
        tvResult  = findViewById(R.id.tv_result);

        ServerPreferences sp = ServerPreferences.get(this);
        etUrl.setText(sp.getBackendUrl());

        // 打开设置页自动查一次后端状态
        checkBackendHealth(false);

        btnTest.setOnClickListener(v -> checkBackendHealth(true));
        btnQr.setOnClickListener(v   -> fetchQrCode());
        btnSave.setOnClickListener(v -> save());
    }

    @Override protected void onDestroy() { super.onDestroy(); stopPoll(); }

    // ── 统一的后端状态检查（自动检查和手动"测试连接"都走这里）─────────────
    //
    // 之前的 bug：手动点"测试连接"看到的结果只是打印文字，从来没有真正
    // 更新过登录状态或启用保存按钮，导致明明显示已登录，按钮却锁着。
    // 现在无论自动检查还是手动测试，只要后端确认已登录，就统一在这里
    // 更新 ncmLoggedIn 和按钮状态，两条路径行为一致。

    private void checkBackendHealth(boolean showToButton) {
        saveUrl();

        if (!ServerPreferences.get(this).isConfigured()) {
            if (showToButton) show("请先填写后端服务地址", true);
            return;
        }

        if (showToButton) {
            btnTest.setEnabled(false);
            show("测试中...", false);
        }

        new Thread(() -> {
            try {
                JSONObject h = BackendClient.get(this).health();
                boolean loggedIn = h.optBoolean("logged_in", false);
                boolean olOk     = h.optBoolean("ol_ok",     false);
                boolean indexed  = h.optBoolean("indexed",   false);

                runOnUiThread(() -> {
                    if (loggedIn) {
                        ncmLoggedIn = true;
                        btnSave.setEnabled(true);
                    }
                    // 这三项就是后端 /health 实际返回的全部字段。
                    // 以前这里还显示"就绪歌曲/上传中"两个计数，那是后端早期
                    // 带预取队列版本的字段，队列拆掉之后后端再没返回过它们，
                    // optInt 拿不到就恒显示 0，白白让人以为队列是空的。
                    show("✓ 后端连接正常\n"
                            + "  网易云:   " + (loggedIn ? "已登录" : "未登录") + "\n"
                            + "  OpenList: " + (olOk    ? "已连接" : "未连接") + "\n"
                            + "  歌曲索引: " + (indexed ? "已建立" : "未建立"), false);
                    if (showToButton) btnTest.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (showToButton) {
                        show("✗ 无法连接: " + e.getMessage(), true);
                        btnTest.setEnabled(true);
                    }
                    // 静默的自动检查失败不打扰用户，等手动测试或扫码登录
                });
            }
        }).start();
    }

    // ── 二维码登录 ──────────────────────────────────────────────────────────

    private void fetchQrCode() {
        saveUrl();
        stopPoll();
        btnQr.setEnabled(false);
        llQrArea.setVisibility(View.GONE);
        show("正在获取二维码...", false);

        new Thread(() -> {
            try {
                JSONObject qr    = BackendClient.get(this).getQrCode();
                String     key   = qr.optString("key");
                byte[]     bytes = Base64.decode(qr.optString("qr_image"), Base64.DEFAULT);
                Bitmap     bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

                runOnUiThread(() -> {
                    ivQr.setImageBitmap(bmp);
                    llQrArea.setVisibility(View.VISIBLE);
                    tvQrHint.setText("请用手机网易云App扫描上方二维码");
                    btnQr.setEnabled(true);
                    show("二维码有效期 3 分钟", false);
                    startPoll(key);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { btnQr.setEnabled(true); show("✗ " + e.getMessage(), true); });
            }
        }).start();
    }

    private void startPoll(String key) {
        pollRunnable = new Runnable() {
            @Override public void run() {
                new Thread(() -> {
                    try {
                        int status = BackendClient.get(SettingsActivity.this).checkQrStatus(key);
                        runOnUiThread(() -> handleStatus(status));
                    } catch (Exception e) { pollHandler.postDelayed(this, 2000); }
                }).start();
            }
        };
        pollHandler.postDelayed(pollRunnable, 2000);
    }

    private void handleStatus(int status) {
        switch (status) {
            case 801: tvQrHint.setText("等待扫码..."); pollHandler.postDelayed(pollRunnable, 2000); break;
            case 802: tvQrHint.setText("已扫码，请确认登录"); pollHandler.postDelayed(pollRunnable, 2000); break;
            case 803:
                stopPoll(); ncmLoggedIn = true;
                llQrArea.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                show("✓ 网易云登录成功，点保存开始播放", false);
                break;
            case 800:
                stopPoll(); show("二维码已过期，请重新获取", true);
                break;
            default: pollHandler.postDelayed(pollRunnable, 2000); break;
        }
    }

    private void stopPoll() {
        if (pollRunnable != null) { pollHandler.removeCallbacks(pollRunnable); pollRunnable = null; }
    }

    // ── 保存 ────────────────────────────────────────────────────────────────

    private void saveUrl() {
        ServerPreferences.get(this).setBackendUrl(etUrl.getText().toString().trim());
    }

    private void save() {
        if (!ncmLoggedIn) { show("请先完成网易云扫码登录", true); return; }
        saveUrl();
        startService(new Intent(this, MusicService.class));
        show("✓ 已保存", false);
        btnSave.postDelayed(this::finish, 800);
    }

    private void show(String msg, boolean err) {
        runOnUiThread(() -> {
            tvResult.setVisibility(View.VISIBLE);
            tvResult.setText(msg);
            tvResult.setTextColor(getResources().getColor(err ? R.color.error : R.color.accent));
        });
    }
}
