package cn.dsh.gaodenav;

import android.content.Context;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;

/**
 * 高德 SDK 隐私合规统一入口（8.1.0+ / 定位 5.6.0+ / 搜索 9.x 都必须先调）。
 *
 * 调用顺序要求：任何 SDK 接口（地图、导航、定位、搜索）之前。
 * 这里：
 *  - 地图/导航：MapsInitializer.updatePrivacyShow / updatePrivacyAgree（直接 import，已验证可编译）
 *  - 定位：AMapLocationClient.updatePrivacyShow / updatePrivacyAgree
 *  - 搜索：ServiceSettings.updatePrivacyShow / updatePrivacyAgree（用反射，避免不同搜索 SDK 版本差异卡编译）
 */
public final class AmapPrivacy {

    private static volatile boolean done;

    private AmapPrivacy() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        // 每次都调也无害（幂等），但不重复刷日志
        try {
            MapsInitializer.updatePrivacyShow(context, true, true);
            MapsInitializer.updatePrivacyAgree(context, true);
        } catch (Throwable t) {
            Repo.log("[SDK] 地图隐私合规调用失败: " + t);
        }
        try {
            AMapLocationClient.updatePrivacyShow(context, true, true);
            AMapLocationClient.updatePrivacyAgree(context, true);
        } catch (Throwable t) {
            Repo.log("[SDK] 定位隐私合规调用失败: " + t);
        }
        // 搜索 SDK（9.x 有 ServiceSettings.updatePrivacyShow/Agree；反射调用，缺失不影响其他 SDK）
        try {
            Class<?> cls = Class.forName("com.amap.api.services.core.ServiceSettings");
            java.lang.reflect.Method show = cls.getMethod("updatePrivacyShow",
                    Context.class, boolean.class, boolean.class);
            java.lang.reflect.Method agree = cls.getMethod("updatePrivacyAgree",
                    Context.class, boolean.class);
            show.invoke(null, context, Boolean.TRUE, Boolean.TRUE);
            agree.invoke(null, context, Boolean.TRUE);
        } catch (Throwable ignore) {
            // 搜索 SDK 版本没有该接口时不重要
        }
        if (!done) {
            done = true;
            Repo.log("[SDK] 已调用高德隐私合规接口（地图/定位/搜索）");
        }
    }
}
