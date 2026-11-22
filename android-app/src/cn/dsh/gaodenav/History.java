package cn.dsh.gaodenav;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地「最近去过 / 最近搜索」记录（高德首页与路线页都有这两块）。
 *
 * 存储用 SharedPreferences 的单字符串，字段用制表符分隔、条目用换行分隔：
 *   目的地：name \t lat \t lng \t 时间戳
 *   搜索词：keyword \t 时间戳
 * 最多各存 10 条，重复的（同名同坐标 / 同关键词）提到最前面。
 *
 * 纯 Java 逻辑（只依赖 Context 拿 SharedPreferences），方便和 AmapLink 一样单测。
 */
public final class History {

    private static final String PREF = "nav_history";
    private static final String KEY_DEST = "recent_dest";
    private static final String KEY_SEARCH = "recent_search";
    private static final int MAX = 10;

    private History() {
    }

    /** 一条历史目的地 */
    public static final class Dest {
        public final String name;
        public final double lat;
        public final double lng;
        public final long time;

        Dest(String name, double lat, double lng, long time) {
            this.name = name == null ? "" : name;
            this.lat = lat;
            this.lng = lng;
            this.time = time;
        }
    }

    public static void addDestination(Context ctx, String name, double lat, double lng) {
        if (ctx == null || !AmapLink.isInChina(lat, lng)) {
            return;
        }
        String n = name == null ? "" : name.replace("\t", " ").replace("\n", " ").trim();
        if (n.length() == 0) {
            n = "地图选点";
        }
        List<Dest> list = recentDestinations(ctx);
        // 同名同坐标视为同一条：先删旧再插到最前
        for (int i = list.size() - 1; i >= 0; i--) {
            Dest d = list.get(i);
            if (d.name.equals(n) && Math.abs(d.lat - lat) < 1e-6 && Math.abs(d.lng - lng) < 1e-6) {
                list.remove(i);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(n).append('\t').append(lat).append('\t').append(lng)
                .append('\t').append(System.currentTimeMillis());
        for (int i = 0; i < list.size() && i < MAX - 1; i++) {
            Dest d = list.get(i);
            sb.append('\n').append(d.name).append('\t').append(d.lat)
                    .append('\t').append(d.lng).append('\t').append(d.time);
        }
        prefs(ctx).edit().putString(KEY_DEST, sb.toString()).apply();
    }

    public static List<Dest> recentDestinations(Context ctx) {
        return parseDests(ctx == null ? null : prefs(ctx).getString(KEY_DEST, ""));
    }

    /** 删除一条历史目的地（长按某行时用） */
    public static void removeDestination(Context ctx, Dest target) {
        if (ctx == null || target == null) {
            return;
        }
        List<Dest> list = recentDestinations(ctx);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Dest d : list) {
            if (d.name.equals(target.name)
                    && Math.abs(d.lat - target.lat) < 1e-6
                    && Math.abs(d.lng - target.lng) < 1e-6) {
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append(d.name).append('\t').append(d.lat).append('\t').append(d.lng)
                    .append('\t').append(d.time);
        }
        prefs(ctx).edit().putString(KEY_DEST, sb.toString()).apply();
    }

    /** 清空历史目的地 */
    public static void clearDestinations(Context ctx) {
        if (ctx != null) {
            prefs(ctx).edit().remove(KEY_DEST).apply();
        }
    }

    /** 删除一条搜索词 */
    public static void removeSearch(Context ctx, String keyword) {
        if (ctx == null || keyword == null) {
            return;
        }
        List<String> list = recentSearches(ctx);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String k : list) {
            if (k.equals(keyword.trim())) {
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append(k);
        }
        prefs(ctx).edit().putString(KEY_SEARCH, sb.toString()).apply();
    }

    /** 清空历史搜索词 */
    public static void clearSearches(Context ctx) {
        if (ctx != null) {
            prefs(ctx).edit().remove(KEY_SEARCH).apply();
        }
    }

    public static void addSearch(Context ctx, String keyword) {
        if (ctx == null || keyword == null) {
            return;
        }
        String k = keyword.replace("\t", " ").replace("\n", " ").trim();
        // 链接/带链接的整段分享文本不记成搜索词（太吵，而且下次点它只会再搜一次链接）
        String lower = k.toLowerCase(java.util.Locale.US);
        if (k.length() == 0 || lower.contains("http://") || lower.contains("https://")) {
            return;
        }
        List<String> list = recentSearches(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equals(k)) {
                list.remove(i);
            }
        }
        StringBuilder sb = new StringBuilder(k).append('\t').append(System.currentTimeMillis());
        for (int i = 0; i < list.size() && i < MAX - 1; i++) {
            sb.append('\n').append(list.get(i));
        }
        prefs(ctx).edit().putString(KEY_SEARCH, sb.toString()).apply();
    }

    public static List<String> recentSearches(Context ctx) {
        List<String> out = new ArrayList<String>();
        String raw = ctx == null ? "" : prefs(ctx).getString(KEY_SEARCH, "");
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            String k = t > 0 ? line.substring(0, t) : line;
            if (k.trim().length() > 0) {
                out.add(k.trim());
            }
        }
        return out;
    }

    private static List<Dest> parseDests(String raw) {
        List<Dest> out = new ArrayList<Dest>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 3) {
                continue;
            }
            try {
                out.add(new Dest(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        p.length > 3 ? Long.parseLong(p[3]) : 0L));
            } catch (Throwable ignore) {
                // 跳过坏行
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
