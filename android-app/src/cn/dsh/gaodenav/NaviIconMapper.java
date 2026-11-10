package cn.dsh.gaodenav;

/**
 * 高德导航 SDK 的转向图标类型（com.amap.api.navi.enums.IconType 的数值）
 * -> 手环帧的 turn 枚举 + 中文动作文案。
 *
 * 纯 Java，不依赖 SDK 类，方便离线自测（build.sh 的 ParserTest 会跑它）。
 * 数值来自官方 javadoc：
 *   https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/enums/IconType.html
 *
 * 手环端只认这 8 个 turn：
 *   left | right | straight | slight-left | slight-right | uturn-left | uturn-right | arrive
 */
public final class NaviIconMapper {

    private NaviIconMapper() {
    }

    /** IconType 数值 */
    public static final int NONE = 0;
    public static final int DEFAULT_SELF = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;
    public static final int LEFT_FRONT = 4;
    public static final int RIGHT_FRONT = 5;
    public static final int LEFT_BACK = 6;
    public static final int RIGHT_BACK = 7;
    public static final int LEFT_TURN_AROUND = 8;
    public static final int STRAIGHT = 9;
    public static final int ARRIVED_WAYPOINT = 10;
    public static final int ENTER_ROUNDABOUT = 11;
    public static final int OUT_ROUNDABOUT = 12;
    public static final int ARRIVED_SERVICE_AREA = 13;
    public static final int ARRIVED_TOLLGATE = 14;
    public static final int ARRIVED_DESTINATION = 15;
    public static final int ARRIVED_TUNNEL = 16;
    public static final int SPECIAL_CONTINUE = 20;
    public static final int U_TURN_RIGHT = 19;
    public static final int MERGE_LEFT = 65;
    public static final int MERGE_RIGHT = 66;

    /**
     * @param iconType NaviInfo.getIconType()
     * @return {turn, actionText}；返回 null 表示「未知，不要覆盖上一帧动作」
     */
    public static String[] map(int iconType) {
        switch (iconType) {
            case LEFT:
                return new String[] {"left", "左转"};
            case RIGHT:
                return new String[] {"right", "右转"};
            case LEFT_FRONT:
                return new String[] {"slight-left", "左前方"};
            case RIGHT_FRONT:
                return new String[] {"slight-right", "右前方"};
            case LEFT_BACK:
                return new String[] {"uturn-left", "左后方"};
            case RIGHT_BACK:
                return new String[] {"uturn-right", "右后方"};
            case LEFT_TURN_AROUND:
            case U_TURN_RIGHT:
                return new String[] {"uturn-left", "掉头"};
            case STRAIGHT:
                return new String[] {"straight", "直行"};
            case SPECIAL_CONTINUE:
                return new String[] {"straight", "顺行"};
            case MERGE_LEFT:
                return new String[] {"slight-left", "靠左"};
            case MERGE_RIGHT:
                return new String[] {"slight-right", "靠右"};
            case ARRIVED_DESTINATION:
                return new String[] {"arrive", "到达目的地"};
            case ARRIVED_WAYPOINT:
                return new String[] {"straight", "到达途经点"};
            case ARRIVED_TOLLGATE:
                return new String[] {"straight", "到达收费站"};
            case ARRIVED_SERVICE_AREA:
                return new String[] {"straight", "到达服务区"};
            case ARRIVED_TUNNEL:
                return new String[] {"straight", "到达隧道"};
            case ENTER_ROUNDABOUT:
            case 17: // ENTRY_LEFT_RING
            case 25: // ENTRY_LEFT_RING_LEFT
            case 26: // ENTRY_LEFT_RING_RIGHT
            case 27: // ENTRY_LEFT_RING_CONTINUE
            case 28: // ENTRY_LEFTRINGU_TURN
                return new String[] {"straight", "进入环岛"};
            case OUT_ROUNDABOUT:
            case 18: // LEAVE_LEFT_RING
                return new String[] {"straight", "驶出环岛"};
            case 21: // ENTRY_RING_LEFT
            case 22: // ENTRY_RING_RIGHT
            case 23: // ENTRY_RING_CONTINUE
            case 24: // ENTRY_RING_UTURN
                return new String[] {"straight", "环岛"};
            case NONE:
            case DEFAULT_SELF:
            default:
                // 骑行/步行专有图标（29~54）等一律按直行兜底但保留动作文字
                if (iconType >= 29 && iconType <= 54) {
                    return new String[] {"straight", "直行"};
                }
                return null;
        }
    }

    /** 只要 turn（未知返回 null） */
    public static String turnOf(int iconType) {
        String[] m = map(iconType);
        return m == null ? null : m[0];
    }

    /** 只要动作文案（未知返回 null） */
    public static String actionOf(int iconType) {
        String[] m = map(iconType);
        return m == null ? null : m[1];
    }
}
