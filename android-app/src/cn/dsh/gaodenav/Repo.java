package cn.dsh.gaodenav;

import android.util.Log;

/** 帧序号 + logcat 输出。ponytail: 原日志环形缓冲没有任何 UI 消费者，删。 */
public final class Repo {

    private static final String TAG = "GaoDeNav";

    private static final Object LOCK = new Object();
    private static long seq = 0L;

    private Repo() {
    }

    /**
     * 生成单调递增的帧序号。
     * 用秒级 Unix 时间戳做下限，而不是纯进程内计数：App 被系统强杀重启后，
     * 进程内计数会从 1 重新来，但手环端快应用若仍存活（没收到 stop），
     * 其 lastSeq 还是上一程的大值，导致新程所有帧的 msg.seq > lastSeq 恒为 false，
     * 转弯震动全部丢失。时间戳基准保证跨进程重启依然递增。
     * 同一秒内的多帧用 seq+1 保证严格递增。
     */
    public static int nextSeq() {
        synchronized (LOCK) {
            long wallSeconds = System.currentTimeMillis() / 1000L;
            seq = Math.max(seq + 1, wallSeconds);
            if (seq > Integer.MAX_VALUE - 8) {
                seq = 1;
            }
            return (int) seq;
        }
    }

    public static void log(String msg) {
        Log.d(TAG, msg == null ? "null" : msg);
    }
}
