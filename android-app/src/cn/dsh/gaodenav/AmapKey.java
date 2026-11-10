package cn.dsh.gaodenav;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.navi.AMapNavi;
import com.amap.api.services.core.ServiceSettings;

/**
 * 高德 Key 的运行时管理：**不把 Key 打进 APK**，由用户在 App 主界面自己填写并保存。
 *
 * 高德 SDK 四个模块都支持运行时 setApiKey（已用 javap 核实）：
 *   - 地图/导航：MapsInitializer.setApiKey(String)
 *   - 定位：    AMapLocationClient.setApiKey(String)
 *   - 搜索：    ServiceSettings.getInstance().setApiKey(String)
 *   - 导航：    AMapNavi.setApiKey(Context, String)
 *
 * 调用顺序必须是：隐私合规（AmapPrivacy.init）→ AmapKey.apply() → 任何 SDK 初始化/地图创建。
 */
public final class AmapKey {

    /** SharedPreferences 文件名与 Key 名（与需求约定一致） */
    private static final String PREFS = "app_prefs";
    private static final String PREF_KEY = "amap_key";

    private AmapKey() {
    }

    /** 读取用户填写的 Key；未配置返回 "" */
    public static String get(Context context) {
        if (context == null) {
            return "";
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String k = sp.getString(PREF_KEY, "");
            return k == null ? "" : k.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean hasKey(Context context) {
        return get(context).length() > 0;
    }

    /** 保存 Key（去掉首尾空白）；返回是否成功保存 */
    public static boolean save(Context context, String key) {
        if (context == null) {
            return false;
        }
        String k = key == null ? "" : key.trim();
        if (k.length() == 0) {
            return false;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.edit().putString(PREF_KEY, k).commit();
        } catch (Throwable t) {
            Repo.log("保存高德 Key 失败: " + t);
            return false;
        }
    }

    /**
     * 把已保存的 Key 应用到高德各模块。必须在隐私合规之后、SDK 初始化之前调用。
     *
     * @return 是否成功应用（未配置 Key 返回 false）
     */
    public static boolean apply(Context context) {
        String key = get(context);
        if (key.length() == 0) {
            return false;
        }
        try {
            MapsInitializer.setApiKey(key);
        } catch (Throwable t) {
            Repo.log("MapsInitializer.setApiKey 失败: " + t);
        }
        try {
            AMapLocationClient.setApiKey(key);
        } catch (Throwable t) {
            Repo.log("AMapLocationClient.setApiKey 失败: " + t);
        }
        try {
            ServiceSettings.getInstance().setApiKey(key);
        } catch (Throwable t) {
            Repo.log("ServiceSettings.setApiKey 失败: " + t);
        }
        try {
            AMapNavi.setApiKey(context, key);
        } catch (Throwable t) {
            Repo.log("AMapNavi.setApiKey 失败: " + t);
        }
        Repo.log("高德 Key 已应用（长度 " + key.length() + "）");
        return true;
    }

    /** 展示用：只露前 4 位与后 4 位 */
    public static String mask(Context context) {
        String k = get(context);
        if (k.length() <= 8) {
            return k.length() == 0 ? "未配置" : "****";
        }
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }

    /** Key 基本格式检查（高德 Android Key 为 32 位十六进制，仅提示不强制） */
    public static boolean looksValid(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        String k = key.trim();
        if (k.length() != 32) {
            return false;
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
