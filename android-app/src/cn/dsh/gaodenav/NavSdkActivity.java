package cn.dsh.gaodenav;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.enums.TransportType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapTravelInfo;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;

import java.util.ArrayList;
import java.util.List;

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

    private AMapNaviView naviView;
    private AMapNavi navi;
    private NaviCallbackAdapter callback;

    private FrameLayout root;
    private LinearLayout setupPanel;
    private LinearLayout statusBar;
    private EditText destInput;
    private TextView setupStatus;
    private TextView bandStatusView;
    private TextView frameView;
    private TextView naviInfoView;

    /** 目的地选择：可收起面板 / 候选列表 / 已选目的地 / 地图选点 */
    private ScrollView setupScroll;
    private Button expandBtn;
    private LinearLayout candidateBox;
    private TextView selectedView;
    private PoiSearch poiSearch;
    private String destName = "";
    private String destAddress = "";
    private NaviLatLng destLatLng;

    /** 导航方式：0=驾车 1=骑行 2=步行 */
    private int naviMode;
    /** 地图选点请求码 */
    private static final int REQ_MAP_PICK = 2001;
    /** 定位 SDK 客户端（起点优先用它，拿不到再退回 LocationManager） */
    private AMapLocationClient locationClient;
    private volatile NaviLatLng cachedFrom;
    /** 最近一次定位的时间（uptimeMillis）；真实导航要求「新鲜定位」 */
    private volatile long locationAtMs;
    /** 新鲜定位的有效期 */
    private static final long FRESH_LOCATION_MS = 30000L;
    /** 正在等待定位回调，拿到后自动继续算路 */
    private volatile boolean waitingLocation;
    /** 已停止导航：阻止 NaviInfo 后续回调再往手环发帧 */
    private volatile boolean naviStopped;

    private boolean naviInited;
    private boolean routeStarted;
    private boolean navActive;
    private boolean arrived;
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
            }, 1001);
        } catch (Throwable t) {
            Repo.log("[SDK] 请求定位权限失败: " + t);
        }

        handler.removeCallbacks(refresh);
        handler.post(refresh);
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
        stopNavigation("用户返回");
        finish();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        naviView = new AMapNaviView(this);
        root.addView(naviView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部状态条（导航开始后显示）
        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.VERTICAL);
        statusBar.setBackgroundColor(0xCC101216);
        statusBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bandStatusView = text("", Color.WHITE, 11, false);
        naviInfoView = text("", 0xFF8A8F98, 10, false);
        frameView = text("", 0xFF7CE38B, 9, false);
        frameView.setTypeface(Typeface.MONOSPACE);
        statusBar.addView(bandStatusView);
        statusBar.addView(naviInfoView);
        statusBar.addView(frameView);
        Button exitBtn = new Button(this);
        exitBtn.setText("退出导航");
        exitBtn.setAllCaps(false);
        exitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        exitBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopNavigation("用户点退出按钮");
                finish();
            }
        });
        statusBar.addView(exitBtn);
        statusBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams sbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.gravity = Gravity.TOP;
        root.addView(statusBar, sbLp);

        // 目的地选择面板（可收起；不挡满屏，收起后能看地图/导航视图）
        setupScroll = new ScrollView(this);
        setupScroll.setBackgroundColor(0xE8101216);

        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setPadding(dp(14), dp(18), dp(14), dp(12));

        setupPanel.addView(text("目的地选择", Color.WHITE, 18, true));
        setupPanel.addView(text("① 输入地名点「搜索」→ 点候选选定；② 或输「纬度,经度」；"
                + "③ 也可「地图选点」", 0xFF8A8F98, 10, false), margins(0, dp(6), 0, dp(8)));

        destInput = new EditText(this);
        destInput.setHint("地名，或「纬度,经度」坐标");
        destInput.setText("");
        destInput.setTextColor(Color.WHITE);
        destInput.setHintTextColor(0xFF6F7680);
        setupPanel.addView(destInput, margins(0, 0, 0, dp(6)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.addView(smallButton("搜索", new View.OnClickListener() {
            public void onClick(View v) {
                doSearch();
            }
        }), weight1());
        searchRow.addView(smallButton("地图选点", new View.OnClickListener() {
            public void onClick(View v) {
                openMapPicker();
            }
        }), weight1());
        searchRow.addView(smallButton("收起", new View.OnClickListener() {
            public void onClick(View v) {
                collapsePanel(true);
            }
        }), weight1());
        setupPanel.addView(searchRow, margins(0, 0, 0, dp(8)));

        selectedView = text("未选择目的地", 0xFF7CE38B, 12, true);
        setupPanel.addView(selectedView, margins(0, 0, 0, dp(6)));

        candidateBox = new LinearLayout(this);
        candidateBox.setOrientation(LinearLayout.VERTICAL);
        setupPanel.addView(candidateBox, margins(0, 0, 0, dp(6)));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.addView(smallButton("驾车", new View.OnClickListener() {
            public void onClick(View v) {
                prepareRoute(0);
            }
        }), weight1());
        modeRow.addView(smallButton("骑行", new View.OnClickListener() {
            public void onClick(View v) {
                prepareRoute(1);
            }
        }), weight1());
        modeRow.addView(smallButton("步行", new View.OnClickListener() {
            public void onClick(View v) {
                prepareRoute(2);
            }
        }), weight1());
        setupPanel.addView(modeRow);

        setupStatus = text("", 0xFFE0B25A, 11, false);
        setupPanel.addView(setupStatus, margins(0, dp(8), 0, 0));

        setupScroll.addView(setupPanel);
        FrameLayout.LayoutParams ssLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssLp.gravity = Gravity.TOP;
        root.addView(setupScroll, ssLp);

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

        setContentView(root);
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
        // 每次重新发起算路都重置导航状态（允许用户退出后再次开始）
        naviStopped = false;
        routeStarted = false;
        navActive = false;
        frameCount = 0;
        lastTurn = "";
        lastAction = "";
        pendingTo = destLatLng;
        setSetupStatus("准备" + modeName() + "算路…");
        requestRouteWhenReady();
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

    /** 候选列表：名称 + 详细地址 + 距离（有则显示）；点击一条选中 */
    private void showCandidates(java.util.List<PoiItem> pois) {
        clearCandidates();
        setSetupStatus("找到 " + pois.size() + " 个候选，点一条选中（第 2 行是地址）");
        int n = Math.min(pois.size(), 10);
        for (int i = 0; i < n; i++) {
            final PoiItem item = pois.get(i);
            if (item == null || item.getLatLonPoint() == null) {
                continue;
            }
            final String title = safe(item.getTitle());
            final String addr = safe(item.getSnippet());
            final LatLonPoint point = item.getLatLonPoint();
            int distance = item.getDistance();
            String distText = distance > 0 ? ("  " + formatDistance(distance)) : "";
            TextView row = new TextView(this);
            row.setText((i + 1) + ". " + title + "\n     " + addr + distText);
            row.setTextColor(0xFFE8EEF4);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundColor(i % 2 == 0 ? 0x22FFFFFF : 0x14FFFFFF);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectDestination(title, addr,
                            new NaviLatLng(point.getLatitude(), point.getLongitude()), "搜索");
                }
            });
            candidateBox.addView(row);
        }
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
        setSetupStatus("已设定目的地（" + from + "），点下方「驾车/骑行/步行」开始导航");
        Repo.log("[SDK] 目的地(" + from + "): " + destName + " " + destAddress + " " + latLng);
    }

    private void setSelectedText() {
        if (selectedView == null) {
            return;
        }
        if (destLatLng == null) {
            selectedView.setText("未选择目的地");
            return;
        }
        selectedView.setText("已选：" + destName
                + (destAddress.length() > 0 ? "（" + destAddress + "）" : "")
                + "\n坐标：" + destLatLng);
    }

    private void openMapPicker() {
        try {
            Intent intent = new Intent(this, MapPickActivity.class);
            startActivityForResult(intent, REQ_MAP_PICK);
        } catch (Throwable t) {
            setSetupStatus("打开地图选点失败：" + t);
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
        selectDestination(name == null ? "地图选点" : name,
                addr == null ? "" : addr, new NaviLatLng(lat, lng), "地图选点");
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
            return String.format(java.util.Locale.US, "%.1fkm", meters / 1000.0);
        }
        return meters + "m";
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setOnClickListener(l);
        return b;
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
            NaviLatLng from = currentLatLng();
            if (from == null) {
                // 真实导航但没有新鲜定位：已触发一次定位请求，等回调后自动继续
                setSetupStatus("等待定位…（导航需要实时定位，请到窗边/开阔处并确认已允许定位权限）");
                return;
            }
            if (naviMode == 1) {
                navi.setIsNaviTravelView(true);
                navi.setTravelInfo(new AMapTravelInfo(TransportType.Ride));
                navi.calculateRideRoute(from, to);
                setSetupStatus("已请求骑行算路…");
            } else if (naviMode == 2) {
                navi.setIsNaviTravelView(true);
                navi.setTravelInfo(new AMapTravelInfo(TransportType.Walk));
                navi.calculateWalkRoute(from, to);
                setSetupStatus("已请求步行算路…");
            } else {
                List<NaviLatLng> fromList = new ArrayList<NaviLatLng>();
                fromList.add(from);
                List<NaviLatLng> toList = new ArrayList<NaviLatLng>();
                toList.add(to);
                List<NaviLatLng> viaList = new ArrayList<NaviLatLng>();
                int strategy = navi.strategyConvert(true, false, false, false, false);
                navi.calculateDriveRoute(fromList, toList, viaList, strategy);
                setSetupStatus("已请求驾车算路…");
            }
            Repo.log("[SDK] 算路请求已发出 mode=" + modeName());
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
                        if (waitingLocation) {
                            waitingLocation = false;
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
                        Repo.log("[SDK] AMapLocation 失败 code=" + location.getErrorCode()
                                + " " + location.getErrorInfo());
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
        try {
            String s = raw.replace('，', ',').replace(' ', ',');
            String[] parts = s.split(",");
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
                setSetupStatus("导航初始化成功");
                if (pendingTo != null && !routeStarted && !naviStopped) {
                    doRequestRoute(pendingTo);
                }
            }
        });
    }

    public void onRouteSuccess(AMapCalcRouteResult result) {
        if (routeStarted || naviStopped) {
            return;
        }
        routeStarted = true;
        runOnUiThread(new Runnable() {
            public void run() {
                collapsePanel(true);
                statusBar.setVisibility(View.VISIBLE);
                try {
                    navi.startNavi(NaviType.GPS);
                    Repo.log("[SDK] startNavi(GPS)");
                } catch (Throwable t) {
                    Repo.log("[SDK] startNavi 异常: " + t);
                }
                navActive = true;
                Repo.navActive = true;
            }
        });
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
            Repo.lastFrame = json;
            Repo.lastParse = "NaviInfo icon=" + icon
                    + " road=" + info.getNextRoadName()
                    + " stepM=" + info.getCurStepRetainDistance()
                    + " remainM=" + info.getPathRetainDistance()
                    + " remainS=" + info.getPathRetainTime();
            Repo.log("[SDK] 帧#" + frameCount + " " + json);

            Bridge bridge = Bridge.get();
            bridge.init(this);
            bridge.sendFrame(json);
            navActive = true;
            Repo.navActive = true;
            updateFrameUi(info, json);
        } catch (Throwable t) {
            Repo.log("[SDK] 处理 NaviInfo 异常: " + t);
        }
    }

    public void onArrive() {
        arrived = true;
        stopNavigation("到达目的地");
        runOnUiThread(new Runnable() {
            public void run() {
                setSetupStatus("已到达目的地");
            }
        });
    }

    public void onExit() {
        stopNavigation("导航结束回调");
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
        Repo.navActive = false;
        Repo.log("[SDK] 结束导航（" + reason + "），发送 stop");
        Bridge bridge = Bridge.get();
        bridge.init(this);
        bridge.sendFrame(NavFrame.stopJson());
        Repo.lastFrame = NavFrame.stopJson();
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
                // 导航结束/退出后重新露出目的地选择面板，方便改目的地再来一次
                collapsePanel(false);
            }
        });
    }

    // ---------------------------------------------------------------- UI 刷新

    private void updateFrameUi(final NaviInfo info, final String json) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (naviInfoView != null) {
                    naviInfoView.setText("图标类型=" + info.getIconType()
                            + "  下一路口=" + info.getCurStepRetainDistance() + "m"
                            + "  剩余=" + info.getPathRetainDistance() + "m/"
                            + info.getPathRetainTime() + "s"
                            + "\n下条路：" + info.getNextRoadName());
                }
                if (frameView != null) {
                    frameView.setText(json);
                }
            }
        });
    }

    private void refreshStatus() {
        if (bandStatusView == null) {
            return;
        }
        Bridge bridge = Bridge.get();
        bandStatusView.setText("手环：" + bridge.stateText()
                + "  |  已下发 " + bridge.sentCount() + " 帧 / 成功 " + bridge.okCount() + " 帧"
                + (navActive ? "  |  导航中" : "  |  未开始"));
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
