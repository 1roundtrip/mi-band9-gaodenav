/**
 * NaviIconMapper 离线自测（纯 Java，不依赖 Android SDK / 高德 SDK）。
 *
 * 由 android-app/build.sh 第 1 步调用：
 *   javac -encoding UTF-8 -d $BUILD/test src/cn/dsh/gaodenav/NaviIconMapper.java \
 *         tools/IconMapperTest.java && java -cp $BUILD/test IconMapperTest
 *
 * 覆盖点：
 *   1. 每个高德 IconType 数值 -> (turn, 动作文字) 的映射结果
 *   2. 未知类型必须返回 null —— 手机端据此「保留上一帧动作」，不能被覆盖成直行
 *   3. 骑行/步行专有图标（29~54）兜底为 straight
 *   4. turn 取值必须落在手环端认识的 8 个枚举里（手环 index.ux 按文件名取图标）
 */
import cn.dsh.gaodenav.NaviIconMapper;

public final class IconMapperTest {

    /** 手环端 index.ux 的 ICONS 表只认这 8 个 turn */
    private static final String[] BAND_TURNS = {
        "left", "right", "straight", "slight-left", "slight-right",
        "uturn-left", "uturn-right", "arrive"
    };

    private static int checks = 0;
    private static int fails = 0;

    public static void main(String[] args) {
        // ---------- 直接转向 ----------
        expect("左转", 2, "left", "左转");
        expect("右转", 3, "right", "右转");
        expect("左前方", 4, "slight-left", "左前方");
        expect("右前方", 5, "slight-right", "右前方");
        expect("左后方", 6, "uturn-left", "左后方");
        expect("右后方", 7, "uturn-right", "右后方");
        expect("掉头(8)", 8, "uturn-left", "掉头");
        expect("掉头(19)", 19, "uturn-left", "掉头");
        expect("直行", 9, "straight", "直行");
        expect("顺行(20)", 20, "straight", "顺行");

        // ---------- 并线 / 靠左靠右 ----------
        expect("靠左(65)", 65, "slight-left", "靠左");
        expect("靠右(66)", 66, "slight-right", "靠右");

        // ---------- 到达类 ----------
        expect("到达目的地", 15, "arrive", "到达目的地");
        expect("到达途经点", 10, "straight", "到达途经点");
        expect("到达收费站", 14, "straight", "到达收费站");
        expect("到达服务区", 13, "straight", "到达服务区");
        expect("到达隧道", 16, "straight", "到达隧道");

        // ---------- 环岛 ----------
        expect("进入环岛(11)", 11, "straight", "进入环岛");
        int[] enterRing = {17, 25, 26, 27, 28};
        for (int i = 0; i < enterRing.length; i++) {
            expect("进入环岛(" + enterRing[i] + ")", enterRing[i], "straight", "进入环岛");
        }
        expect("驶出环岛(12)", 12, "straight", "驶出环岛");
        expect("驶出环岛(18)", 18, "straight", "驶出环岛");
        int[] inRing = {21, 22, 23, 24};
        for (int i = 0; i < inRing.length; i++) {
            expect("环岛(" + inRing[i] + ")", inRing[i], "straight", "环岛");
        }

        // ---------- 骑行/步行专有图标 29~54 兜底直行 ----------
        for (int t = 29; t <= 54; t++) {
            expect("骑行兜底(" + t + ")", t, "straight", "直行");
        }

        // ---------- 未知必须返回 null（不能覆盖上一帧） ----------
        expectNull("NONE(0)", 0);
        expectNull("DEFAULT_SELF(1)", 1);
        expectNull("55(未定义)", 55);
        expectNull("64(未定义)", 64);
        expectNull("67(未定义)", 67);
        expectNull("999(未定义)", 999);
        expectNull("-1(未定义)", -1);

        // ---------- turnOf / actionOf 与 map() 一致 ----------
        check("turnOf(2)=left", "left".equals(NaviIconMapper.turnOf(2)));
        check("actionOf(2)=左转", "左转".equals(NaviIconMapper.actionOf(2)));
        check("turnOf(0)=null", NaviIconMapper.turnOf(0) == null);
        check("actionOf(999)=null", NaviIconMapper.actionOf(999) == null);
        check("turnOf(65)=slight-left", "slight-left".equals(NaviIconMapper.turnOf(65)));

        // ---------- 全表扫描：非 null 结果的 turn 必须是手环认识的 8 个之一 ----------
        for (int t = -20; t <= 200; t++) {
            String[] m = NaviIconMapper.map(t);
            if (m == null) {
                continue;
            }
            check("map(" + t + ") 长度=2", m.length == 2);
            check("map(" + t + ") turn 合法: " + m[0], isBandTurn(m[0]));
            check("map(" + t + ") action 非空", m[1] != null && m[1].length() > 0);
        }

        // ---------- 汇总 ----------
        if (fails > 0) {
            System.out.println("IconMapperTest FAILED: " + fails + "/" + checks + " 项不通过");
            System.exit(1);
        }
        System.out.println("IconMapperTest OK: " + checks + " 项全部通过");
    }

    private static void expect(String name, int iconType, String turn, String action) {
        String[] m = NaviIconMapper.map(iconType);
        if (m == null) {
            fail(name + " -> 期望 " + turn + "/" + action + "，实际 null");
            return;
        }
        check(name + " turn=" + turn, turn.equals(m[0]));
        check(name + " action=" + action, action.equals(m[1]));
    }

    private static void expectNull(String name, int iconType) {
        check(name + " -> null", NaviIconMapper.map(iconType) == null);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            fail(name);
        }
    }

    private static void fail(String msg) {
        fails++;
        System.out.println("  [FAIL] " + msg);
    }

    private static boolean isBandTurn(String turn) {
        if (turn == null) {
            return false;
        }
        for (int i = 0; i < BAND_TURNS.length; i++) {
            if (BAND_TURNS[i].equals(turn)) {
                return true;
            }
        }
        return false;
    }

    private IconMapperTest() {
    }
}
