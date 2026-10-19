package com.fmplayer;

import android.app.AlertDialog;
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
    private Button       btnTest, btnQr, btnSave, btnMode;
    private LinearLayout llQrArea;
    private ImageView    ivQr;
    private TextView     tvQrHint, tvResult;

    private final Handler pollHandler = new Handler();
    private Runnable      pollRunnable;
    private boolean       ncmLoggedIn = false;

    /** 设置页细分曲风/场景模式选项（9种，作为首页基础模式的弥补） */
    private static final String[] MODE_VALUES  = {
        "FOCUS", "EXERCISE", "NIGHT_EMO", "MANYAO", "FOLK", "JAZZ", "CHINESE", "ENGLISH", "YUEYU"
    };
    private static final String[] MODE_LABELS  = {
        "纯音乐（FOCUS）", "节奏（EXERCISE）", "伤感（NIGHT_EMO）", "慢摇DJ（MANYAO）",
        "民谣（FOLK）", "爵士（JAZZ）", "华语（CHINESE）", "欧美（ENGLISH）", "粤语（YUEYU）"
    };
    private String currentMode = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etUrl     = findViewById(R.id.et_backend_url);
        btnTest   = findViewById(R.id.btn_test);
        btnQr     = findViewById(R.id.btn_get_qr);
        btnSave   = findViewById(R.id.btn_save);
        btnMode   = findViewById(R.id.btn_mode);
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
        btnMode.setOnClickListener(v -> showModePicker());
        loadMode();
    }

    @Override protected void onDestroy() { super.onDestroy(); stopPoll(); }

    // ── 统一的后端状态检查（自动检查和手动"测试连接"都走这里）─────────────

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
                String  userName = h.optString("user_name", "");
                boolean olOk     = h.optBoolean("ol_ok",     false);
                boolean indexed  = h.optBoolean("indexed",   false);

                runOnUiThread(() -> {
                    if (loggedIn) {
                        ncmLoggedIn = true;
                        btnSave.setEnabled(true);
                    }
                    String ncmStatus = "未登录";
                    if (loggedIn) {
                        ncmStatus = (userName != null && !userName.isEmpty())
                                ? "已登录（" + userName + "）"
                                : "已登录";
                    }
                    show("✓ 后端连接正常\n"
                            + "  网易云:   " + ncmStatus + "\n"
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
                // 刷新健康状态以获取新绑定的用户名
                checkBackendHealth(false);
                show("✓ 网易云登录成功，点保存开始播放", false);
                // 扫码成功后，通知 MusicService 重置会话与历史列表，彻底防串味
                Intent resetIntent = new Intent(this, MusicService.class);
                resetIntent.setAction(MusicService.ACTION_RESET_SESSION);
                startService(resetIntent);
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

    // ── 私人FM推荐模式：读取 + 选择弹窗 ─────────────────────────────────────
    //
    // 模式存在后端（/data/users.json），App 只是读写。打开设置页时回读一次；
    // 点按钮弹单选列表，选中即 POST 后端并回读确认——不搞本地缓存，避免
    // 车机和后端两处状态不一致。

    private void loadMode() {
        saveUrl();   // 先存地址，防止用户改了 URL 还没点测试就读错后端
        if (!ServerPreferences.get(this).isConfigured()) {
            btnMode.setText("推荐模式：请先配置后端地址");
            btnMode.setEnabled(false);
            return;
        }
        btnMode.setEnabled(true);
        btnMode.setText("推荐模式：加载中...");
        new Thread(() -> {
            String mode = null;
            try { mode = BackendClient.get(this).getMode(); } catch (Exception ignored) { }
            final String m = mode;
            runOnUiThread(() -> {
                if (m == null) {
                    btnMode.setText("推荐模式：读取失败，点此重试");
                    currentMode = null;
                } else {
                    currentMode = m;
                    btnMode.setText("推荐模式：" + labelOf(m));
                }
            });
        }).start();
    }

    private String labelOf(String mode) {
        if (mode == null) return "未设置";
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i].equals(mode)) return MODE_LABELS[i];
        }
        if ("DEFAULT".equalsIgnoreCase(mode)) return "默认模式（常规私人FM）";
        if ("EXPLORE".equalsIgnoreCase(mode)) return "探索模式（首页）";
        if ("COMMUTE".equalsIgnoreCase(mode) || "SCENE_RCMD".equalsIgnoreCase(mode)) return "开车模式（首页）";
        if ("FAMILIAR".equalsIgnoreCase(mode)) return "熟悉模式";
        return mode;   // 后端将来加了新模式，App 未升级时至少把值显示出来
    }

    private void showModePicker() {
        if (currentMode == null) { loadMode(); return; }

        // 单选列表：若当前是细分模式则打钩，若是首页基础模式（DEFAULT 等）则都不打钩
        int checked = -1;
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i].equals(currentMode)) { checked = i; break; }
        }

        new AlertDialog.Builder(this)
                .setTitle("细分场景/曲风定制")
                .setSingleChoiceItems(MODE_LABELS, checked, (dialog, which) -> {
                    dialog.dismiss();
                    final String target = MODE_VALUES[which];
                    if (target.equals(currentMode)) return;
                    btnMode.setText("推荐模式：切换中...");
                    new Thread(() -> {
                        try {
                            final String confirmed = BackendClient.get(this).setMode(target);
                            runOnUiThread(() -> {
                                currentMode = confirmed;
                                btnMode.setText("推荐模式：" + labelOf(confirmed));
                                show("✓ 已切换为" + labelOf(confirmed) + "，下一首生效", false);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                btnMode.setText("推荐模式：" + labelOf(currentMode));
                                show("✗ 切换失败: " + e.getMessage(), true);
                            });
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
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
