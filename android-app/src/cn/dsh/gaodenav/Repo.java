package cn.dsh.gaodenav;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 极简全局状态 + 日志环形缓冲。MainActivity 每秒轮询渲染，方便截图调试。
 * 不用 Listener/Broadcast，减少组件间耦合和生命周期问题。
 */
public final class Repo {

    private static final int MAX_LOG = 120;
    private static final Object LOCK = new Object();

    /** 最近一次成功下发的导航帧 JSON */
    public static volatile String lastFrame = "（暂无）";
    /** 最近一次解析结果的可读描述（未识别也会写，方便看高德界面结构） */
    public static volatile String lastParse = "（暂无）";
    /** 当前导航是否处于活动状态（服务判定） */
    public static volatile boolean navActive = false;
    /** 手环互联状态（Bridge 写，UI 读） */
    public static volatile String bandState = "未初始化";
    /** 最近一次错误 */
    public static volatile String lastError = "";

    private static final List<String> LOG = new ArrayList<String>();
    private static long seq = 0L;

    private Repo() {
    }

    public static int nextSeq() {
        synchronized (LOCK) {
            seq++;
            if (seq > Integer.MAX_VALUE - 8) {
                seq = 1;
            }
            return (int) seq;
        }
    }

    public static void log(String msg) {
        if (msg == null) {
            msg = "null";
        }
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOCK) {
            LOG.add(ts + " " + msg);
            while (LOG.size() > MAX_LOG) {
                LOG.remove(0);
            }
        }
    }

    public static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (int i = 0; i < LOG.size(); i++) {
                sb.append(LOG.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clearLog() {
        synchronized (LOCK) {
            LOG.clear();
        }
    }
}
