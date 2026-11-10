package cn.dsh.gaodenav;

import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviViewListener;
import com.amap.api.navi.AmapPageType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapNaviRouteNotifyData;
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo;
import com.amap.api.navi.model.AMapServiceAreaInfo;
import com.amap.api.navi.model.AimLessModeCongestionInfo;
import com.amap.api.navi.model.AimLessModeStat;
import com.amap.api.navi.model.NaviInfo;

/**
 * 高德导航 SDK 回调适配器（AMapNaviListener + AMapNaviViewListener）。
 *
 * 说明：
 *  - 高德这两个接口方法很多（合计 50+），这里按官方 Demo 的 BaseActivity 原样实现，
 *    默认空实现，只把需要的回调转给 Host（NavSdkActivity）。
 *  - 故意**不加 @Override**：SDK 不同小版本的方法集有增删，加了 @Override 反而容易
 *    编译失败；这里多写的方法（如果某个版本没有）只是普通方法，不影响。
 *  - 如果某个版本新增了接口方法而我们没实现，编译器会明确报「未实现抽象方法」，
 *    照抄一个空方法即可。
 */
public class NaviCallbackAdapter implements AMapNaviListener, AMapNaviViewListener {

    /** 真正关心的回调 */
    public interface Host {
        /** 导航初始化成功（AMapNavi 就绪，可以算路） */
        void onNaviInited();

        /** 算路成功 */
        void onRouteSuccess(AMapCalcRouteResult result);

        /** 算路失败 */
        void onRouteFailure(AMapCalcRouteResult result);

        /** 导航引导信息更新（每秒约 1 次，核心数据源） */
        void onNaviInfo(NaviInfo info);

        /** 到达目的地 */
        void onArrive();

        /** 退出/结束导航（视图退出、模拟结束、取消等） */
        void onExit();

        /** 需要记录到 App 日志的事件 */
        void onLog(String message);
    }

    private final Host host;

    public NaviCallbackAdapter(Host host) {
        this.host = host;
    }

    // ================================================================
    // AMapNaviListener
    // ================================================================

    public void onInitNaviFailure() {
        host.onLog("onInitNaviFailure（高德导航初始化失败，检查 Key/SHA1/包名/网络）");
    }

    public void onInitNaviSuccess() {
        host.onLog("onInitNaviSuccess（高德导航初始化成功）");
        host.onNaviInited();
    }

    public void onStartNavi(int type) {
        host.onLog("onStartNavi type=" + type);
    }

    public void onTrafficStatusUpdate() {
    }

    public void onLocationChange(AMapNaviLocation location) {
    }

    public void onGetNavigationText(int type, String text) {
        host.onLog("导航播报[" + type + "]: " + text);
    }

    public void onGetNavigationText(String text) {
        host.onLog("导航播报: " + text);
    }

    public void onEndEmulatorNavi() {
        host.onLog("导航结束");
        host.onExit();
    }

    public void onArriveDestination() {
        host.onLog("到达目的地");
        host.onArrive();
    }

    public void onCalculateRouteFailure(int errorInfo) {
        host.onLog("onCalculateRouteFailure(旧回调) errorInfo=" + errorInfo);
    }

    public void onReCalculateRouteForYaw() {
        host.onLog("偏航重算");
    }

    public void onReCalculateRouteForTrafficJam() {
        host.onLog("拥堵重算");
    }

    public void onArrivedWayPoint(int wayID) {
    }

    public void onGpsOpenStatus(boolean enabled) {
        host.onLog("GPS 开关状态=" + enabled);
    }

    public void updateCameraInfo(AMapNaviCameraInfo[] infos) {
    }

    public void onServiceAreaUpdate(AMapServiceAreaInfo[] infos) {
    }

    /** 核心：导航引导信息 */
    public void onNaviInfoUpdate(NaviInfo naviinfo) {
        host.onNaviInfo(naviinfo);
    }

    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo info) {
    }

    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] infos) {
    }

    public void showCross(AMapNaviCross cross) {
    }

    public void hideCross() {
    }

    public void showModeCross(AMapModelCross modelCross) {
    }

    public void hideModeCross() {
    }

    public void showLaneInfo(AMapLaneInfo[] laneInfos, byte[] laneBackgroundInfo,
                             byte[] laneRecommendedInfo) {
    }

    public void showLaneInfo(AMapLaneInfo laneInfo) {
    }

    public void hideLaneInfo() {
    }

    public void onCalculateRouteSuccess(int[] ids) {
        host.onLog("onCalculateRouteSuccess(旧回调) ids=" + (ids == null ? 0 : ids.length));
    }

    public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        String msg = result == null ? "null"
                : ("code=" + result.getErrorCode() + " desc=" + result.getErrorDescription());
        host.onLog("算路失败: " + msg);
        host.onRouteFailure(result);
    }

    public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
        host.onLog("算路成功");
        host.onRouteSuccess(result);
    }

    public void notifyParallelRoad(int parallelRoadType) {
    }

    public void updateAimlessModeStatistics(AimLessModeStat stat) {
    }

    public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo info) {
    }

    public void onPlayRing(int type) {
    }

    public void onNaviRouteNotify(AMapNaviRouteNotifyData data) {
    }

    public void onGpsSignalWeak(boolean weak) {
        host.onLog("GPS 信号" + (weak ? "弱" : "正常"));
    }

    // ================================================================
    // AMapNaviViewListener
    // ================================================================

    public void onNaviSetting() {
    }

    public void onNaviMapMode(int naviMode) {
    }

    public void onNaviCancel() {
        host.onLog("onNaviCancel");
        host.onExit();
    }

    public void onNaviTurnClick() {
    }

    public void onNextRoadClick() {
    }

    public void onScanViewButtonClick() {
    }

    public void onLockMap(boolean isLock) {
    }

    public void onNaviViewLoaded() {
        host.onLog("onNaviViewLoaded（导航视图加载完成）");
    }

    public void onMapTypeChanged(int mapType) {
    }

    public void onNaviViewShowMode(int showMode) {
    }

    public void onStopSpeaking() {
    }

    public void onViewTypeChanged(AmapPageType pageType) {
    }

    public void onAMapNaviViewExit() {
        host.onLog("onAMapNaviViewExit");
        host.onExit();
    }

    /** 兼容个别版本命名的退出回调（多写一个空别名无副作用） */
    public void onMapNaviViewExit() {
        host.onLog("onMapNaviViewExit");
        host.onExit();
    }

    public void onStrategyChanged(int strategy) {
    }

    public void onBroadcastModeChanged(int mode) {
    }

    public void onDayAndNightModeChanged(int mode) {
    }

    public void onScaleAutoChanged(boolean enable) {
    }

    public void onListenToVoiceDuringCallChanged(boolean listen) {
    }

    public void onControlMusicVolumeModeChanged(int mode) {
    }

    public void onEagleChanged(boolean show) {
    }

    public void onNaviRouteHighlightChange(long routeId, int type) {
    }

    public boolean onNaviBackClick() {
        return false;
    }

    // SDK 11.3 接口方法：区间测速摄像头信息（空实现）
    public void updateIntervalCameraInfo(AMapNaviCameraInfo start, AMapNaviCameraInfo end, int interval) {
    }
}
