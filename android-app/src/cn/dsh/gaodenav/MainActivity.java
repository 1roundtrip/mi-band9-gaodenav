package cn.dsh.gaodenav;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeAddress;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;

/**
 * 手环导航 · 首页（高德风格精简版）
 *
 *  全屏地图 + 顶部一条搜索栏 + 右上角定位 + 底部一张「上下文」卡片。
 *  选目的地不再需要跳到导航页：
 *    - 在地图上「长按」任意位置 → 落大头针 + 逆地理解析地址 → 卡片变「去这里」
 *    - 顶部搜索栏输入地名 / 坐标 / 高德分享链接 → 交给导航页统一解析
 *    - 粘贴按钮读剪贴板，方便从高德 App「分享 → 复制链接」直接导入
 *  手环状态、高德 Key 等调试信息收进「设置」抽屉，不占主界面。
 */
public class MainActivity extends Activity {

    private static final int BLUE = 0xFF1677FF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT_DARK = 0xFF1A1A1A;
    private static final int TEXT_GRAY = 0xFF8A9099;
    private static final int GREEN = 0xFF22B573;

    private static final int ICON_MAP = 0;
    private static final int ICON_ROUTE = 1;
    private static final int ICON_SETTINGS = 2;
    private static final int ICON_LOCATE = 3;

    /** 定位权限请求码 */
    private static final int REQ_LOCATION_PERM = 3001;
    /** 是否已成功定位到当前位置（进入首页自动定位用） */
    private boolean locatedOnce;

    private MapView mapView;
    private AMap aMap;
    private AMapLocationClient locationClient;
    private Marker myMarker;

    /** 首页地图选点：长按落点 + 逆地理地址 */
    private Marker pickMarker;
    private GeocodeSearch geocodeSearch;
    private LatLng pickedLatLng;
    private String pickedName = "";
    private String pickedAddress = "";

    private EditText searchInput;
    private TextView destTitleView;
    private TextView destSubView;
    private Button goBtn;
    /** 首页「最近去过」列表（本地历史） */
    private LinearLayout recentBox;
    private View settingsSheet;

    // 设置抽屉里的 Key 编辑器
    private TextView keyStatusView;
    private EditText keyInput;
    private Button keySaveBtn;
    private Button keyEditBtn;
    private TextView bandDetailView;
    private TextView framesView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            try {
                renderStatus();
            } catch (Throwable ignore) {
                // ignore
            }
            handler.postDelayed(this, 2000L);
        }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        // 隐私合规：必须在任何高德 SDK 调用之前
        AmapPrivacy.init(this);

        boolean hasKey = AmapKey.hasKey(this);
        if (hasKey) {
            AmapKey.apply(this);
        }

        setContentView(buildUi(hasKey));

        if (hasKey) {
            try {
                mapView = (MapView) findViewById(1001);
                mapView.onCreate(savedInstanceState);
                aMap = mapView.getMap();
                aMap.getUiSettings().setZoomControlsEnabled(false);
                aMap.getUiSettings().setMyLocationButtonEnabled(false);
                // 默认中国范围视角；定位成功后会平滑移动到当前位置
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(35.0, 105.0), 4.5f));
                // 首页地图选点：长按落大头针
                aMap.setOnMapLongClickListener(new AMap.OnMapLongClickListener() {
                    public void onMapLongClick(LatLng latLng) {
                        onPick(latLng);
                    }
                });
                initLocationClient();
                // 进入首页即定位到当前位置：有权限直接定位，无权限先申请
                requestLocationThenLocate();
                Toast.makeText(this, "长按地图任意位置可设置目的地", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Repo.log("[首页] MapView 初始化失败: " + t);
            }
        }

        renderKeyCard();
        handler.post(refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (mapView != null) {
                mapView.onResume();
            }
        } catch (Throwable ignore) {
            // ignore
        }
        Bridge.get().init(this);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        renderRecent();
    }

    /** 首页「最近去过」：读本地历史，点一条直接带坐标去导航（高德首页的习惯） */
    private void renderRecent() {
        if (recentBox == null) {
            return;
        }
        recentBox.removeAllViews();
        java.util.List<History.Dest> dests = History.recentDestinations(this);
        if (dests.isEmpty() || pickedLatLng != null) {
            recentBox.setVisibility(View.GONE);
            return;
        }
        recentBox.setVisibility(View.VISIBLE);
        // 表头：最近去过 + 清除（长按某一条可只删那条）
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = new TextView(this);
        head.setText("最近去过");
        head.setTextColor(TEXT_GRAY);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        headRow.addView(head, weight1());
        TextView clear = new TextView(this);
        clear.setText("清除");
        clear.setTextColor(BLUE);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clear.setPadding(dp(10), dp(2), 0, dp(2));
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("清除「最近去过」？")
                        .setMessage("只清除本机记录，不影响收藏和地图上的点。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清除", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                History.clearDestinations(MainActivity.this);
                                renderRecent();
                                Toast.makeText(MainActivity.this, "已清除最近去过", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            }
        });
        headRow.addView(clear);
        recentBox.addView(headRow, margins(0, dp(2), 0, dp(4)));
        int n = Math.min(dests.size(), 3);
        for (int i = 0; i < n; i++) {
            final History.Dest d = dests.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(0xFFF2F4F7, 10));
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            TextView name = new TextView(this);
            name.setText("↺ " + d.name);
            name.setTextColor(TEXT_DARK);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (searchInput != null) {
                        searchInput.setText("");
                    }
                    pickedLatLng = new LatLng(d.lat, d.lng);
                    pickedName = d.name;
                    pickedAddress = "";
                    onGoHere();
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    History.removeDestination(MainActivity.this, d);
                    renderRecent();
                    Toast.makeText(MainActivity.this, "已删除「" + d.name + "」", Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            recentBox.addView(row, margins(0, 0, 0, dp(6)));
        }
    }

    @Override
    protected void onPause() {
        try {
            if (mapView != null) {
                mapView.onPause();
            }
        } catch (Throwable ignore) {
            // ignore
        }
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            if (mapView != null) {
                mapView.onDestroy();
            }
        } catch (Throwable ignore) {
            // ignore
        }
        try {
            if (locationClient != null) {
                locationClient.stopLocation();
                locationClient.onDestroy();
            }
        } catch (Throwable ignore) {
            // ignore
        }
        geocodeSearch = null;
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            if (mapView != null) {
                mapView.onSaveInstanceState(outState);
            }
        } catch (Throwable ignore) {
            // ignore
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsSheet != null && settingsSheet.getVisibility() == View.VISIBLE) {
            settingsSheet.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- UI

    /** hasKey=false 时用提示卡代替地图，仍可进设置填 Key */
    private View buildUi(boolean hasKey) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF2F4F7);

        if (hasKey) {
            MapView mv = new MapView(this);
            mv.setId(1001);
            root.addView(mv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout placeholder = new LinearLayout(this);
            placeholder.setOrientation(LinearLayout.VERTICAL);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackgroundColor(0xFFE8EDF3);
            TextView t = new TextView(this);
            t.setText("地图需要高德 Key 才能显示");
            t.setTextColor(TEXT_GRAY);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            placeholder.addView(t);
            root.addView(placeholder, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // ---- 顶部唯一一条：搜索栏（搜索图标 + 输入 + 粘贴 + 搜索）----
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(rounded(WHITE, 24));
        searchBar.setPadding(dp(12), 0, dp(5), 0);
        searchBar.setElevation(dp(4));

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(android.R.drawable.ic_menu_search);
        searchIcon.setColorFilter(BLUE);

        searchInput = new EditText(this);
        searchInput.setHint("搜地点 / 粘贴高德链接");
        searchInput.setTextColor(TEXT_DARK);
        searchInput.setHintTextColor(TEXT_GRAY);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setBackgroundColor(Color.TRANSPARENT);

        Button pasteBtn = smallButton("粘贴", new View.OnClickListener() {
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        Button goSearchBtn = new Button(this);
        goSearchBtn.setText("搜索");
        goSearchBtn.setAllCaps(false);
        goSearchBtn.setTextColor(WHITE);
        goSearchBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        goSearchBtn.setBackground(rounded(BLUE, 20));
        goSearchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startNavWithInput();
            }
        });

        searchBar.addView(searchIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.leftMargin = dp(8);
        searchBar.addView(searchInput, inputLp);
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(dp(46), dp(30));
        searchBar.addView(pasteBtn, pasteLp);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(58), dp(34));
        searchLp.leftMargin = dp(5);
        searchBar.addView(goSearchBtn, searchLp);

        FrameLayout.LayoutParams sbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        sbLp.gravity = Gravity.TOP;
        sbLp.leftMargin = dp(12);
        sbLp.rightMargin = dp(12);
        sbLp.topMargin = dp(12);
        root.addView(searchBar, sbLp);

        // ---- 右上角：定位按钮（自绘靶心图标）----
        ImageButton locateBtn = new ImageButton(this);
        locateBtn.setImageDrawable(icon(ICON_LOCATE, BLUE));
        locateBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        locateBtn.setBackground(rounded(WHITE, 22));
        locateBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        locateBtn.setElevation(dp(3));
        locateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                locateMe();
            }
        });
        FrameLayout.LayoutParams locLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        locLp.gravity = Gravity.TOP | Gravity.RIGHT;
        locLp.rightMargin = dp(12);
        locLp.topMargin = dp(66);
        root.addView(locateBtn, locLp);

        // ---- 底部：一张上下文卡片（选点前是提示，选点后是地址 + 去这里）----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(rounded(WHITE, 18));
        bottom.setPadding(dp(16), dp(10), dp(16), dp(14));
        bottom.setElevation(dp(6));

        // 高德 BottomSheet 标志性的小拖条
        View handle = new View(this);
        handle.setBackground(rounded(0xFFDDE2E8, 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER;
        bottom.addView(handle, hp);

        destTitleView = new TextView(this);
        destTitleView.setText("去哪儿？");
        destTitleView.setTextColor(TEXT_DARK);
        destTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        destTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        bottom.addView(destTitleView, margins(0, dp(8), 0, 0));

        destSubView = new TextView(this);
        destSubView.setText("长按地图任意位置选点，或搜索地名 / 粘贴高德链接");
        destSubView.setTextColor(TEXT_GRAY);
        destSubView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bottom.addView(destSubView, margins(0, dp(4), 0, dp(12)));

        // 高德首页式「最近去过」：只显示 3 条，点一条直接带坐标进导航
        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(recentBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        goBtn = new Button(this);
        goBtn.setText("去这里");
        goBtn.setAllCaps(false);
        goBtn.setTextColor(WHITE);
        goBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setBackground(rounded(BLUE, 12));
        goBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onGoHere();
            }
        });
        bottom.addView(goBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.BOTTOM;
        bLp.leftMargin = dp(12);
        bLp.rightMargin = dp(12);
        bLp.bottomMargin = dp(74);
        root.addView(bottom, bLp);

        // ---- 底部功能栏（地图 / 路线 / 设置），高德风格的固定 Tab ----
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(WHITE);
        tabBar.setElevation(dp(4));
        tabBar.addView(tabItem(ICON_MAP, "地图", true, new View.OnClickListener() {
            public void onClick(View v) {
                locateMe();
            }
        }), weight1());
        tabBar.addView(tabItem(ICON_ROUTE, "路线", false, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, NavSdkActivity.class));
                } catch (Throwable t) {
                    Repo.log("打开路线页失败: " + t);
                }
            }
        }), weight1());
        tabBar.addView(tabItem(ICON_SETTINGS, "设置", false, new View.OnClickListener() {
            public void onClick(View v) {
                settingsSheet.setVisibility(View.VISIBLE);
                renderKeyCard();
            }
        }), weight1());
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        tbLp.gravity = Gravity.BOTTOM;
        root.addView(tabBar, tbLp);

        // ---- 设置抽屉（覆盖全屏，默认隐藏）----
        settingsSheet = buildSettingsSheet();
        root.addView(settingsSheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // FrameLayout 按 elevation 决定绘制顺序：不抬高会被底部卡片(6)/搜索栏(4) 盖住
        settingsSheet.setElevation(dp(30));
        settingsSheet.setVisibility(View.GONE);

        return root;
    }

    /** 设置抽屉：手环状态 / 高德 Key / 连接统计，点空白处关闭 */
    private View buildSettingsSheet() {
        FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x88000000);
        sheet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                settingsSheet.setVisibility(View.GONE);
            }
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(WHITE, 18));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setElevation(dp(4));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 吃掉点击，避免穿透关闭
            }
        });

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(TEXT_DARK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("手环连接状态与高德 Key。这两项配置一次即可，平时不用打开。");
        sub.setTextColor(TEXT_GRAY);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        card.addView(sub, margins(0, dp(4), 0, dp(14)));

        // 手环状态
        bandDetailView = new TextView(this);
        bandDetailView.setText("节点：-   监听：未注册");
        bandDetailView.setTextColor(TEXT_DARK);
        bandDetailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(bandDetailView);

        framesView = new TextView(this);
        framesView.setText("已下发 0 帧 / 成功 0 帧");
        framesView.setTextColor(TEXT_GRAY);
        framesView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        card.addView(framesView, margins(0, dp(4), 0, dp(8)));

        Button connect = new Button(this);
        connect.setText("连接手环");
        connect.setAllCaps(false);
        connect.setTextColor(BLUE);
        connect.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        connect.setBackground(outline(BLUE, 10));
        connect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Bridge.get().connect(true);
                Toast.makeText(MainActivity.this, "正在连接手环…", Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(connect, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFECEFF3);
        card.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 高德 Key
        TextView keyTitle = new TextView(this);
        keyTitle.setText("高德 Key");
        keyTitle.setTextColor(TEXT_DARK);
        keyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(keyTitle, margins(0, dp(12), 0, 0));

        keyStatusView = new TextView(this);
        keyStatusView.setText("未配置");
        keyStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(keyStatusView, margins(0, dp(4), 0, 0));

        TextView keyHint = new TextView(this);
        keyHint.setText("高德开放平台创建 Android 平台 Key，绑定下面两项。"
                + "Key 只存在本机，不内置安装包。");
        keyHint.setTextColor(TEXT_GRAY);
        keyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        card.addView(keyHint, margins(0, dp(4), 0, dp(4)));

        // 地图空白最常见的原因：高德后台没绑这个包名 + 签名 SHA1，或 Key 类型选成了 Web 服务
        TextView signView = new TextView(this);
        signView.setText("包名：" + getPackageName() + "\n签名 SHA1：" + appSha1()
                + "\n（长按可复制，去高德后台核对）");
        signView.setTextColor(0xFF5A6472);
        signView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        signView.setTextIsSelectable(true);
        card.addView(signView, margins(0, dp(2), 0, dp(8)));

        keyInput = new EditText(this);
        keyInput.setHint("粘贴 32 位高德 Key");
        keyInput.setTextColor(TEXT_DARK);
        keyInput.setHintTextColor(TEXT_GRAY);
        keyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT);
        card.addView(keyInput);

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
        card.addView(keyRow, margins(0, dp(6), 0, 0));

        TextView version = new TextView(this);
        version.setText("手环导航 v" + appVersion());
        version.setTextColor(0xFFB0B6BF);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        version.setGravity(Gravity.CENTER);
        card.addView(version, margins(0, dp(12), 0, 0));

        Button close = new Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextColor(TEXT_DARK);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setBackground(rounded(0xFFEEF1F5, 10));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                settingsSheet.setVisibility(View.GONE);
            }
        });
        card.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = dp(24);
        lp.rightMargin = dp(24);
        sheet.addView(card, lp);
        return sheet;
    }

    // ---------------------------------------------------------------- 地图选点

    /** 长按地图：落大头针 + 逆地理解析地址 + 更新底部卡片 */
    private void onPick(LatLng latLng) {
        if (latLng == null) {
            return;
        }
        pickedLatLng = latLng;
        pickedName = "";
        pickedAddress = "";
        destTitleView.setText("正在解析位置…");
        destSubView.setText(String.format(java.util.Locale.US, "%.6f, %.6f",
                latLng.latitude, latLng.longitude));
        try {
            if (aMap != null) {
                if (pickMarker != null) {
                    pickMarker.remove();
                }
                pickMarker = aMap.addMarker(new MarkerOptions()
                        .position(latLng).title("目的地"));
                aMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            }
        } catch (Throwable t) {
            Repo.log("[首页] 选点落针失败: " + t);
        }
        reverseGeocode(latLng);
    }

    /** 逆地理编码：把坐标换成可读地址（复用高德 Search SDK） */
    private void reverseGeocode(final LatLng latLng) {
        try {
            if (geocodeSearch == null) {
                geocodeSearch = new GeocodeSearch(this);
                geocodeSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                    public void onRegeocodeSearched(RegeocodeResult result, int code) {
                        String addr = "";
                        if (code == 1000 && result != null) {
                            RegeocodeAddress address = result.getRegeocodeAddress();
                            if (address != null) {
                                addr = address.getFormatAddress();
                            }
                        }
                        pickedAddress = addr == null ? "" : addr;
                        pickedName = pickedAddress.length() > 0
                                ? pickedAddress
                                : String.format(java.util.Locale.US, "地图选点 %.6f,%.6f",
                                        latLng.latitude, latLng.longitude);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                destTitleView.setText(pickedName);
                                destSubView.setText(pickedAddress.length() > 0
                                        ? pickedAddress
                                        : "（地址解析失败，将用坐标作为名称）");
                            }
                        });
                    }

                    public void onGeocodeSearched(GeocodeResult result, int code) {
                        // 用不到正向地理编码
                    }
                });
            }
            geocodeSearch.getFromLocationAsyn(new RegeocodeQuery(
                    new LatLonPoint(latLng.latitude, latLng.longitude), 200, GeocodeSearch.AMAP));
        } catch (Throwable t) {
            Repo.log("[首页] 逆地理失败: " + t);
            pickedName = String.format(java.util.Locale.US, "地图选点 %.6f,%.6f",
                    latLng.latitude, latLng.longitude);
            destTitleView.setText(pickedName);
            destSubView.setText("（逆地理不可用，将用坐标作为名称）");
        }
    }

    /** 「去这里」：优先用搜索框输入（地名/链接），否则用地图上选的点 */
    private void onGoHere() {
        String input = searchInput == null || searchInput.getText() == null
                ? "" : searchInput.getText().toString().trim();
        if (!TextUtils.isEmpty(input)) {
            startNavWithInput();
            return;
        }
        if (pickedLatLng == null) {
            Toast.makeText(this, "请长按地图选点，或在上方输入目的地", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AmapKey.hasKey(this)) {
            Toast.makeText(this, "请先在「设置」里填写高德 Key", Toast.LENGTH_LONG).show();
            settingsSheet.setVisibility(View.VISIBLE);
            renderKeyCard();
            return;
        }
        try {
            Intent intent = new Intent(this, NavSdkActivity.class);
            intent.putExtra(NavSdkActivity.EXTRA_DEST_LAT, pickedLatLng.latitude);
            intent.putExtra(NavSdkActivity.EXTRA_DEST_LNG, pickedLatLng.longitude);
            intent.putExtra(NavSdkActivity.EXTRA_DEST_NAME, pickedName);
            intent.putExtra(NavSdkActivity.EXTRA_DEST_ADDRESS, pickedAddress);
            startActivity(intent);
        } catch (Throwable t) {
            Repo.log("打开导航页失败: " + t);
            Toast.makeText(this, "打开导航页失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 把搜索框输入交给导航页统一解析（链接 / 坐标 / 关键词） */
    private void startNavWithInput() {
        String input = searchInput == null || searchInput.getText() == null
                ? "" : searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            Toast.makeText(this, "请输入地名、坐标或粘贴高德链接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AmapKey.hasKey(this)) {
            Toast.makeText(this, "请先在「设置」里填写高德 Key", Toast.LENGTH_LONG).show();
            settingsSheet.setVisibility(View.VISIBLE);
            renderKeyCard();
            return;
        }
        try {
            Intent intent = new Intent(this, NavSdkActivity.class);
            intent.putExtra(NavSdkActivity.EXTRA_INPUT, input);
            startActivity(intent);
        } catch (Throwable t) {
            Repo.log("打开导航页失败: " + t);
            Toast.makeText(this, "打开导航页失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                Toast.makeText(this, "剪贴板里没有内容", Toast.LENGTH_SHORT).show();
                return;
            }
            String text = clip.getItemAt(0).coerceToText(this).toString().trim();
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(this, "剪贴板里没有文本", Toast.LENGTH_SHORT).show();
                return;
            }
            searchInput.setText(text);
            Toast.makeText(this, "已粘贴，点「搜索」或「去这里」", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "读取剪贴板失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 进入首页：检查定位权限，有则直接定位，无则申请（Android 6+ 必需） */
    private void requestLocationThenLocate() {
        try {
            int granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
            if (granted == PackageManager.PERMISSION_GRANTED) {
                doStartLocation();
            } else {
                requestPermissions(new String[] {
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION_PERM);
            }
        } catch (Throwable t) {
            Repo.log("[首页] 定位权限检查失败: " + t);
        }
    }

    /** 权限结果：授权后自动定位到当前位置；拒绝则给出明确提示 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION_PERM) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            doStartLocation();
        } else {
            Toast.makeText(this, "未授予定位权限，地图无法定位到您的位置（可在系统设置里开启）",
                    Toast.LENGTH_LONG).show();
            Repo.log("[首页] 定位权限被拒绝");
        }
    }

    /** 真正启动一次定位（静默，进入首页/授权后调用） */
    private void doStartLocation() {
        try {
            if (aMap == null) {
                return;
            }
            if (locationClient == null) {
                initLocationClient();
            }
            if (locationClient != null) {
                locationClient.stopLocation();
                locationClient.startLocation();
            }
        } catch (Throwable t) {
            Repo.log("[首页] 启动定位失败: " + t);
        }
    }

    /** 右上角定位按钮：重新定位当前位置 */
    private void locateMe() {
        if (aMap == null) {
            Toast.makeText(this, "地图未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        int granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION_PERM);
            return;
        }
        doStartLocation();
        Toast.makeText(this, "正在定位…", Toast.LENGTH_SHORT).show();
    }

    private void initLocationClient() {
        try {
            locationClient = new AMapLocationClient(getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setOnceLocation(true);
            option.setNeedAddress(false);
            locationClient.setLocationOption(option);
            locationClient.setLocationListener(new AMapLocationListener() {
                public void onLocationChanged(AMapLocation location) {
                    if (location == null || location.getErrorCode() != 0) {
                        final String err = location == null ? "无定位结果"
                                : ("定位失败 code=" + location.getErrorCode()
                                + " " + location.getErrorInfo());
                        Repo.log("[首页] " + err);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(MainActivity.this,
                                        "定位失败，请到开阔处或检查定位开关：" + err,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }
                    final double lat = location.getLatitude();
                    final double lng = location.getLongitude();
                    final boolean first = !locatedOnce;
                    locatedOnce = true;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                LatLng here = new LatLng(lat, lng);
                                if (aMap != null) {
                                    if (myMarker != null) {
                                        myMarker.remove();
                                    }
                                    myMarker = aMap.addMarker(new MarkerOptions()
                                            .position(here).title("我的位置"));
                                    // 首次进入直接落到当前位置（15.5 级）；之后重新定位平滑移动
                                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                            here, first ? 15.5f : 15f));
                                }
                            } catch (Throwable ignore) {
                                // ignore
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            locationClient = null;
            Repo.log("[首页] 定位客户端初始化失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 状态刷新 / Key

    private void renderStatus() {
        Bridge bridge = Bridge.get();
        if (bandDetailView != null) {
            bandDetailView.setText("节点：" + bridge.nodeIdText()
                    + "   监听：" + (bridge.isListening() ? "已注册" : "未注册"));
        }
        if (framesView != null) {
            framesView.setText("已下发 " + bridge.sentCount() + " 帧 / 成功 "
                    + bridge.okCount() + " 帧");
        }
    }

    private void renderKeyCard() {
        if (keyStatusView == null) {
            return;
        }
        if (AmapKey.hasKey(this)) {
            keyStatusView.setTextColor(GREEN);
            keyStatusView.setText("已配置 ✓  " + AmapKey.mask(this));
            showKeyEditor(false);
        } else {
            keyStatusView.setTextColor(0xFFE0A000);
            keyStatusView.setText("未配置（不填无法算路/显示地图）");
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
        if (TextUtils.isEmpty(raw)) {
            keyStatusView.setTextColor(0xFFE5484D);
            keyStatusView.setText("请输入高德 Key");
            return;
        }
        if (!AmapKey.save(this, raw)) {
            keyStatusView.setTextColor(0xFFE5484D);
            keyStatusView.setText("保存失败，请重试");
            return;
        }
        if (!AmapKey.looksValid(raw)) {
            keyStatusView.setTextColor(0xFFE0A000);
            keyStatusView.setText("已保存，但格式不像 32 位 Key，请确认没复制错");
        } else {
            keyStatusView.setTextColor(GREEN);
            keyStatusView.setText("已配置 ✓  " + AmapKey.mask(this));
        }
        keyInput.setText("");
        showKeyEditor(false);
        Repo.log("高德 Key 已保存（长度 " + raw.length() + "）");

        // Key 必须立即生效：首页在 onCreate 里才决定是否创建 MapView，
        // 只保存不重建的话，屏幕上还是无 Key 时的占位卡，地图永远不会出现。
        // apply 必须在 MapView 创建之前，所以要重建 Activity 而不是只刷新文案。
        AmapKey.apply(this);
        Toast.makeText(this, "Key 已保存，正在加载地图…", Toast.LENGTH_SHORT).show();
        settingsSheet.setVisibility(View.GONE);
        handler.postDelayed(new Runnable() {
            public void run() {
                try {
                    recreate();
                } catch (Throwable t) {
                    Repo.log("[首页] 重建地图失败: " + t);
                    Toast.makeText(MainActivity.this, "请手动杀掉 App 再打开", Toast.LENGTH_LONG).show();
                }
            }
        }, 250L);
    }

    /** 当前 APK 的签名 SHA1：高德 Key 没绑它的话，地图会空白/瓦片加载失败 */
    private String appSha1() {
        // v2/v3 签名方案下 signatures 可能为空，优先取 API28+ 的 signingInfo（反射兼容 minSdk 26）
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), 0x8000000); // GET_SIGNING_CERTIFICATES
            Object signingInfo = android.content.pm.PackageInfo.class
                    .getField("signingInfo").get(pi);
            if (signingInfo != null) {
                Object signers = signingInfo.getClass()
                        .getMethod("getApkContentsSigners").invoke(signingInfo);
                if (signers instanceof android.content.pm.Signature[]) {
                    android.content.pm.Signature[] arr =
                            (android.content.pm.Signature[]) signers;
                    if (arr.length > 0) {
                        return hexSha1(arr[0].toByteArray());
                    }
                }
            }
        } catch (Throwable ignore) {
            // 老系统没这个字段，回退 v1 证书
        }
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi.signatures != null && pi.signatures.length > 0) {
                return hexSha1(pi.signatures[0].toByteArray());
            }
        } catch (Throwable ignore) {
            // ignore
        }
        return "获取失败";
    }

    private String hexSha1(byte[] cert) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA1").digest(cert);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(java.util.Locale.US, "%02X", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "获取失败";
        }
    }

    private String appVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return (v == null || v.length() == 0) ? "?" : v;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 自绘线性图标：风格统一，不依赖系统 drawable */
    private BitmapDrawable icon(int kind, int color) {
        int size = dp(24);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float d = getResources().getDisplayMetrics().density;
        c.scale(d, d);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        if (kind == ICON_MAP) {
            android.graphics.Path map = new android.graphics.Path();
            map.moveTo(4, 6); map.lineTo(9, 4); map.lineTo(15, 6); map.lineTo(20, 4);
            map.lineTo(20, 18); map.lineTo(15, 20); map.lineTo(9, 18); map.lineTo(4, 20);
            map.close();
            c.drawPath(map, p);
            c.drawLine(9, 4, 9, 18, p);
            c.drawLine(15, 6, 15, 20, p);
        } else if (kind == ICON_ROUTE) {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(6, 18, 2.6f, p);
            android.graphics.Path line = new android.graphics.Path();
            line.moveTo(6, 18); line.lineTo(6, 11); line.lineTo(12, 11);
            line.lineTo(12, 6); line.lineTo(18, 6);
            p.setStyle(Paint.Style.STROKE);
            c.drawPath(line, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(18, 6, 2.6f, p);
        } else if (kind == ICON_SETTINGS) {
            for (int i = 0; i < 8; i++) {
                c.save();
                c.translate(12, 12);
                c.rotate(i * 45f);
                p.setStyle(Paint.Style.FILL);
                c.drawRect(-1.6f, -10.6f, 1.6f, -7.4f, p);
                c.restore();
            }
            p.setStyle(Paint.Style.STROKE);
            c.drawCircle(12, 12, 6.2f, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(12, 12, 2.4f, p);
        } else if (kind == ICON_LOCATE) {
            p.setStyle(Paint.Style.STROKE);
            c.drawCircle(12, 12, 6.6f, p);
            c.drawLine(12, 1.5f, 12, 4.5f, p);
            c.drawLine(12, 19.5f, 12, 22.5f, p);
            c.drawLine(1.5f, 12, 4.5f, 12, p);
            c.drawLine(19.5f, 12, 22.5f, 12, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(12, 12, 2.8f, p);
        }
        return new BitmapDrawable(getResources(), bmp);
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(BLUE);
        b.setBackground(rounded(0xFFEEF4FF, 10));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 底部功能栏单项：上方矢量图标 + 下方标签 */
    private View tabItem(int iconKind, String label, boolean active, View.OnClickListener l) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(9), 0, dp(7));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(icon(iconKind, active ? BLUE : TEXT_GRAY));
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setGravity(Gravity.CENTER);
        txt.setTextColor(active ? BLUE : TEXT_GRAY);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txtLp.topMargin = dp(2);
        item.addView(txt, txtLp);

        item.setOnClickListener(l);
        return item;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = right;
        lp.bottomMargin = bottom;
        return lp;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable outline(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(WHITE);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), color);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
