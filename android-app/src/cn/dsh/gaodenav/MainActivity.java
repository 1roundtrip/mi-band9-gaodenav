package cn.dsh.gaodenav;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 手环导航 · 主界面
 *
 *  状态卡片：手环连接（已连接/未连接 + 节点 ID + 监听状态）、已下发帧数、导航状态
 *  高德 Key 卡片：首次使用填写高德 Key（不内置在安装包里），已配置则显示掩码并可修改
 *  主按钮「开始导航」→ NavSdkActivity
 *  次按钮「连接手环」→ Bridge.connect
 *  底部：版本号
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF161B22;
    private static final int ACCENT = 0xFF3DDC97;
    private static final int TEXT = 0xFFE8EEF4;
    private static final int SUB = 0xFF8A8F98;
    private static final int WARN = 0xFFE0B25A;

    private TextView bandStateView;
    private TextView bandDetailView;
    private TextView framesView;
    private TextView navStateView;
    private TextView errorView;

    // 高德 Key 卡片
    private TextView keyStatusView;
    private EditText keyInput;
    private Button keySaveBtn;
    private Button keyEditBtn;
    private TextView keyHintView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            try {
                render();
            } catch (Throwable ignore) {
                // ignore
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bridge.get().init(this);
        setContentView(buildUi());
        renderKeyCard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bridge.get().init(this);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));

        root.addView(text("手环导航", TEXT, 26, true));
        root.addView(text("高德导航 · 小米手环互联", SUB, 12, false), marginTop(dp(6)));

        // ---- 手环状态卡片 ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 16));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        card.addView(text("手环连接", SUB, 11, false));
        bandStateView = text("未连接", WARN, 20, true);
        card.addView(bandStateView, marginTop(dp(4)));
        bandDetailView = text("节点：-   监听：未注册", SUB, 11, false);
        card.addView(bandDetailView, marginTop(dp(4)));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF232A33);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerLp.topMargin = dp(12);
        dividerLp.bottomMargin = dp(12);
        card.addView(divider, dividerLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        framesView = text("已下发 0 帧 / 成功 0 帧", TEXT, 12, false);
        navStateView = text("导航空闲", SUB, 12, false);
        row.addView(framesView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(navStateView);
        card.addView(row);

        errorView = text("", 0xFFFF6B6B, 11, false);
        card.addView(errorView, marginTop(dp(8)));

        root.addView(card, cardLayoutParams(dp(22)));

        // ---- 高德 Key 卡片 ----
        LinearLayout keyCard = new LinearLayout(this);
        keyCard.setOrientation(LinearLayout.VERTICAL);
        keyCard.setBackground(rounded(CARD, 16));
        keyCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        keyCard.addView(text("高德 Key", SUB, 11, false));
        keyStatusView = text("未配置", WARN, 16, true);
        keyCard.addView(keyStatusView, marginTop(dp(4)));

        keyHintView = text("首次使用请填写：高德开放平台 → 创建应用 → 添加 Key"
                + "（服务平台选「Android 平台」，PackageName 填 cn.dsh.gaodenav，"
                + "SHA1 见随包使用说明，两栏都填），把生成的 32 位 Key 粘贴到下面。",
                SUB, 10, false);
        keyCard.addView(keyHintView, marginTop(dp(6)));

        keyInput = new EditText(this);
        keyInput.setHint("粘贴 32 位高德 Key");
        keyInput.setTextColor(TEXT);
        keyInput.setHintTextColor(0xFF6F7680);
        keyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT);
        keyCard.addView(keyInput, marginTop(dp(8)));

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keySaveBtn = smallButton("保存 Key", new View.OnClickListener() {
            public void onClick(View v) {
                saveKey();
            }
        });
        keyEditBtn = smallButton("修改", new View.OnClickListener() {
            public void onClick(View v) {
                showKeyEditor(true);
            }
        });
        keyRow.addView(keySaveBtn, weight1());
        keyRow.addView(keyEditBtn, weight1());
        keyCard.addView(keyRow, marginTop(dp(8)));

        root.addView(keyCard, cardLayoutParams(dp(12)));

        // ---- 主按钮：开始导航 ----
        Button start = pill("开始导航", ACCENT, 0xFF0E1116, 17, true);
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AmapKey.hasKey(MainActivity.this)) {
                    keyStatusView.setTextColor(0xFFFF6B6B);
                    keyStatusView.setText("请先填写高德 Key 再开始导航");
                    showKeyEditor(true);
                    return;
                }
                try {
                    startActivity(new Intent(MainActivity.this, NavSdkActivity.class));
                } catch (Throwable t) {
                    Repo.log("打开导航页失败: " + t);
                }
            }
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        startLp.topMargin = dp(20);
        root.addView(start, startLp);

        // ---- 次按钮：连接手环 ----
        Button connect = pill("连接手环", 0xFF1B2330, ACCENT, 14, false);
        connect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Repo.log("手动连接手环");
                Bridge.get().connect(true);
            }
        });
        LinearLayout.LayoutParams connectLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        connectLp.topMargin = dp(12);
        root.addView(connect, connectLp);

        // ---- 底部版本 ----
        TextView version = text("v1.0.0", 0xFF6F7680, 11, false);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionLp.topMargin = dp(26);
        versionLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(version, versionLp);

        scroll.addView(root);
        return scroll;
    }

    // ---------------------------------------------------------------- 高德 Key

    private void renderKeyCard() {
        boolean has = AmapKey.hasKey(this);
        if (has) {
            keyStatusView.setTextColor(ACCENT);
            keyStatusView.setText("已配置 ✓  " + AmapKey.mask(this));
            showKeyEditor(false);
        } else {
            keyStatusView.setTextColor(WARN);
            keyStatusView.setText("未配置（不填无法算路）");
            showKeyEditor(true);
        }
    }

    private void showKeyEditor(boolean show) {
        if (keyInput != null) {
            keyInput.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && AmapKey.hasKey(this)) {
                keyInput.setText(AmapKey.get(this));
            }
        }
        if (keySaveBtn != null) {
            keySaveBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (keyEditBtn != null) {
            keyEditBtn.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void saveKey() {
        String raw = keyInput.getText() == null ? "" : keyInput.getText().toString().trim();
        if (raw.length() == 0) {
            keyStatusView.setTextColor(0xFFFF6B6B);
            keyStatusView.setText("请输入高德 Key");
            return;
        }
        if (!AmapKey.save(this, raw)) {
            keyStatusView.setTextColor(0xFFFF6B6B);
            keyStatusView.setText("保存失败，请重试");
            return;
        }
        if (!AmapKey.looksValid(raw)) {
            // 只提示，不阻止（部分账号的 Key 形态可能不同）
            keyStatusView.setTextColor(WARN);
            keyStatusView.setText("已保存，但格式不像 32 位 Key，请确认没有复制错");
        } else {
            keyStatusView.setTextColor(ACCENT);
            keyStatusView.setText("已配置 ✓  " + AmapKey.mask(this));
        }
        keyInput.setText("");
        showKeyEditor(false);
        Repo.log("高德 Key 已保存（长度 " + raw.length() + "）");
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        Bridge bridge = Bridge.get();
        boolean ready = bridge.isReady();
        bandStateView.setText(ready ? "已连接" : bridge.stateText());
        bandStateView.setTextColor(ready ? ACCENT : WARN);
        bandDetailView.setText("节点：" + bridge.nodeIdText()
                + "   监听：" + (bridge.isListening() ? "已注册" : "未注册"));
        framesView.setText("已下发 " + bridge.sentCount() + " 帧 / 成功 " + bridge.okCount() + " 帧");
        navStateView.setText(Repo.navActive ? "导航进行中" : "导航空闲");
        String err = Repo.lastError;
        if (err != null && err.length() > 0) {
            errorView.setText(err);
            errorView.setVisibility(View.VISIBLE);
        } else {
            errorView.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private TextView text(String s, int color, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private Button pill(String label, int bgColor, int textColor, float sp, boolean bold) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(textColor);
        b.setBackground(rounded(bgColor, 16));
        if (bold) {
            b.setTypeface(Typeface.DEFAULT_BOLD);
        }
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(ACCENT);
        b.setBackground(rounded(0xFF1B2330, 12));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams cardLayoutParams(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
