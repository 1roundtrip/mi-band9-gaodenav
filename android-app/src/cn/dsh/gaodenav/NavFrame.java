package cn.dsh.gaodenav;

/**
 * 手环端 JSON 帧（与 rpk 端 pages/index 约定一致）：
 * {"type":"nav","seq":N,"turn":"left","action":"左转","distMeters":300,
 *  "remainSeconds":720,"remainMeters":3200,"vibrate":true}
 * {"type":"stop"}
 *
 * turn 取值：left | right | straight | slight-left | slight-right |
 *            uturn-left | uturn-right | arrive
 *
 * 注意：rpk 端 formatDist/formatRemain 对 undefined 不显示；对 -1 会显示成「0米」，
 * 所以未知字段（<0）这里直接**不输出**，让手环端隐藏该行。
 */
public final class NavFrame {

    public String turn = "straight";
    public String action = "";
    public int distMeters = -1;      // 下一动作距离，-1 表示未知
    public int remainSeconds = -1;   // 剩余时间，-1 表示未知
    public int remainMeters = -1;    // 剩余里程，-1 表示未知
    public boolean vibrate = true;

    /** 去重用键：内容完全一致视为同一帧 */
    public String key() {
        return turn + "|" + action + "|" + distMeters + "|" + remainSeconds + "|" + remainMeters;
    }

    public String toJson(int seq) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"type\":\"nav\",");
        sb.append("\"seq\":").append(seq).append(',');
        sb.append("\"turn\":\"").append(escape(turn)).append("\",");
        sb.append("\"action\":\"").append(escape(action)).append("\"");
        if (distMeters >= 0) {
            sb.append(",\"distMeters\":").append(distMeters);
        }
        if (remainSeconds >= 0) {
            sb.append(",\"remainSeconds\":").append(remainSeconds);
        }
        if (remainMeters >= 0) {
            sb.append(",\"remainMeters\":").append(remainMeters);
        }
        sb.append(",\"vibrate\":").append(vibrate);
        sb.append('}');
        return sb.toString();
    }

    public static String stopJson() {
        return "{\"type\":\"stop\"}";
    }

    /** 手环端只做 JSON.parse，转义控制字符和引号即可 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "NavFrame{turn=" + turn + ", action=" + action + ", dist=" + distMeters
                + ", remainS=" + remainSeconds + ", remainM=" + remainMeters + "}";
    }
}
