package cn.dsh.gaodenav;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
 * 地图选点：3D 地图 SDK（AMap MapView）+ 点击落点 + 逆地理地址 + 确认回填。
 *
 * 返回给调用方（NavSdkActivity）：
 *   onActivityResult(RESULT_OK) extras: lat(double) lng(double) name(String) address(String)
 */
public class MapPickActivity extends Activity {

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_ADDRESS = "address";

    private MapView mapView;
    private AMap aMap;
    private Marker marker;
    private TextView coordView;
    private TextView addressView;
    private TextView hintView;
    private GeocodeSearch geocodeSearch;
    private LatLng picked;
    private String pickedAddress = "";
    private boolean geocoding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AmapPrivacy.init(this);

        // 高德 Key：不内置在包里；未配置时回主界面填写（地图加载同样需要 Key）
        if (!AmapKey.hasKey(this)) {
            Repo.log("[地图选点] 未配置高德 Key，返回主界面");
            Toast.makeText(this, "请先在主界面填写高德 Key", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        AmapKey.apply(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        try {
            mapView = new MapView(this);
            root.addView(mapView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mapView.onCreate(savedInstanceState);
            aMap = mapView.getMap();
            aMap.setOnMapClickListener(new AMap.OnMapClickListener() {
                public void onMapClick(LatLng latLng) {
                    onPicked(latLng);
                }
            });
            // 默认落到中性位置（北京天安门附近），用户可拖动/缩放；不使用设备定位作初始点
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9087, 116.3975), 12));
        } catch (Throwable t) {
            Repo.log("[地图选点] MapView 初始化失败: " + t);
        }

        // 顶部提示
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xCC101216);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = new TextView(this);
        title.setText("地图选点：点击地图任意位置作为目的地");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        top.addView(title);
        hintView = new TextView(this);
        hintView.setText("可双指缩放/拖动地图；点击后出现标记");
        hintView.setTextColor(0xFF8A8F98);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        top.addView(hintView);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        // 底部确认栏
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xE8101216);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10));
        coordView = new TextView(this);
        coordView.setText("未选择位置");
        coordView.setTextColor(0xFFE0B25A);
        coordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bottom.addView(coordView);
        addressView = new TextView(this);
        addressView.setText("");
        addressView.setTextColor(0xFFCDD3DA);
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bottom.addView(addressView, margins(0, dp(4), 0, dp(8)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        Button confirm = new Button(this);
        confirm.setText("用这个位置");
        confirm.setAllCaps(false);
        confirm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmPick();
            }
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(confirm, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(row);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomLp);

        setContentView(root);
    }

    // ---------------------------------------------------------------- 选点

    private void onPicked(LatLng latLng) {
        if (latLng == null) {
            return;
        }
        picked = latLng;
        if (coordView != null) {
            coordView.setText(String.format(java.util.Locale.US, "已选：%.6f,%.6f",
                    latLng.latitude, latLng.longitude));
        }
        pickedAddress = "";
        if (addressView != null) {
            addressView.setText("正在解析地址…");
        }
        try {
            if (aMap != null) {
                if (marker != null) {
                    marker.remove();
                }
                marker = aMap.addMarker(new MarkerOptions().position(latLng).title("目的地"));
                aMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            }
        } catch (Throwable t) {
            Repo.log("[地图选点] 落点异常: " + t);
        }
        reverseGeocode(latLng);
    }

    private void reverseGeocode(final LatLng latLng) {
        try {
            if (geocodeSearch == null) {
                geocodeSearch = new GeocodeSearch(this);
                geocodeSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                    public void onRegeocodeSearched(RegeocodeResult result, int code) {
                        geocoding = false;
                        String addr = "";
                        if (code == 1000 && result != null) {
                            RegeocodeAddress a = result.getRegeocodeAddress();
                            if (a != null) {
                                addr = a.getFormatAddress();
                            }
                        }
                        pickedAddress = addr == null ? "" : addr;
                        if (addressView != null) {
                            addressView.setText(pickedAddress.length() > 0
                                    ? pickedAddress : "（地址解析失败，将用坐标作为名称）");
                        }
                    }

                    public void onGeocodeSearched(GeocodeResult result, int code) {
                        // 用不到正向地理编码
                    }
                });
            }
            geocoding = true;
            geocodeSearch.getFromLocationAsyn(new RegeocodeQuery(
                    new LatLonPoint(latLng.latitude, latLng.longitude), 200, GeocodeSearch.AMAP));
        } catch (Throwable t) {
            geocoding = false;
            pickedAddress = "";
            Repo.log("[地图选点] 逆地理失败: " + t);
            if (addressView != null) {
                addressView.setText("（逆地理不可用，将用坐标作为名称）");
            }
        }
    }

    private void confirmPick() {
        if (picked == null) {
            if (hintView != null) {
                hintView.setText("请先点击地图选择位置");
            }
            return;
        }
        String name = pickedAddress.length() > 0
                ? pickedAddress
                : String.format(java.util.Locale.US, "地图选点 %.6f,%.6f",
                        picked.latitude, picked.longitude);
        Intent data = new Intent();
        data.putExtra(EXTRA_LAT, picked.latitude);
        data.putExtra(EXTRA_LNG, picked.longitude);
        data.putExtra(EXTRA_NAME, name);
        data.putExtra(EXTRA_ADDRESS, pickedAddress);
        setResult(RESULT_OK, data);
        finish();
    }

    // ---------------------------------------------------------------- 生命周期（MapView 必须全接）

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
            if (geocodeSearch != null) {
                geocodeSearch = null;
            }
        } catch (Throwable ignore) {
            // ignore
        }
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
