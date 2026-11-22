package cn.dsh.gaodenav;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.enums.TransportType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviPath;
import com.amap.api.navi.model.AMapTravelInfo;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 高德导航 SDK 集成页：AMapNaviView（地图/导航界面）+ AMapNavi（算路/导航状态）
 * + NaviCallbackAdapter（AMapNaviListener）。
 *
 * 数据链路：
 *   AMapNaviListener.onNaviInfoUpdate(NaviInfo)
 *     -> NaviIconMapper（IconType -> turn/中文动作）
 *     -> NavFrame（把手环要的字段拼成 JSON）
 *     -> Bridge（小米互联，复用现有实现）
 *     -> 手环 rpk
 *
 * 目的地：搜索候选列表 / 地图选点 / 坐标输入，三种方式任选。
 *
 * 高德 Key：不内置在安装包里，由用户在 App 主界面填写（AmapKey）；
 * 进入本页时先做隐私合规、再应用 Key，最后才初始化 SDK。
 */
public class NavSdkActivity extends Activity implements NaviCallbackAdapter.Host {

    // 高德风格浅色主题
    private static final int BLUE = 0xFF1677FF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT_DARK = 0xFF1A1A1A;
    private static final int TEXT_GRAY = 0xFF8A9099;
    private static final int GREEN = 0xFF22B573;

    private AMapNaviView naviView;
    private AMapNavi navi;
    private NaviCallbackAdapter callback;

    private FrameLayout root;
    private LinearLayout setupPanel;
    private LinearLayout statusBar;
    private EditText destInput;
    private TextView setupStatus;
    private TextView bandStatusView;

    /** 目的地选择：可收起面板 / 候选列表 / 已选目的地 / 地图选点 */
    private ScrollView setupScroll;
    private Button expandBtn;
    private LinearLayout candidateBox;
    private TextView selectedView;
    private PoiSearch poiSearch;
    private String destName = "";
    private String destAddress = "";
    private NaviLatLng destLatLng;

    /** 途经点：名字 + 坐标（驾车算路时传给 viaList） */
    private static final class Via {
        final String name;
        final NaviLatLng ll;
        Via(String name, NaviLatLng ll) {
            this.name = name;
            this.ll = ll;
        }
    }
    private final List<Via> vias = new ArrayList<Via>();
    private LinearLayout viaBox;
    /** 正在给谁选位置：0=目的地 1=途经点 2=起点。搜索/地图选点回调按此分流 */
    private static final int PICK_DEST = 0, PICK_VIA = 1, PICK_FROM = 2;
    private int pickingTarget = PICK_DEST;
    /** 沿途搜的搜索中心（起点附近），普通搜索为 null */
    private LatLonPoint searchCenter;

    /** 算路成功后的多条路线候选，用户点一条再开始导航 */
    private LinearLayout routeBox;
    private static final class RouteOpt {
        final int id;
        final String label;
        final String time;
        final String detail;
        final boolean fast;
        RouteOpt(int id, String label, String time, String detail, boolean fast) {
            this.id = id;
            this.label = label;
            this.time = time;
            this.detail = detail;
            this.fast = fast;
        }
    }
    private final List<RouteOpt> routeOpts = new ArrayList<RouteOpt>();
    /** 点选预览的路线 id（-1=未选）；选中后只高亮不开始，再点「开始导航」才走 */
    private int previewRouteId = -1;
    /** 仅调试：adb/SEND 带 debug_auto_nav=true 时，算路成功后自动开始导航（便于真机截图验证） */
    private boolean debugAutoNav;
    private Button startNavBtn;
    private LinearLayout prefRow;
    /** 低频设置（途经点 / 选起点 / 驾车偏好）默认折叠，避免首屏被按钮淹没 */
    private LinearLayout morePanel;
    private Button moreBtn;
    /** 出行方式 Tab（高德式文字 Tab，选中高亮） */
    private final TextView[] modeTabs = new TextView[3];
    /** 底部路线卡片（算路成功后浮现，支持折叠/半开/全开三态拖拽） */
    private LinearLayout routeCard;
    private FrameLayout.LayoutParams routeCardLp;
    private TextView routeCardTitle;
    private View dragHandle;
    private int panelState = 1; // 0=折叠 1=半开 2=全开
    private int startY = 0, startH = 0;
    private boolean dragging = false;

    /** 路线链接里指定的起点：非空则算路用它，否则用当前定位 */
    private NaviLatLng planFrom;
    private String fromName = "";
    private TextView fromView;
    /** 驾车策略（来自链接 policy），-1 表示用默认 */
    private int planPolicy = -1;

    /** 导航方式：0=驾车 1=骑行 2=步行 */
    private int naviMode;
    /** 地图选点请求码 */
    private static final int REQ_MAP_PICK = 2001;
    /** 定位权限请求码 */
    private static final int REQ_LOCATION_PERM = 1001;
    /** 首页传入的输入：地名 / 坐标 / 高德分享链接，由本页统一解析 */
    public static final String EXTRA_INPUT = "nav_input";
    /** 首页地图长按选点后直接传入的目的地（经纬度 + 名称 + 地址） */
    public static final String EXTRA_DEST_LAT = "dest_lat";
    public static final String EXTRA_DEST_LNG = "dest_lng";
    public static final String EXTRA_DEST_NAME = "dest_name";
    public static final String EXTRA_DEST_ADDRESS = "dest_address";
    /** 定位 SDK 客户端（起点优先用它，拿不到再退回 LocationManager） */
    private AMapLocationClient locationClient;
    private volatile NaviLatLng cachedFrom;
    /** 最近一次定位的时间（uptimeMillis）；真实导航要求「新鲜定位」 */
    private volatile long locationAtMs;
    /** 新鲜定位的有效期 */
    private static final long FRESH_LOCATION_MS = 30000L;
    /** 正在等待定位回调，拿到后自动继续算路 */
    private volatile boolean waitingLocation;
    /** 等待定位的超时兜底（15s），避免永远卡在「等待定位…」 */
    private static final long LOCATION_TIMEOUT_MS = 15000L;
    private final Runnable locationTimeout = new Runnable() {
        public void run() {
            if (!waitingLocation) {
                return;
            }
            waitingLocation = false;
            setSetupStatus("定位超时：请确认已开启定位开关/定位权限后，再点一次出行方式重试");
            Repo.log("[SDK] 等待定位超时，复位等待状态");
        }
    };
    /** 已停止导航：阻止 NaviInfo 后续回调再往手环发帧 */
    private volatile boolean naviStopped;

    /** 导航结束页：到达/结束后显示用时、距离、重新规划入口 */
    private LinearLayout endPage;
    private TextView endTitleView;
    private TextView endDetailView;
    private long naviStartMs;

    private boolean naviInited;
    private boolean routeStarted;
    private volatile boolean navActive;
    private NaviLatLng pendingTo;

    private String lastTurn = "";
    private String lastAction = "";
    private int frameCount;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            try {
                refreshStatus();
            } catch (Throwable ignore) {
                // ignore
            }
            handler.postDelayed(this, 1000L);
        }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 1) 隐私合规：必须在任何高德 SDK 调用之前（地图/导航/定位/搜索统一入口）
        AmapPrivacy.init(this);

        // 2) 运行时高德 Key（用户在 App 主界面填写，不内置在安装包里）
        if (!AmapKey.hasKey(this)) {
            Repo.log("[SDK] 未配置高德 Key，返回主界面");
            Toast.makeText(this, "请先在主界面填写高德 Key", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        AmapKey.apply(this);

        // 3) AMapNavi 必须在 AMapNaviView 之前初始化（官方 Demo 顺序）
        try {
            navi = AMapNavi.getInstance(getApplicationContext());
            callback = new NaviCallbackAdapter(this);
            navi.addAMapNaviListener(callback);
            navi.setUseInnerVoice(true, true);
            navi.setEmulatorNaviSpeed(75);
            Repo.log("[SDK] AMapNavi 初始化完成");
        } catch (Throwable t) {
            navi = null;
            Repo.log("[SDK] AMapNavi 初始化失败: " + t);
        }

        // 2.5) 定位客户端（起点优先用它；不阻塞，定位到达后缓存坐标）
        initLocationClient();

        // 3) UI + AMapNaviView
        buildUi();
        try {
            naviView.onCreate(savedInstanceState);
            naviView.setAMapNaviViewListener(callback);
            applyNaviUiOptions();
            // 首页已定位过：进入路线页立即把地图带到当前位置，不等二次定位回调
            if (cachedFrom != null && !navActive && destLatLng == null) {
                centerMapOn(cachedFrom.getLatitude(), cachedFrom.getLongitude());
            }
        } catch (Throwable t) {
            Repo.log("[SDK] AMapNaviView.onCreate 异常: " + t);
        }

        // 4) 手环桥
        Bridge bridge = Bridge.get();
        bridge.init(this);
        if (!bridge.isReady()) {
            bridge.connect(true);
        }

        // 5) 定位权限（导航必需）
        try {
            requestPermissions(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION_PERM);
        } catch (Throwable t) {
            Repo.log("[SDK] 请求定位权限失败: " + t);
        }

        handler.removeCallbacks(refresh);
        handler.post(refresh);

        // 6) 首页传入的输入（地名 / 坐标 / 高德分享链接），统一交给本页解析
        debugAutoNav = getIntent() != null && getIntent().getBooleanExtra("debug_auto_nav", false);
        consumeIncomingInput();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (naviView != null) {
                naviView.onResume();
            }
        } catch (Throwable t) {
            Repo.log("naviView.onResume 异常: " + t);
        }
    }

    @Override
    protected void onPause() {
        try {
            if (naviView != null) {
                naviView.onPause();
            }
        } catch (Throwable t) {
            Repo.log("naviView.onPause 异常: " + t);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(locationTimeout);
        // 先真正停掉导航（stopNavi + 标志位），避免退出后 NaviInfo 还继续发帧
        stopNavigation("页面销毁");
        try {
            if (naviView != null) {
                naviView.onDestroy();
            }
        } catch (Throwable ignore) {
            // ignore
        }
        try {
            if (navi != null) {
                navi.destroy();
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
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 高德式二次确认：导航中返回要问一句，避免误触退出
        confirmExit("退出导航？");
    }

    /** 退出/结束导航的二次确认（继续导航 / 退出） */
    private void confirmExit(String title) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("退出后将停止向手环下发导航提示。")
                .setNegativeButton("继续导航", null)
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        stopNavigation("用户确认退出");
                        if (navActive || routeStarted) {
                            showEndPage("导航已结束");
                        } else {
                            finish();
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        naviView = new AMapNaviView(this);
        root.addView(naviView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 导航中极简角标：只显示「手环已投屏」+ 退出。帧数/JSON 调试信息不再上屏。
        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(6), dp(4), dp(6), dp(4));

        bandStatusView = text("● 手环已投屏", WHITE, 12, false);
        bandStatusView.setBackground(rounded(0xCC1677FF, 16));
        bandStatusView.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusBar.addView(bandStatusView);

        Button exitBtn = new Button(this);
        exitBtn.setText("结束");
        exitBtn.setAllCaps(false);
        exitBtn.setTextColor(TEXT_DARK);
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        exitBtn.setBackground(rounded(WHITE, 16));
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exitLp.leftMargin = dp(8);
        exitBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmExit("结束导航？");
            }
        });
        statusBar.addView(exitBtn, exitLp);
        statusBar.setVisibility(View.GONE);
        // 手环状态角标放在 SDK 转向面板下方右侧：面板不挡、
        // 也不压 SDK 左上角的速度表（高德原生界面没有这个元素，属于本 App 特色）
        FrameLayout.LayoutParams sbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.gravity = Gravity.TOP | Gravity.RIGHT;
        sbLp.rightMargin = dp(12);
        sbLp.topMargin = dp(150);
        root.addView(statusBar, sbLp);

        // 目的地选择面板（可收起；高德白底卡片风格）
        setupScroll = new ScrollView(this);
        setupScroll.setBackgroundColor(0xE6FFFFFF);

        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setPadding(dp(16), dp(16), dp(16), dp(14));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("去哪儿", TEXT_DARK, 18, true), weight1());
        titleRow.addView(smallButton("收起", new View.OnClickListener() {
            public void onClick(View v) { collapsePanel(true); }
        }));
        setupPanel.addView(titleRow, margins(0, 0, 0, dp(10)));

        // 起点行：默认「我的位置」，可搜索/地图选点修改，右侧按钮与终点互换
        LinearLayout fromRow = new LinearLayout(this);
        fromRow.setOrientation(LinearLayout.HORIZONTAL);
        fromRow.setGravity(Gravity.CENTER_VERTICAL);
        fromRow.setBackground(rounded(0xFFF2F4F7, 10));
        fromRow.setPadding(dp(12), dp(8), dp(6), dp(8));
        TextView fromDot = text("●", 0xFF2FBF71, 12, false);
        LinearLayout.LayoutParams fromDotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fromDotLp.rightMargin = dp(8);
        fromRow.addView(fromDot, fromDotLp);
        fromView = text("我的位置", TEXT_DARK, 14, false);
        fromRow.addView(fromView, weight1());
        Button swapBtn = new Button(this);
        swapBtn.setText("⇅");
        swapBtn.setAllCaps(false);
        swapBtn.setTextColor(BLUE);
        swapBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        swapBtn.setBackground(null);
        swapBtn.setPadding(dp(10), 0, dp(10), 0);
        swapBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { swapFromDest(); }
        });
        fromRow.addView(swapBtn);
        setupPanel.addView(fromRow, margins(0, 0, 0, dp(8)));

        // 终点行：红点 + 输入框（高德「输入终点」样式），回车即搜索
        LinearLayout destRow = new LinearLayout(this);
        destRow.setOrientation(LinearLayout.HORIZONTAL);
        destRow.setGravity(Gravity.CENTER_VERTICAL);
        destRow.setBackground(rounded(0xFFF2F4F7, 10));
        destRow.setPadding(dp(12), dp(2), dp(12), dp(2));
        TextView destDot = text("●", 0xFFE5484D, 12, false);
        LinearLayout.LayoutParams destDotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        destDotLp.rightMargin = dp(8);
        destRow.addView(destDot, destDotLp);
        destInput = new EditText(this);
        destInput.setHint("输入终点 / 粘贴高德链接");
        destInput.setText("");
        destInput.setSingleLine(true);
        destInput.setTextColor(TEXT_DARK);
        destInput.setHintTextColor(TEXT_GRAY);
        destInput.setBackground(null);
        destInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        destInput.setPadding(0, dp(10), 0, dp(10));
        destInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                pickingTarget = PICK_DEST;
                doSearch();
                return true;
            }
        });
        destRow.addView(destInput, weight1());
        setupPanel.addView(destRow, margins(0, 0, 0, dp(8)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.addView(smallButton("搜索", new View.OnClickListener() {
            public void onClick(View v) {
                pickingTarget = PICK_DEST;
                doSearch();
            }
        }), weight1());
        searchRow.addView(smallButton("地图选点", new View.OnClickListener() {
            public void onClick(View v) {
                pickingTarget = PICK_DEST;
                openMapPicker();
            }
        }), weight1());
        moreBtn = smallButton("更多", new View.OnClickListener() {
            public void onClick(View v) { toggleMore(); }
        });
        searchRow.addView(moreBtn, weight1());
        setupPanel.addView(searchRow, margins(0, 0, 0, dp(8)));

        selectedView = text("未选择目的地", BLUE, 13, true);
        setupPanel.addView(selectedView, margins(0, 0, 0, dp(6)));

        candidateBox = new LinearLayout(this);
        candidateBox.setOrientation(LinearLayout.VERTICAL);
        setupPanel.addView(candidateBox, margins(0, 0, 0, dp(6)));

        // 低频设置（途经点 / 选起点 / 驾车偏好）默认折叠，点「更多」才展开
        morePanel = new LinearLayout(this);
        morePanel.setOrientation(LinearLayout.VERTICAL);
        morePanel.setVisibility(View.GONE);

        // 选起点（也可在主界面点起点行互换）
        morePanel.addView(smallButton("在地图上选起点", new View.OnClickListener() {
            public void onClick(View v) {
                pickingTarget = PICK_FROM;
                openMapPicker();
            }
        }), margins(0, dp(6), 0, dp(2)));

        // 途经点（驾车有效）：搜索/地图选点添加，长按删除
        morePanel.addView(text("途经点（驾车有效，可多个）", TEXT_GRAY, 12, false),
                margins(0, dp(6), 0, dp(2)));
        viaBox = new LinearLayout(this);
        viaBox.setOrientation(LinearLayout.VERTICAL);
        morePanel.addView(viaBox);
        LinearLayout viaRow = new LinearLayout(this);
        viaRow.setOrientation(LinearLayout.HORIZONTAL);
        viaRow.addView(smallButton("搜索途经点", new View.OnClickListener() {
            public void onClick(View v) {
                pickingTarget = PICK_VIA;
                String q = destInput.getText() == null ? "" : destInput.getText().toString().trim();
                if (q.length() == 0) {
                    setSetupStatus("在上方输入途经点名称/坐标，再点「搜索途经点」");
                    return;
                }
                NaviLatLng direct = parseLatLng(q);
                if (direct != null) {
                    addVia("途经点", direct);
                    destInput.setText("");
                } else {
                    searchCenter = null;
                    runPoiSearch(q);
                }
            }
        }), weight1());
        viaRow.addView(smallButton("沿途搜", new View.OnClickListener() {
            public void onClick(View v) { pickingTarget = PICK_VIA; alongSearch(); }
        }), weight1());
        viaRow.addView(smallButton("地图选途经点", new View.OnClickListener() {
            public void onClick(View v) {
                pickingTarget = PICK_VIA;
                openMapPicker();
            }
        }), weight1());
        viaRow.addView(smallButton("清空", new View.OnClickListener() {
            public void onClick(View v) {
                vias.clear();
                renderVias();
                setSetupStatus("已清空途经点");
            }
        }), weight1());
        morePanel.addView(viaRow, margins(0, dp(2), 0, dp(6)));

        // 驾车偏好切换（高德的「躲避拥堵/高速优先/少收费」）
        prefRow = new LinearLayout(this);
        prefRow.setOrientation(LinearLayout.HORIZONTAL);
        prefRow.setVisibility(View.GONE);
        prefRow.addView(smallButton("高德智能推荐", new View.OnClickListener() {
            public void onClick(View v) { setPreference(0); }
        }), weight1());
        prefRow.addView(smallButton("躲避拥堵", new View.OnClickListener() {
            public void onClick(View v) { setPreference(1); }
        }), weight1());
        prefRow.addView(smallButton("少收费", new View.OnClickListener() {
            public void onClick(View v) { setPreference(2); }
        }), weight1());
        prefRow.addView(smallButton("不走高速", new View.OnClickListener() {
            public void onClick(View v) { setPreference(3); }
        }), weight1());
        setupPanel.addView(prefRow, margins(0, dp(4), 0, 0));

        setupPanel.addView(morePanel);

        // 出行方式：高德式文字 Tab（选中主题蓝加粗），不再是三个等宽大按钮
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] modeLabels = {"驾车", "骑行", "步行"};
        for (int i = 0; i < modeLabels.length; i++) {
            final int m = i;
            TextView tab = text(modeLabels[i], i == 0 ? BLUE : TEXT_GRAY, 15, i == 0);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(9), dp(4), dp(9));
            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prepareRoute(m);
                    updateModeTabs();
                }
            });
            modeTabs[i] = tab;
            modeRow.addView(tab, weight1());
        }
        setupPanel.addView(modeRow, margins(0, dp(2), 0, dp(4)));

        setupStatus = text("", 0xFFB26A00, 12, false);
        setupPanel.addView(setupStatus, margins(0, dp(10), 0, 0));

        setupScroll.addView(setupPanel);
        FrameLayout.LayoutParams ssLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssLp.gravity = Gravity.TOP;
        root.addView(setupScroll, ssLp);

        // ---- 底部路线卡片（高德三态面板）：折叠 / 半开 / 全开，拖把手切换 ----
        routeCard = new LinearLayout(this);
        routeCard.setOrientation(LinearLayout.VERTICAL);
        routeCard.setBackground(roundedTop(WHITE, dp(20)));
        routeCard.setElevation(dp(10));
        routeCard.setVisibility(View.GONE);

        // 拖把手：一条灰色小横条 + 标题，点击在半开/全开间切换，可上下拖动
        LinearLayout handleArea = new LinearLayout(this);
        handleArea.setOrientation(LinearLayout.VERTICAL);
        handleArea.setGravity(Gravity.CENTER_HORIZONTAL);
        handleArea.setPadding(dp(12), dp(10), dp(12), dp(8));
        dragHandle = new View(this);
        dragHandle.setBackground(rounded(0xFFD6DBE3, 3));
        handleArea.addView(dragHandle, new LinearLayout.LayoutParams(dp(40), dp(5)));
        routeCardTitle = text("为你找到的路线", TEXT_DARK, 14, true);
        handleArea.addView(routeCardTitle, margins(0, dp(8), 0, 0));
        handleArea.setOnTouchListener(handleTouch);
        routeCard.addView(handleArea);

        HorizontalScrollView routeScroll = new HorizontalScrollView(this);
        routeScroll.setHorizontalScrollBarEnabled(false);
        routeBox = new LinearLayout(this);
        routeBox.setOrientation(LinearLayout.HORIZONTAL);
        routeScroll.addView(routeBox);
        routeCard.addView(routeScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        startNavBtn = new Button(this);
        startNavBtn.setText("开始导航");
        startNavBtn.setAllCaps(false);
        startNavBtn.setTextColor(WHITE);
        startNavBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        startNavBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startNavBtn.setBackground(rounded(BLUE, 12));
        startNavBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                beginNavigation(previewRouteId);
            }
        });
        routeCard.addView(startNavBtn, margins(dp(14), dp(8), dp(14), dp(16)));

        routeCardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelHeight(1));
        routeCardLp.gravity = Gravity.BOTTOM;
        root.addView(routeCard, routeCardLp);

        // 面板收起后的小入口
        expandBtn = smallButton("选择目的地", new View.OnClickListener() {
            public void onClick(View v) {
                collapsePanel(false);
            }
        });
        expandBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams ebLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ebLp.gravity = Gravity.TOP | Gravity.LEFT;
        ebLp.leftMargin = dp(10);
        ebLp.topMargin = dp(10);
        root.addView(expandBtn, ebLp);

        // ---- 导航结束页（全屏半透明遮罩 + 结果卡） ----
        buildEndPage();

        setContentView(root);
    }

    /** 导航结束页：到达/手动结束都弹，显示用时、目的地、重新规划 */
    private void buildEndPage() {
        endPage = new LinearLayout(this);
        endPage.setBackgroundColor(0x99000000);
        endPage.setGravity(Gravity.CENTER);
        endPage.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(rounded(WHITE, 20));
        card.setPadding(dp(28), dp(32), dp(28), dp(24));
        card.setElevation(dp(12));

        TextView check = text("✓", WHITE, 34, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(rounded(GREEN, 34));
        check.setPadding(dp(18), dp(4), dp(18), dp(8));
        card.addView(check);

        endTitleView = text("已到达目的地", TEXT_DARK, 20, true);
        card.addView(endTitleView, margins(0, dp(18), 0, 0));
        endDetailView = text("", TEXT_GRAY, 13, false);
        endDetailView.setGravity(Gravity.CENTER);
        card.addView(endDetailView, margins(0, dp(8), 0, 0));

        Button again = new Button(this);
        again.setText("重新规划");
        again.setAllCaps(false);
        again.setTextColor(WHITE);
        again.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        again.setBackground(rounded(BLUE, 12));
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hideEndPage(); }
        });
        card.addView(again, margins(0, dp(24), 0, 0));

        Button backHome = new Button(this);
        backHome.setText("返回首页");
        backHome.setAllCaps(false);
        backHome.setTextColor(BLUE);
        backHome.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        backHome.setBackground(null);
        backHome.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        card.addView(backHome, margins(0, dp(4), 0, 0));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        endPage.addView(card, new FrameLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(endPage, lp);
    }

    /**
     * 打开高德 SDK 自带的原生导航引导（车道线 / 路口放大图 / 电子眼 / 拥堵 / 指南针）。
     * 这些是 SDK 原生的，不是我们画的，所以观感和官方 App 一致。
     * 关掉 SDK 自带的设置菜单/路线按钮，避免和我们自己的面板打架。
     */
    private void applyNaviUiOptions() {
        try {
            AMapNaviViewOptions o = naviView.getViewOptions();
            if (o == null) {
                o = new AMapNaviViewOptions();
            }
            o.setNaviNight(false);
            o.setLaneInfoShow(true);              // 车道线引导
            o.setRealCrossDisplayShow(true);      // 路口放大图
            o.setEyrieCrossDisplay(true);         // 路口实景/鸟瞰放大
            o.setCameraBubbleShow(true);          // 电子眼/限速气泡
            o.setShowCameraDistance(true);        // 电子眼距离
            o.setTrafficLine(true);               // 路线拥堵染色
            o.setTrafficLayerEnabled(true);       // 路况图层
            o.setTrafficBarEnabled(true);         // 全程路况条
            o.setCompassEnabled(true);            // 指南针
            o.setAutoChangeZoom(true);            // 自动缩放
            o.setAutoDisplayOverview(true);       // 到路口自动全览
            o.setSettingMenuEnabled(false);
            o.setShowSettingsPanel(false);
            o.setRouteListButtonShow(false);
            // 规划阶段不显示 SDK 自带的导航浮层（底部「退出」栏等），开始导航后再打开
            o.setLayoutVisible(false);
            naviView.setViewOptions(o);
            Repo.log("[SDK] 原生导航 UI 已配置（车道线/路口放大/电子眼/拥堵）");
        } catch (Throwable t) {
            Repo.log("[SDK] 原生导航 UI 设置失败: " + t);
        }
    }

    /**
     * 首页传入的输入：地名 / 坐标 / 高德分享链接，统一解析。
     * 链接/坐标解析出目的地后直接 selectDestination；关键词走 POI 搜索。
     */
    private void consumeIncomingInput() {
        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        // 首页「长按地图选点 → 去这里」直接带入坐标：无需再搜索，直接设为目的地
        if (intent.hasExtra(EXTRA_DEST_LAT) && intent.hasExtra(EXTRA_DEST_LNG)) {
            double lat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN);
            double lng = intent.getDoubleExtra(EXTRA_DEST_LNG, Double.NaN);
            // 导出给外部 App 的入口必须校验：有限值 + 中国范围，越界直接忽略
            if (AmapLink.isInChina(lat, lng)) {
                String name = intent.getStringExtra(EXTRA_DEST_NAME);
                String addr = intent.getStringExtra(EXTRA_DEST_ADDRESS);
                selectDestination(name == null ? "地图选点" : name,
                        addr == null ? "" : addr, new NaviLatLng(lat, lng), "首页地图选点");
                setSetupStatus("已带入首页选好的目的地，点下方出行方式开始导航");
                Repo.log("[SDK] 首页选点直达 " + lat + "," + lng);
                return;
            }
        }

        // 系统分享（高德 App「分享 → 手环导航」发 text/plain）读 EXTRA_TEXT；
        // 页面内搜索框走 EXTRA_INPUT。两者统一进下面的单条解析链。
        String input = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            input = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (input == null) {
                CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                input = cs == null ? null : cs.toString();
            }
            if ((input == null || input.trim().length() == 0) && intent.getData() != null) {
                input = intent.getDataString();
            }
            Repo.log("[SDK] 收到系统分享: " + (input == null ? "空" : input.trim()));
        }
        if (input == null || input.trim().length() == 0) {
            input = intent.getStringExtra(EXTRA_INPUT);
        }
        if (input == null || input.trim().length() == 0) {
            return;
        }
        final String raw = input.trim();
        if (destInput != null) {
            destInput.setText(raw);
        }
        setSetupStatus("正在识别「" + raw + "」…");

        // 单条解析链：路线链接 → RoutePlan，单点链接/坐标 → Place；短链只联网抓一次
        AmapLink.resolveAny(raw, new AmapLink.AnyCallback() {
            public void onResult(final AmapLink.RoutePlan plan, final AmapLink.Place place,
                                 final String error) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (plan != null) {
                            applyRoutePlan(plan);
                        } else if (place != null) {
                            String name = place.name.length() > 0 ? place.name : "目的地";
                            selectDestination(name, place.address,
                                    new NaviLatLng(place.lat, place.lng),
                                    place.source + "导入");
                            setSetupStatus("已识别目的地：" + name + "，点下方出行方式开始导航");
                            Repo.log("[SDK] 链接导入成功 " + place);
                        } else if (AmapLink.isLink(raw)) {
                            // 链接解析失败要给出原因，不能静默卡在「正在识别…」
                            setSetupStatus(error == null ? "链接解析失败，请改用地图选点" : error);
                            Repo.log("[SDK] 链接解析失败 " + raw + " :: " + error);
                        } else {
                            // 解析不出坐标：当成地名关键词搜索
                            setSetupStatus("未识别出坐标，按地名搜索「" + raw + "」…");
                            runPoiSearch(raw);
                        }
                    }
                });
            }
        });
    }

    /** 路线链接导入：起点 / 终点 / 途经点 / 出行方式一次带进规划态 */
    private void applyRoutePlan(final AmapLink.RoutePlan plan) {
        planFrom = plan.from != null ? new NaviLatLng(plan.from.lat, plan.from.lng) : null;
        fromName = plan.from != null && plan.from.name.length() > 0 ? plan.from.name : "路线起点";
        updateFromView();
        planPolicy = plan.policy;
        vias.clear();
        for (AmapLink.Place v : plan.vias) {
            vias.add(new Via(v.name.length() > 0 ? v.name : "途经点", new NaviLatLng(v.lat, v.lng)));
        }
        renderVias();
        selectDestination(plan.to.name.length() > 0 ? plan.to.name : "路线终点",
                plan.to.address, new NaviLatLng(plan.to.lat, plan.to.lng), "路线链接");
        if (plan.mode >= 0) {
            naviMode = plan.mode;
            updateModeTabs();
        }
        String kind = plan.mode < 0 ? "公交（本 App 不支持，按驾车算）" : modeName();
        setSetupStatus("已导入路线：" + (plan.from != null ? "含起点、" : "")
                + vias.size() + " 个途经点、" + kind
                + "，点「" + modeName() + "」开始选路");
        Repo.log("[SDK] 路线链接导入成功 " + plan + " mode=" + naviMode);
    }

    // ---------------------------------------------------------------- 算路

    private void prepareRoute(int mode) {
        if (navi == null) {
            setSetupStatus("导航未初始化，请返回主界面重试");
            return;
        }
        if (destLatLng == null) {
            setSetupStatus("请先「搜索」并点选候选，或「地图选点」选定目的地");
            return;
        }
        naviMode = mode;
        updateModeTabs();
        // 每次重新发起算路都重置导航状态（允许用户退出后再次开始）
        naviStopped = false;
        routeStarted = false;
        previewRouteId = -1;
        routeOpts.clear();
        if (routeBox != null) {
            routeBox.removeAllViews();
            routeBox.setVisibility(View.GONE);
        }
        if (startNavBtn != null) {
            startNavBtn.setVisibility(View.GONE);
        }
        if (routeCard != null) {
            routeCard.setVisibility(View.GONE);
        }
        if (endPage != null) {
            endPage.setVisibility(View.GONE);
        }
        if (prefRow != null) {
            prefRow.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        }
        collapsePanel(false); // 重新算路时展开顶部表单
        navActive = false;
        frameCount = 0;
        lastTurn = "";
        lastAction = "";
        pendingTo = destLatLng;
        setSetupStatus("准备" + modeName() + "算路…");
        requestRouteWhenReady();
    }

    /** 驾车偏好切换：改策略后立刻用当前目的地重新算路 */
    private void setPreference(int policy) {
        if (policy == planPolicy) {
            return;
        }
        planPolicy = policy;
        if (destLatLng != null && navi != null) {
            prepareRoute(0); // 偏好仅对驾车有意义，统一按驾车重算
            setSetupStatus("已切换偏好，正在重新算路…");
        } else {
            setSetupStatus("已选偏好，选定目的地后生效");
        }
    }

    // ---------------------------------------------------------------- 目的地：搜索 / 候选 / 地图选点

    private void doSearch() {
        String raw = destInput.getText() == null ? "" : destInput.getText().toString().trim();
        if (raw.length() == 0) {
            setSetupStatus("请输入地名或坐标");
            return;
        }
        NaviLatLng direct = parseLatLng(raw);
        if (direct != null) {
            // 坐标模式：直接可用，无需搜索
            selectDestination("自定义坐标", "", direct, "坐标输入");
            return;
        }
        runPoiSearch(raw);
    }

    /** 搜索 SDK（PoiSearch）：返回候选列表，**不自动选第一条** */
    private void runPoiSearch(final String keyword) {
        clearCandidates();
        setSetupStatus("搜索中…（" + keyword + "）");
        try {
            PoiSearch.Query query = new PoiSearch.Query(keyword, "", "");
            query.setPageSize(10);
            query.setCityLimit(false);
            poiSearch = new PoiSearch(getApplicationContext(), query);
            // 沿途搜：以起点为中心限定 5km 范围；普通搜索 searchCenter 为 null
            if (searchCenter != null) {
                poiSearch.setBound(new PoiSearch.SearchBound(searchCenter, 5000));
                searchCenter = null; // 一次性，搜完复位
            }
            poiSearch.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                public void onPoiSearched(PoiResult result, int code) {
                    if (code != 1000 || result == null || result.getPois() == null
                            || result.getPois().isEmpty()) {
                        Repo.log("[SDK] 搜索失败 code=" + code + " keyword=" + keyword);
                        setSetupStatus("没搜到「" + keyword + "」（code=" + code
                                + "），换个关键词或直接输坐标");
                        return;
                    }
                    showCandidates(result.getPois());
                }

                public void onPoiItemSearched(PoiItem item, int code) {
                    // 用不到按 POI ID 查详情
                }
            });
            poiSearch.searchPOIAsyn();
        } catch (Throwable t) {
            setSetupStatus("搜索异常：" + t);
            Repo.log("[SDK] PoiSearch 异常: " + t);
        }
    }

    private void addVia(String name, NaviLatLng ll) {
        if (ll == null || vias.size() >= 10) {
            setSetupStatus(ll == null ? "途经点坐标无效" : "途经点最多 10 个");
            return;
        }
        vias.add(new Via(safe(name), ll));
        renderVias();
        setSetupStatus("已加途经点：" + name + "（共 " + vias.size() + " 个）");
    }

    private void renderVias() {
        if (viaBox == null) {
            return;
        }
        viaBox.removeAllViews();
        for (int i = 0; i < vias.size(); i++) {
            final int idx = i;
            TextView row = text((i + 1) + ". " + vias.get(i).name, TEXT_DARK, 13, false);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundColor(i % 2 == 0 ? 0xFFF7F8FA : WHITE);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    vias.remove(idx);
                    renderVias();
                }
            });
            viaBox.addView(row);
        }
    }

    /** 候选列表：高德式结果行（定位图标 + 蓝色名称 + 标签/地址 + 右侧距离与「去这里」） */
    private void showCandidates(java.util.List<PoiItem> pois) {
        clearCandidates();
        // 结果列表展示时隐藏「未选择目的地」，避免和高德式结果列表抢视觉
        if (selectedView != null) {
            selectedView.setVisibility(View.GONE);
        }
        setSetupStatus("找到 " + pois.size() + " 个候选，点一条选中");

        // 列表头：高德式「搜索结果 · N 个」
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("搜索结果", TEXT_GRAY, 12, false), weight1());
        head.addView(text(pois.size() + " 个", TEXT_GRAY, 12, false));
        candidateBox.addView(head, margins(dp(4), dp(2), dp(4), dp(6)));

        int n = Math.min(pois.size(), 10);
        for (int i = 0; i < n; i++) {
            final PoiItem item = pois.get(i);
            if (item == null || item.getLatLonPoint() == null) {
                continue;
            }
            final String title = safe(item.getTitle());
            final String addr = safe(item.getSnippet());
            final LatLonPoint point = item.getLatLonPoint();
            final String tag = safe(item.getTypeDes()).split(";")[0];
            // 高德结果右侧一直显示距离：SDK 没给（未设 bound）时用当前定位本地算一个
            int distance = item.getDistance();
            NaviLatLng center = planFrom != null ? planFrom : cachedFrom;
            if (distance <= 0 && center != null) {
                distance = distanceMeters(center.getLatitude(), center.getLongitude(),
                        point.getLatitude(), point.getLongitude());
            }
            final String distText = distance > 0 ? formatDistance(distance) : "";

            final String action = pickingTarget == PICK_VIA ? "设为途经"
                    : (pickingTarget == PICK_FROM ? "设为起点" : "去这里");

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(rounded(WHITE, 12));
            card.setPadding(dp(12), dp(12), dp(12), dp(12));

            // 左侧定位图标（灰色实心点，近似高德的 POI 标记）
            TextView pin = text("◉", 0xFF98A2B3, 15, false);
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pinLp.rightMargin = dp(10);
            pinLp.topMargin = dp(1);
            card.addView(pin, pinLp);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = text(title, BLUE, 15, true);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(nameView);
            String sub = tag.length() > 0 ? (tag + " · " + addr) : addr;
            TextView subView = text(sub, TEXT_GRAY, 12, false);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(subView, margins(0, dp(3), 0, 0));
            card.addView(info, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 右侧：距离 + 「去这里」蓝色文字按钮（高德右侧「导航」图标的等价物）
            LinearLayout rightBox = new LinearLayout(this);
            rightBox.setOrientation(LinearLayout.VERTICAL);
            rightBox.setGravity(Gravity.CENTER_HORIZONTAL);
            if (distText.length() > 0) {
                rightBox.addView(text(distText, TEXT_GRAY, 11, false));
            }
            TextView go = text(action, BLUE, 12, true);
            go.setPadding(dp(2), dp(5), 0, 0);
            rightBox.addView(go);
            card.addView(rightBox);

            final View.OnClickListener pick = new View.OnClickListener() {
                public void onClick(View v) {
                    NaviLatLng ll = new NaviLatLng(point.getLatitude(), point.getLongitude());
                    if (pickingTarget == PICK_VIA) {
                        addVia(title, ll);
                    } else if (pickingTarget == PICK_FROM) {
                        selectOrigin(title, addr, ll);
                        pickingTarget = PICK_DEST;
                    } else {
                        selectDestination(title, addr, ll, "搜索");
                    }
                }
            };
            card.setOnClickListener(pick);
            go.setOnClickListener(pick);
            candidateBox.addView(card, margins(0, 0, 0, dp(6)));
        }
    }

    /** 两点间球面距离（米），用于搜索结果的距离显示 */
    private static int distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (int) (2 * r * Math.asin(Math.sqrt(a)));
    }

    private void clearCandidates() {
        if (candidateBox != null) {
            candidateBox.removeAllViews();
        }
    }

    /** 设定目的地（搜索选中 / 坐标输入 / 地图选点共用） */
    private void selectDestination(String name, String address, NaviLatLng latLng, String from) {
        destName = safe(name);
        destAddress = safe(address);
        destLatLng = latLng;
        clearCandidates();
        setSelectedText();
        centerOnDestination();
        // 高德行为：选定目的地后立刻按当前出行方式算路并给出方案，不用再点一次「驾车」
        prepareRoute(naviMode);
        setSetupStatus("已设定目的地（" + from + "），正在规划路线…");
        Repo.log("[SDK] 目的地(" + from + "): " + destName + " " + destAddress + " " + latLng);
    }

    private void setSelectedText() {
        if (selectedView == null) {
            return;
        }
        selectedView.setVisibility(View.VISIBLE);
        if (destLatLng == null) {
            selectedView.setText("未选择目的地");
            return;
        }
        // 精简：只显示目的地名称，长地址和坐标不占版面（logcat 里有完整信息）
        String name = destName.length() > 0 ? destName : "已选地点";
        if (name.length() > 22) {
            name = name.substring(0, 22) + "…";
        }
        selectedView.setText("目的地：" + name);
    }

    private void openMapPicker() {
        try {
            Intent intent = new Intent(this, MapPickActivity.class);
            startActivityForResult(intent, REQ_MAP_PICK);
        } catch (Throwable t) {
            setSetupStatus("打开地图选点失败：" + t);
        }
    }

    /** 沿途搜：把搜索中心限定在起点附近，搜加油站/充电站/服务区/厕所 */
    private void alongSearch() {
        NaviLatLng center = planFrom != null ? planFrom : cachedFrom;
        if (center == null) {
            setSetupStatus("正在定位，请稍后再点沿途搜");
            requestFreshLocation();
            return;
        }
        searchCenter = new LatLonPoint(center.getLatitude(), center.getLongitude());
        // 输入框有内容就用它当关键词，否则默认加油站（沿途最高频）。点一条即加入途经点
        String q = destInput == null ? "" : destInput.getText().toString().trim();
        if (q.length() == 0) {
            q = "加油站";
        }
        runPoiSearch(q);
        setSetupStatus("正在搜起点附近的「" + q + "」…点一条加入途经点；想搜充电站就先在上方输入再点");
    }

    /** 起终点互换：起点用当前定位或已设起点，与目的地对调后重新算路 */
    private void swapFromDest() {
        if (destLatLng == null) {
            setSetupStatus("请先选定目的地，再互换");
            return;
        }
        NaviLatLng from = planFrom != null ? planFrom : cachedFrom;
        if (from == null) {
            setSetupStatus("还没拿到起点定位，请稍后再试");
            requestFreshLocation();
            return;
        }
        String oldFromName = fromName.length() > 0 ? fromName : "我的位置";
        String oldDestName = destName.length() > 0 ? destName : "目的地";
        NaviLatLng oldDest = destLatLng;
        // 对调：起点变终点，终点变起点
        planFrom = oldDest;
        fromName = oldDestName;
        destLatLng = from;
        destName = oldFromName;
        destAddress = "";
        updateFromView();
        setSelectedText();
        if (navi != null) {
            prepareRoute(naviMode);
        }
        setSetupStatus("已互换起终点，正在重新算路…");
        Repo.log("[SDK] 起终点互换 from=" + fromName + " to=" + destName);
    }

    /** 选定目的地后把地图带到该点（新实例的默认视角在别处，否则看起来像「定位错了」） */
    private void centerOnDestination() {
        if (naviView == null || destLatLng == null) {
            return;
        }
        try {
            com.amap.api.maps.AMap map = naviView.getMap();
            if (map == null) {
                return;
            }
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(destLatLng.getLatitude(), destLatLng.getLongitude()), 13f));
        } catch (Throwable t) {
            Repo.log("[SDK] 移动镜头到目的地失败: " + t);
        }
    }

    private void updateFromView() {
        if (fromView == null) {
            return;
        }
        String n = fromName.length() > 0 ? fromName : "我的位置";
        if (n.length() > 16) {
            n = n.substring(0, 16) + "…";
        }
        fromView.setText(n);
    }

    /** 设为起点（搜索候选/地图选点回调里 pickingTarget==PICK_FROM 时调用） */
    private void selectOrigin(String name, String address, NaviLatLng latLng) {
        planFrom = latLng;
        fromName = safe(name).length() > 0 ? safe(name) : "自定义起点";
        updateFromView();
        setSetupStatus("已设起点：" + fromName + "（原起点来自定位/路线链接，已被覆盖）");
        Repo.log("[SDK] 起点(" + fromName + ") " + address + " " + latLng);
    }

    /** 显示导航结束页 */
    private void showEndPage(String title) {
        if (endPage == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                String t = title;
                endTitleView.setText(t);
                long secs = naviStartMs > 0 ? (System.currentTimeMillis() - naviStartMs) / 1000 : 0;
                String time = secs >= 60 ? (secs / 60) + " 分 " + (secs % 60) + " 秒" : secs + " 秒";
                String dest = destName.length() > 0 ? destName : "目的地";
                endDetailView.setText("用时 " + time + "\n目的地：" + dest
                        + "\n" + (Bridge.get().isReady() ? "手环已同步结束" : "手环未连接"));
                endPage.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideEndPage() {
        if (endPage != null) {
            endPage.setVisibility(View.GONE);
        }
        // 回到选路前的可操作状态
        naviStopped = false;
        routeStarted = false;
        if (destLatLng != null && navi != null) {
            prepareRoute(naviMode);
        } else {
            collapsePanel(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MAP_PICK) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            setSetupStatus("已取消地图选点");
            return;
        }
        double lat = data.getDoubleExtra(MapPickActivity.EXTRA_LAT, Double.NaN);
        double lng = data.getDoubleExtra(MapPickActivity.EXTRA_LNG, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            setSetupStatus("地图选点没有返回坐标");
            return;
        }
        String name = data.getStringExtra(MapPickActivity.EXTRA_NAME);
        String addr = data.getStringExtra(MapPickActivity.EXTRA_ADDRESS);
        NaviLatLng ll = new NaviLatLng(lat, lng);
        if (pickingTarget == PICK_VIA) {
            addVia(name == null ? "途经点" : name, ll);
        } else if (pickingTarget == PICK_FROM) {
            selectOrigin(name == null ? "地图选起点" : name, addr == null ? "" : addr, ll);
            pickingTarget = PICK_DEST;
        } else {
            selectDestination(name == null ? "地图选点" : name,
                    addr == null ? "" : addr, ll, "地图选点");
        }
    }

    /** 定位权限结果：拒绝时明确提示去系统设置开启，避免一直停在「等待定位…」 */
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
            Repo.log("[SDK] 定位权限已授予");
            // 申请权限前 startLocation 大概率已经失败过一次：授权后立刻补一次定位，
            // 拿到坐标会自动继续算路（否则用户要再点一次出行方式）
            requestFreshLocation();
            if (pendingTo != null && !naviStopped) {
                doRequestRoute(pendingTo);
            }
            return;
        }
        String msg = "定位权限被拒绝：请在系统设置 → 应用 → 权限 → 位置信息中允许本 App，"
                + "否则无法算路";
        setSetupStatus(msg);
        Repo.log("[SDK] " + msg);
    }

    /** 选点阶段把地图镜头移到指定坐标；导航开始后不再干预 */
    private void centerMapOn(double lat, double lng) {
        if (navActive || destLatLng != null || naviView == null) {
            return;
        }
        try {
            com.amap.api.maps.AMap map = naviView.getMap();
            if (map != null) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 15f));
            }
        } catch (Throwable ignore) {
            // getMap 在未完全初始化时可能失败，忽略
        }
    }

    /** 展开/收起低频设置（途经点 / 选起点 / 驾车偏好） */
    /** 高德式出行方式 Tab：选中项主题蓝加粗，其余灰色 */
    private void updateModeTabs() {
        for (int i = 0; i < modeTabs.length; i++) {
            if (modeTabs[i] == null) {
                continue;
            }
            boolean on = i == naviMode;
            modeTabs[i].setTextColor(on ? BLUE : TEXT_GRAY);
            modeTabs[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void toggleMore() {
        if (morePanel == null || moreBtn == null) {
            return;
        }
        boolean show = morePanel.getVisibility() != View.VISIBLE;
        morePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        moreBtn.setText(show ? "收起更多" : "更多");
        // 偏好胶囊已提到面板上（高德样式），不再由「更多」抽屉控制
    }

    /** 收起/展开目的地面板（收起后能看到地图，不挡满屏） */
    private void collapsePanel(boolean collapse) {
        if (setupScroll != null) {
            setupScroll.setVisibility(collapse ? View.GONE : View.VISIBLE);
        }
        if (expandBtn != null) {
            expandBtn.setVisibility(collapse ? View.VISIBLE : View.GONE);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String formatDistance(int meters) {
        if (meters >= 1000) {
            // 高德口径用「公里 / 米」
            return String.format(java.util.Locale.US, "%.1f公里", meters / 1000.0);
        }
        return meters + "米";
    }

    /** 三态面板高度：折叠=把手、半开=卡片一半、全开=屏幕 72% */
    private int panelHeight(int state) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (state == 0) {
            return dp(96); // 只露把手 + 标题
        }
        if (state == 1) {
            return Math.min(screenH * 45 / 100, dp(320));
        }
        return (int) (screenH * 0.72f);
    }

    /** 切到指定面板态，带高度动画 */
    private void setPanelState(int state) {
        panelState = state;
        animatePanelHeight(panelHeight(state));
    }

    private void animatePanelHeight(int to) {
        if (routeCardLp == null) {
            return;
        }
        try {
            ValueAnimator anim = ValueAnimator.ofInt(routeCardLp.height, to);
            anim.setDuration(220);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    routeCardLp.height = (Integer) a.getAnimatedValue();
                    routeCard.setLayoutParams(routeCardLp);
                }
            });
            anim.start();
        } catch (Throwable t) {
            routeCardLp.height = to;
            routeCard.setLayoutParams(routeCardLp);
        }
    }

    /** 把手触摸：上下拖动实时改高度，松手吸附到最近态；点击在半开/全开间切换 */
    private final View.OnTouchListener handleTouch = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent e) {
            if (routeCardLp == null) {
                return false;
            }
            int action = e.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                startY = (int) e.getRawY();
                startH = routeCardLp.height;
                dragging = false;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int dy = startY - (int) e.getRawY(); // 上滑为正
                if (Math.abs(dy) > dp(4)) {
                    dragging = true;
                }
                int h = startH + dy;
                h = Math.max(panelHeight(0), Math.min(panelHeight(2), h));
                routeCardLp.height = h;
                routeCard.setLayoutParams(routeCardLp);
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (!dragging) {
                    setPanelState(panelState == 2 ? 1 : 2); // 点一下切换半开/全开
                    return true;
                }
                // 吸附到最近的档位
                int h = routeCardLp.height;
                int[] hs = {panelHeight(0), panelHeight(1), panelHeight(2)};
                int best = 0;
                int bestD = Math.abs(h - hs[0]);
                for (int i = 1; i < hs.length; i++) {
                    int d = Math.abs(h - hs[i]);
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                setPanelState(best);
                return true;
            }
            return false;
        }
    };

    private GradientDrawable roundedTop(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
        return d;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BLUE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackground(rounded(0xFFEAF2FF, 10));
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void requestRouteWhenReady() {
        if (pendingTo == null || naviStopped) {
            return;
        }
        if (!naviInited) {
            setSetupStatus("AMapNavi 初始化中，稍后自动开始算路…");
            return;
        }
        doRequestRoute(pendingTo);
    }

    private void doRequestRoute(NaviLatLng to) {
        try {
            // 路线链接指定了起点就用它；否则用当前定位（真实导航要新鲜定位）
            NaviLatLng from = planFrom != null ? planFrom : currentLatLng();
            if (from == null) {
                // 真实导航但没有新鲜定位：已触发一次定位请求，等回调后自动继续；
                // 同时挂超时兜底，信号差时不能让页面永远停在「等待定位…」
                setSetupStatus("等待定位…（导航需要实时定位，请到窗边/开阔处并确认已允许定位权限）");
                handler.removeCallbacks(locationTimeout);
                handler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);
                return;
            }
            handler.removeCallbacks(locationTimeout);
            if (naviMode == 1 || naviMode == 2) {
                if (!vias.isEmpty()) {
                    setSetupStatus("骑行/步行的高德算路不支持途经点，已忽略 " + vias.size() + " 个途经点");
                }
                navi.setIsNaviTravelView(true);
                if (naviMode == 1) {
                    navi.setTravelInfo(new AMapTravelInfo(TransportType.Ride));
                    navi.calculateRideRoute(from, to);
                    setSetupStatus("已请求骑行算路…");
                } else {
                    navi.setTravelInfo(new AMapTravelInfo(TransportType.Walk));
                    navi.calculateWalkRoute(from, to);
                    setSetupStatus("已请求步行算路…");
                }
            } else {
                List<NaviLatLng> fromList = new ArrayList<NaviLatLng>();
                fromList.add(from);
                List<NaviLatLng> toList = new ArrayList<NaviLatLng>();
                toList.add(to);
                List<NaviLatLng> viaList = new ArrayList<NaviLatLng>();
                for (Via v : vias) {
                    viaList.add(v.ll);
                }
                // policy: 0 默认 / 1 避堵 / 2 避收费 / 3 不走高速
                int strategy;
                if (planPolicy == 1) {
                    strategy = navi.strategyConvert(true, false, false, false, false);
                } else if (planPolicy == 2) {
                    strategy = navi.strategyConvert(false, false, true, false, false);
                } else if (planPolicy == 3) {
                    strategy = navi.strategyConvert(false, true, false, false, false);
                } else {
                    strategy = navi.strategyConvert(true, false, false, false, false);
                }
                navi.calculateDriveRoute(fromList, toList, viaList, strategy);
                setSetupStatus("已请求驾车算路" + (vias.isEmpty() ? "" : "（含 " + vias.size() + " 个途经点）") + "…");
            }
            Repo.log("[SDK] 算路请求已发出 mode=" + modeName() + " via=" + vias.size());
        } catch (Throwable t) {
            setSetupStatus("算路异常：" + t);
            Repo.log("[SDK] 算路异常: " + t);
        }
    }

    /**
     * 起点：真实导航必须用**新鲜的**定位（默认 30s 内）。
     * 没有新鲜定位就返回 null，由调用方提示「等待定位…」，并触发一次定位请求，
     * 拿到后自动继续算路（不再用缓存旧位置或默认坐标兜底）。
     */
    private NaviLatLng currentLatLng() {
        long now = SystemClock.uptimeMillis();
        if (cachedFrom != null && (now - locationAtMs) <= FRESH_LOCATION_MS) {
            Repo.log("[SDK] 起点=AMapLocation(" + ((now - locationAtMs) / 1000) + "s 前) "
                    + cachedFrom);
            return cachedFrom;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location loc = null;
                try {
                    loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                } catch (Throwable ignore) {
                    // 无权限/无 provider
                }
                if (loc == null) {
                    try {
                        loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    } catch (Throwable ignore) {
                        // ignore
                    }
                }
                // 系统最后已知定位也可能是几小时前的，真实导航只接受 2 分钟内的
                if (loc != null && (now - loc.getTime()) <= 120000L) {
                    NaviLatLng l = new NaviLatLng(loc.getLatitude(), loc.getLongitude());
                    Repo.log("[SDK] 起点=LocationManager(" + ((now - loc.getTime()) / 1000) + "s 前) "
                            + l);
                    return l;
                }
            }
        } catch (Throwable t) {
            Repo.log("[SDK] LocationManager 读取失败: " + t);
        }

        // 没有新鲜定位：请求 AMapLocation，等待定位回调
        Repo.log("[SDK] 无新鲜定位：请求 AMapLocation，等待定位回调…");
        waitingLocation = true;
        requestFreshLocation();
        return null;
    }

    private void requestFreshLocation() {
        if (locationClient == null) {
            return;
        }
        try {
            locationClient.stopLocation();
            locationClient.startLocation();
        } catch (Throwable t) {
            Repo.log("[SDK] 重新请求定位失败: " + t);
        }
    }

    /** 高德定位 SDK：一次性定位，拿到坐标后缓存起来当起点 */
    private void initLocationClient() {
        try {
            locationClient = new AMapLocationClient(getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setOnceLocation(true);
            option.setNeedAddress(false);
            locationClient.setLocationOption(option);
            locationClient.setLocationListener(new AMapLocationListener() {
                public void onLocationChanged(AMapLocation location) {
                    if (location == null) {
                        return;
                    }
                    if (location.getErrorCode() == 0) {
                        cachedFrom = new NaviLatLng(location.getLatitude(), location.getLongitude());
                        locationAtMs = SystemClock.uptimeMillis();
                        Repo.log("[SDK] AMapLocation 起点=" + location.getLatitude()
                                + "," + location.getLongitude());
                        // 选点阶段：地图初始视角落在用户当前位置（导航开始后交给 SDK 接管）
                        if (!navActive && destLatLng == null) {
                            final double lat = location.getLatitude();
                            final double lng = location.getLongitude();
                            runOnUiThread(new Runnable() {
                                public void run() { centerMapOn(lat, lng); }
                            });
                        }
                        if (waitingLocation) {
                            waitingLocation = false;
                            handler.removeCallbacks(locationTimeout);
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    setSetupStatus("定位成功，继续算路…");
                                    if (pendingTo != null && !naviStopped) {
                                        doRequestRoute(pendingTo);
                                    }
                                }
                            });
                        }
                    } else {
                        final int code = location.getErrorCode();
                        Repo.log("[SDK] AMapLocation 失败 code=" + code
                                + " " + location.getErrorInfo());
                        // 定位失败必须复位等待状态并提示，否则算路会永久停在「等待定位…」
                        waitingLocation = false;
                        handler.removeCallbacks(locationTimeout);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                setSetupStatus("定位失败（code " + code
                                        + "）：请确认定位开关/权限后，再点一次出行方式重试");
                            }
                        });
                    }
                }
            });
            locationClient.startLocation();
            Repo.log("[SDK] AMapLocationClient 已启动定位");
        } catch (Throwable t) {
            locationClient = null;
            Repo.log("[SDK] AMapLocationClient 初始化失败（退回 LocationManager）: " + t);
        }
    }

    private String modeName() {
        return naviMode == 1 ? "骑行" : (naviMode == 2 ? "步行" : "驾车");
    }

    private NaviLatLng parseLatLng(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            // 兼容「纬度,经度」「纬度，经度」「纬度 经度」以及分隔符合空格/多空格的写法。
            // 旧写法先 replace(' ',',') 会把「39.9087, 116.3975」变成「39.9087,,116.3975」，
            // split 后出现空串，parseDouble("") 抛异常导致坐标输入失效。
            String s = raw.replace('，', ',').trim();
            String[] parts = s.split("[,\\s]+");
            if (parts.length < 2) {
                return null;
            }
            double lat = Double.parseDouble(parts[0]);
            double lng = Double.parseDouble(parts[1]);
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                return null;
            }
            return new NaviLatLng(lat, lng);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Host 回调

    public void onNaviInited() {
        naviInited = true;
        runOnUiThread(new Runnable() {
            public void run() {
                // 初始化回调通常晚于「链接导入 / 搜索」的状态提示，别把更重要的提示盖掉
                if (setupStatus == null || TextUtils.isEmpty(setupStatus.getText())) {
                    setSetupStatus("导航初始化成功");
                }
                if (pendingTo != null && !routeStarted && !naviStopped) {
                    doRequestRoute(pendingTo);
                }
            }
        });
    }

    public void onRouteSuccess(AMapCalcRouteResult result) {
        // 已开始导航后的偏航重算由 SDK 自己处理，不要再弹选路列表打断导航
        if (naviStopped || routeStarted) {
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                showRouteOptions(result);
            }
        });
    }

    /** 算路成功：列出所有方案让用户选，点一条才真正开始导航（高德的选路体验） */
    private void showRouteOptions(AMapCalcRouteResult result) {
        routeBox.removeAllViews();
        routeOpts.clear();
        List<RouteOpt> built = new ArrayList<RouteOpt>();
        try {
            Map<Integer, AMapNaviPath> paths = navi.getNaviPaths();
            if (paths != null) {
                for (Map.Entry<Integer, AMapNaviPath> e : paths.entrySet()) {
                    int id = e.getKey();
                    AMapNaviPath p = e.getValue();
                    if (p == null) {
                        continue;
                    }
                    String time = formatDuration(p.getAllTime());
                    String detail = formatDistance(p.getAllLength()) + " · 红绿灯"
                            + p.getTrafficLightCount()
                            + (p.getTollCost() > 0 ? " · 收费¥" + p.getTollCost() : "");
                    built.add(new RouteOpt(id, time, time, detail, false));
                }
            }
        } catch (Throwable t) {
            Repo.log("[SDK] 读取路线方案失败: " + t);
        }
        if (built.isEmpty()) {
            beginNavigation(-1); // 拿不到方案详情也照常导航，不阻塞
            return;
        }
        // 找出最快方案，标「推荐」
        int fastest = 0;
        for (int i = 1; i < built.size(); i++) {
            AMapNaviPath pi = navi.getNaviPaths().get(built.get(i).id);
            AMapNaviPath pf = navi.getNaviPaths().get(built.get(fastest).id);
            if (pi != null && pf != null && pi.getAllTime() < pf.getAllTime()) {
                fastest = i;
            }
        }
        final int fastIdx = fastest;
        final List<View> cards = new ArrayList<View>();
        for (int i = 0; i < built.size(); i++) {
            final RouteOpt opt = built.get(i);
            final int idx = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(rounded(i == fastIdx ? 0xFFE8F2FF : 0xFFF2F4F7, 12));

            String tag = i == fastIdx ? "推荐 · 最快" : "方案 " + (i + 1);
            card.addView(text(tag, i == fastIdx ? BLUE : TEXT_GRAY, 11, i == fastIdx));
            card.addView(text(opt.time, i == fastIdx ? BLUE : TEXT_DARK, 22, true),
                    margins(0, dp(2), 0, 0));
            card.addView(text(opt.detail, TEXT_GRAY, 11, false),
                    margins(0, dp(2), 0, 0));

            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    previewRouteId = opt.id;
                    try {
                        navi.selectRouteId(opt.id); // 地图高亮，不出发
                    } catch (Throwable ignore) {
                        // ignore
                    }
                    for (int j = 0; j < cards.size(); j++) {
                        cards.get(j).setBackground(rounded(
                                j == idx ? 0xFFD6E8FF : (j == fastIdx ? 0xFFE8F2FF : 0xFFF2F4F7), 12));
                    }
                }
            });
            cards.add(card);
            routeOpts.add(opt);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = dp(8);
            routeBox.addView(card, cp);
        }
        // 默认预览推荐路线
        previewRouteId = built.get(fastIdx).id;
        try {
            navi.selectRouteId(previewRouteId);
        } catch (Throwable ignore) {
            // ignore
        }
        routeBox.setVisibility(View.VISIBLE);
        routeCard.setVisibility(View.VISIBLE);
        if (routeCardTitle != null) {
            routeCardTitle.setText("为你找到 " + built.size() + " 条路线 · 上滑看详情");
        }
        routeCardLp.height = panelHeight(1);
        routeCard.setLayoutParams(routeCardLp);
        panelState = 1;
        collapsePanel(true); // 收起顶部表单，让地图和路线露出来
        setSetupStatus("已算出 " + built.size() + " 条路线，点选后按「开始导航」");
        if (debugAutoNav) {
            debugAutoNav = false;
            Repo.log("[SDK] debug_auto_nav=true，自动开始导航（调试）");
            beginNavigation(previewRouteId);
        }
    }

    private void beginNavigation(int routeId) {
        if (routeId < 0 && !routeOpts.isEmpty()) {
            routeId = routeOpts.get(0).id;
        }
        routeBox.setVisibility(View.GONE);
        startNavBtn.setVisibility(View.GONE);
        prefRow.setVisibility(View.GONE);
        if (routeCard != null) {
            routeCard.setVisibility(View.GONE);
        }
        routeStarted = true;
        naviStartMs = System.currentTimeMillis();
        collapsePanel(true);
        if (expandBtn != null) {
            expandBtn.setVisibility(View.GONE); // 导航中不让「选择目的地」浮在地图上
        }
        // 进入导航：打开 SDK 原生导航浮层（退出栏、车道线、路况条等）
        try {
            AMapNaviViewOptions navOpts = naviView.getViewOptions();
            if (navOpts == null) {
                navOpts = new AMapNaviViewOptions();
            }
            navOpts.setLayoutVisible(true);
            naviView.setViewOptions(navOpts);
        } catch (Throwable ignore) {
            // ignore
        }
        statusBar.setVisibility(View.VISIBLE);
        if (endPage != null) {
            endPage.setVisibility(View.GONE);
        }
        try {
            if (routeId >= 0) {
                navi.selectRouteId(routeId);
            }
            navi.startNavi(NaviType.GPS);
            Repo.log("[SDK] selectRouteId=" + routeId + " startNavi(GPS)");
        } catch (Throwable t) {
            Repo.log("[SDK] startNavi 异常: " + t);
        }
        navActive = true;
    }

    private static String formatDuration(int seconds) {
        return (seconds / 60) + "分钟";
    }

    public void onRouteFailure(AMapCalcRouteResult result) {
        final String msg = result == null ? "未知错误"
                : ("code=" + result.getErrorCode() + " " + result.getErrorDescription());
        runOnUiThread(new Runnable() {
            public void run() {
                setSetupStatus("算路失败：" + msg + "（多半是 Key 未配置/绑定不符）");
            }
        });
    }

    public void onNaviInfo(NaviInfo info) {
        if (info == null || naviStopped) {
            return;
        }
        try {
            frameCount++;
            int icon = info.getIconType();
            String turn = NaviIconMapper.turnOf(icon);
            String action = NaviIconMapper.actionOf(icon);
            boolean turnChanged = turn != null && !turn.equals(lastTurn);
            if (turn == null) {
                // 未知图标：沿用上一帧动作
                turn = lastTurn.length() > 0 ? lastTurn : "straight";
                action = lastAction.length() > 0 ? lastAction : "直行";
            }
            lastTurn = turn;
            lastAction = action == null ? "" : action;

            NavFrame frame = new NavFrame();
            frame.turn = turn;
            frame.action = lastAction;
            frame.distMeters = info.getCurStepRetainDistance();
            frame.remainMeters = info.getPathRetainDistance();
            frame.remainSeconds = info.getPathRetainTime();
            frame.vibrate = turnChanged || frameCount <= 1;

            String json = frame.toJson(Repo.nextSeq());
            Repo.log("[SDK] 帧#" + frameCount + " " + json);

            Bridge bridge = Bridge.get();
            bridge.init(this);
            bridge.sendFrame(json);
            navActive = true;
        } catch (Throwable t) {
            Repo.log("[SDK] 处理 NaviInfo 异常: " + t);
        }
    }

    public void onArrive() {
        stopNavigation("到达目的地");
        showEndPage("已到达目的地");
        Repo.log("[SDK] 到达目的地，弹结束页");
    }

    public void onExit() {
        stopNavigation("导航结束回调");
        showEndPage("导航已结束");
    }

    public void onLog(String message) {
        Repo.log("[SDK] " + message);
    }

    // ---------------------------------------------------------------- 手环帧发送

    private void sendStop(String reason) {
        if (!navActive) {
            return;
        }
        navActive = false;
        Repo.log("[SDK] 结束导航（" + reason + "），发送 stop");
        Bridge bridge = Bridge.get();
        bridge.init(this);
        bridge.sendFrame(NavFrame.stopJson());
    }

    /**
     * 真正停止导航：
     *   1) 发 stop 帧；
     *   2) 调 AMapNavi.stopNavi()（否则 SDK 会继续回调 onNaviInfoUpdate）；
     *   3) 置 naviStopped，onNaviInfo 里直接 return，保证停止后不再下发任何帧。
     * 幂等：重复调用只生效一次。
     */
    private void stopNavigation(String reason) {
        if (naviStopped) {
            return;
        }
        naviStopped = true;
        navActive = true; // 保证 sendStop 里能进入发送分支
        sendStop(reason);
        try {
            if (navi != null) {
                navi.stopNavi();
                Repo.log("[SDK] 已调用 AMapNavi.stopNavi()");
            }
        } catch (Throwable t) {
            Repo.log("[SDK] stopNavi 异常: " + t);
        }
        runOnUiThread(new Runnable() {
            public void run() {
                if (statusBar != null) {
                    statusBar.setVisibility(View.GONE);
                }
                // 退出导航：收起 SDK 原生导航浮层，回到规划态
                try {
                    AMapNaviViewOptions opts = naviView.getViewOptions();
                    if (opts == null) {
                        opts = new AMapNaviViewOptions();
                    }
                    opts.setLayoutVisible(false);
                    naviView.setViewOptions(opts);
                } catch (Throwable ignore) {
                    // ignore
                }
                // 导航结束/退出后重新露出目的地选择面板，方便改目的地再来一次
                collapsePanel(false);
            }
        });
    }

    // ---------------------------------------------------------------- UI 刷新

    /** 每秒刷新角标：手环连上就显示「已投屏」，断开则提示（不显示帧数） */
    private void refreshStatus() {
        if (bandStatusView == null || statusBar == null
                || statusBar.getVisibility() != View.VISIBLE) {
            return;
        }
        Bridge bridge = Bridge.get();
        boolean ready = bridge.isReady();
        bandStatusView.setText(ready ? "● 手环已投屏" : "○ 手环未连接");
        bandStatusView.setTextColor(WHITE);
        bandStatusView.setBackground(rounded(ready ? 0xCC1677FF : 0xCC8A9099, 16));
    }

    private void setSetupStatus(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (setupStatus != null) {
                    setupStatus.setText(msg);
                }
                Repo.log("[页面] " + msg);
            }
        });
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

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
