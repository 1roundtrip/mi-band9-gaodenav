package cn.dsh.gaodenav;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高德链接 / 坐标解析：把「高德 App 分享的链接」或一段裸坐标解析成目的地。
 *
 * 支持的格式（离线即可解析，无需联网）：
 *   1) https://uri.amap.com/marker?position=116.3975,39.9087&name=天安门
 *   2) https://uri.amap.com/navigation?to=116.3975,39.9087,天安门&mode=car
 *   3) https://uri.amap.com/navi?dest=116.3975,39.9087&destName=天安门
 *   4) androidamap://viewMap?sourceApplication=x&poiname=天安门&lat=39.9&lon=116.4
 *   5) https://m.amap.com/navi/?dest=116.3975,39.9087&destName=天安门
 *   6) 裸坐标「39.9087, 116.3975」（两侧都必须带小数，见下）
 *
 * 短链（如 https://s.amap.com/xxx）用 resolve()/resolveAny() 跟随重定向后再解析，
 * 只对 amap.com 域名发起网络请求。
 *
 * ⚠ 坐标顺序：高德 URI 参数 position/to/dest 是「经度,纬度」(lng,lat)，
 *   而用户手输裸坐标是中国习惯的「纬度,经度」(lat,lng)。两类分开处理。
 *
 * ⚠ 裸文本坐标的收紧要则（避免「35,000」「2026,11」被当成坐标）：
 *   - 两侧数字都必须带小数点；
 *   - 解析结果必须落在中国范围（lng 73~136, lat 3~54）；
 *   - 不再做「海外/非常规也按 lat,lng 猜」的兜底。
 *
 * 本类不依赖 Android / 高德 SDK，便于独立编译与测试。网络请求在工作线程，
 * 回调也在工作线程——调用方需自行 runOnUiThread 再更新 UI。
 */
public final class AmapLink {

    private AmapLink() {
    }

    public static final class Place {
        public final double lat;
        public final double lng;
        public final String name;
        public final String address;
        public final String source;

        Place(double lat, double lng, String name, String address, String source) {
            this.lat = lat;
            this.lng = lng;
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address;
            this.source = source == null ? "" : source;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Place{%.6f,%.6f,%s,%s}", lat, lng, name, source);
        }
    }

    /** 一条完整路线：起点 + 终点 + 途经点 + 出行方式（来自高德路线规划分享链接） */
    public static final class RoutePlan {
        public final Place from;   // 起点，可为 null（表示用当前定位）
        public final Place to;     // 终点
        public final List<Place> vias = new ArrayList<Place>();
        public final int mode;     // 0=驾车 1=骑行 2=步行，与 NavSdkActivity 一致
        public final int policy;   // 驾车策略 0~3，-1 表示未指定

        RoutePlan(Place from, Place to, int mode, int policy) {
            this.from = from;
            this.to = to;
            this.mode = mode;
            this.policy = policy;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "RoutePlan{from=%s,to=%s,vias=%d,mode=%d,policy=%d}",
                    from, to, vias.size(), mode, policy);
        }
    }

    public interface Callback {
        /** place 或 error 至少一个非空；运行在工作线程 */
        void onResult(Place place, String error);
    }

    /** 路线计划回调：plan 或 error 至少一个非空；运行在工作线程 */
    public interface RouteCallback {
        void onResult(RoutePlan plan, String error);
    }

    /**
     * 通用导入回调：路线链接给 plan、单点链接/坐标给 place、失败给 error，三者只会有一个非空。
     * 短链只抓一次，避免路线/单点两条解析链重复请求与状态竞争。
     */
    public interface AnyCallback {
        void onResult(RoutePlan plan, Place place, String error);
    }

    /** 裸文本坐标：两侧都必须带小数点，避免把「35,000」「2026,11」当坐标 */
    private static final Pattern PAIR = Pattern.compile(
            "(-?\\d{1,3}\\.\\d+)[,，]\\s*(-?\\d{1,3}\\.\\d+)");

    /** 高德 URI 里带坐标的参数名（值形如 lng,lat[,name]） */
    private static final String[] PAIR_KEYS = {"position", "to", "dest", "lonlat", "destLocation"};

    /** 名称参数名，按优先级 */
    private static final String[] NAME_KEYS = {"name", "destName", "poiname", "poiName", "title"};

    /** 链接页 HTML 里的 amap 链接（marker 单点 / 路线计划） */
    private static final Pattern MARKER_LINK = Pattern.compile(
            "(?i)(?:https?://)?(?:uri|m|www)\\.amap\\.com/(?:marker|navi)/?\\?[^\\s\"'<>\\\\]{0,500}");
    private static final Pattern ROUTE_LINK = Pattern.compile(
            "(?i)(?:amapuri://route/plan/|(?:https?://)?uri\\.amap\\.com/navigation)\\?[^\\s\"'<>\\\\]{0,700}");

    /** 链接页 HTML 里的 lat/lng 结构化字段（只认显式字段，不对整段 HTML 跑坐标正则） */
    private static final Pattern PAGE_LAT = Pattern.compile(
            "(?i)[\"']?(?:lat|latitude)[\"']?\\s*[:=]\\s*(-?\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern PAGE_LNG = Pattern.compile(
            "(?i)[\"']?(?:lng|lon|longitude)[\"']?\\s*[:=]\\s*(-?\\d{1,3}(?:\\.\\d+)?)");

    /** 中国范围：本 App 只在高德中国地图上导航 */
    private static final double CN_LAT_MIN = 3.0;
    private static final double CN_LAT_MAX = 54.0;
    private static final double CN_LNG_MIN = 73.0;
    private static final double CN_LNG_MAX = 136.0;

    /** 短链抓取上限：总字符数（避免超长响应占内存；按块读，不按行读） */
    private static final int MAX_BODY_CHARS = 200000;

    // ---------------------------------------------------------------- 对外工具

    /** 坐标是否在中国范围内（App 只支持中国地图；也用于校验外部 Intent 传入的坐标） */
    public static boolean isInChina(double lat, double lng) {
        return finite(lat) && finite(lng)
                && lat >= CN_LAT_MIN && lat <= CN_LAT_MAX
                && lng >= CN_LNG_MIN && lng <= CN_LNG_MAX;
    }

    /** 输入是否像一个链接（用于「解析失败后是否回退成地名搜索」的判断） */
    public static boolean isLink(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim().toLowerCase(Locale.US);
        return s.startsWith("http://") || s.startsWith("https://") || s.contains("amapuri://");
    }

    // ---------------------------------------------------------------- 离线解析

    /** 同步解析：标准高德链接 / 裸坐标 / 文本中的坐标对。失败返回 null。 */
    public static Place parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return null;
        }

        // 1) 高德 URI 的 position/to/dest：明确是 lng,lat，不猜
        for (String key : PAIR_KEYS) {
            String value = param(s, key);
            if (value != null) {
                Place p = fromAmapPair(value, s);
                if (p != null) {
                    return p;
                }
            }
        }

        // 2) androidamap:// 的 lat / lon 独立参数
        String latStr = param(s, "lat");
        String lonStr = param(s, "lon");
        if (latStr != null && lonStr != null) {
            try {
                double la = Double.parseDouble(latStr.trim());
                double lo = Double.parseDouble(lonStr.trim());
                if (valid(la, lo)) {
                    return new Place(la, lo, firstParam(s, NAME_KEYS), "", "高德URI");
                }
            } catch (Throwable ignore) {
                // 落到下面的通用解析
            }
        }

        // 3) 文本里的裸坐标（两侧带小数、且必须落在中国范围内）
        Matcher m = PAIR.matcher(s);
        if (m.find()) {
            return fromAnyPair(m.group(1), m.group(2), s);
        }
        return null;
    }

    /** 高德 URI 参数：lng,lat[,name] */
    private static Place fromAmapPair(String value, String fullRaw) {
        return fromAmapPair(value, fullRaw, "");
    }

    /** 裸坐标 / 未知文本：只接受中国范围内的明确小数对 */
    private static Place fromAnyPair(String a, String b, String fullRaw) {
        try {
            double x = Double.parseDouble(a.trim());
            double y = Double.parseDouble(b.trim());
            double lat;
            double lng;
            if (isLng(x) && isLat(y)) {
                // 高德风格 lng,lat
                lng = x;
                lat = y;
            } else if (isLat(x) && isLng(y)) {
                // 中国习惯 lat,lng
                lat = x;
                lng = y;
            } else {
                // 不在中国范围：不再猜（避免「35,000」「2026,11」导到国外/海里）
                return null;
            }
            return new Place(lat, lng, firstParam(fullRaw, NAME_KEYS), "", "坐标");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 路线计划解析（起点/终点/途经点）

    /**
     * 解析整条路线规划分享链接，支持两种高德官方格式：
     *  1) https://uri.amap.com/navigation?from=116.4,39.9,起点&to=116.5,40.0,终点
     *       &via=116.45,39.95,途经点&mode=car&policy=1
     *  2) amapuri://route/plan/?slon=116.4&slat=39.9&sname=起点
     *       &dlon=116.5&dlat=40.0&dname=终点
     *       &vian=2&vialons=116.8|116.5&vialats=39.5|39.7&vianames=点1|点2
     * 出行方式 mode=car/ride/walk/bus，公交本 App 不支持，归为 -1。
     *
     * 没有有效终点就返回 null（否则页面会卡在「正在识别…」，见 M1）。
     */
    public static RoutePlan parseRoute(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return null;
        }

        int mode = parseMode(param(s, "mode"));
        int policy = parseInt(param(s, "policy"), -1);

        // 格式 2：amapuri://route/plan/（经纬度分参数）
        String dlon = param(s, "dlon");
        String dlat = param(s, "dlat");
        String slon = param(s, "slon");
        String slat = param(s, "slat");
        if (dlon != null && dlat != null) {
            double dLa = parseDouble(dlat);
            double dLo = parseDouble(dlon);
            Place to = validPlace(dLa, dLo)
                    ? new Place(dLa, dLo, firstParam(s, new String[]{"dname", "ename"}), "", "路线终点")
                    : null;
            Place from = null;
            if (slon != null && slat != null) {
                double sLa = parseDouble(slat);
                double sLo = parseDouble(slon);
                if (validPlace(sLa, sLo)) {
                    from = new Place(sLa, sLo,
                            firstParam(s, new String[]{"sname", "bname"}), "", "路线起点");
                }
            }
            if (!validPlace(to)) {
                return null; // 终点坏了：不要返回「只有途经点」的半成品计划
            }
            RoutePlan plan = new RoutePlan(from, to, mode, policy);
            parseViaArray(s, plan);
            return plan;
        }

        // 格式 1：uri.amap.com/navigation（from/to/via 均为 lng,lat[,name]）
        Place pairFrom = fromAmapPair(param(s, "from"), s, "路线起点");
        String toVal = param(s, "to");
        if (toVal == null) {
            toVal = param(s, "dest"); // navi 的老参数名
        }
        Place pairTo = fromAmapPair(toVal, s, "路线终点");
        if (!validPlace(pairTo)) {
            return null; // 没有终点就不是一条可用路线
        }
        RoutePlan plan = new RoutePlan(validPlace(pairFrom) ? pairFrom : null, pairTo, mode, policy);

        // via：高德官方是重复的 &via=；也兼容单个 via 里用 | ; 分隔多个点
        for (String via : params(s, "via")) {
            if (via == null) {
                continue;
            }
            for (String v : via.split("[|;]")) {
                Place p = fromAmapPair(v, "", "途经点");
                if (validPlace(p)) {
                    plan.vias.add(p);
                }
            }
        }
        return plan;
    }

    /** 解析 vialons/vialats/vianames 三个平行数组（amapuri://route/plan/ 多途经点） */
    private static void parseViaArray(String raw, RoutePlan plan) {
        String lons = param(raw, "vialons");
        String lats = param(raw, "vialats");
        if (lons == null || lats == null) {
            return;
        }
        String[] lonArr = lons.split("[|,]");
        String[] latArr = lats.split("[|,]");
        String nameStr = param(raw, "vianames");
        // 名称里可能含中英文逗号，只按 | 切分
        String[] nameArr = (nameStr == null ? "" : nameStr).split("\\|");
        int n = Math.min(lonArr.length, latArr.length);
        // 高德约定途经点数用 vian 给出，但以实际数组长度为准，最多取 10 个
        n = Math.min(n, 10);
        for (int i = 0; i < n; i++) {
            double la = parseDouble(latArr[i]);
            double lo = parseDouble(lonArr[i]);
            if (valid(la, lo)) {
                String nm = i < nameArr.length && nameArr[i].trim().length() > 0
                        ? nameArr[i].trim() : ("途经点" + (i + 1));
                plan.vias.add(new Place(la, lo, nm, "", "路线途经点"));
            }
        }
    }

    /** 解析 lng,lat[,name]，name 从值里取；fullRaw 仅用于回填缺省名称 */
    private static Place fromAmapPair(String value, String fullRaw, String defaultName) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        String[] parts = value.split("[,，]");
        if (parts.length < 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            if (!valid(lat, lng)) {
                return null;
            }
            String name = parts.length >= 3 && parts[2].trim().length() > 0
                    ? parts[2].trim()
                    : (fullRaw != null && fullRaw.length() > 0
                        ? firstParam(fullRaw, NAME_KEYS) : defaultName);
            return new Place(lat, lng, name.length() > 0 ? name : defaultName, "", defaultName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean validPlace(Place p) {
        return p != null && valid(p.lat, p.lng);
    }

    private static boolean validPlace(double lat, double lng) {
        return valid(lat, lng);
    }

    private static int parseMode(String mode) {
        if (mode == null) {
            return 0;
        }
        String m = mode.trim().toLowerCase(Locale.US);
        if ("ride".equals(m)) {
            return 1;
        }
        if ("walk".equals(m)) {
            return 2;
        }
        if ("bus".equals(m)) {
            return -1;
        }
        return 0;
    }

    private static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static double parseDouble(String v) {
        try {
            return v == null ? Double.NaN : Double.parseDouble(v.trim());
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- 短链解析（联网，只抓一次）

    /** 单点解析：先离线；是短链则联网跟随一次重定向再解析（错误回给回调） */
    public static void resolve(final String raw, final Callback cb) {
        resolveAny(raw, new AnyCallback() {
            public void onResult(RoutePlan plan, Place place, String error) {
                if (cb == null) {
                    return;
                }
                if (place != null) {
                    cb.onResult(place, null);
                } else if (plan != null && plan.to != null) {
                    cb.onResult(plan.to, null);
                } else {
                    cb.onResult(null, error);
                }
            }
        });
    }

    /** 路线计划解析：先离线；是短链则联网跟随一次重定向再解析（错误回给回调） */
    public static void resolveRoute(final String raw, final RouteCallback cb) {
        resolveAny(raw, new AnyCallback() {
            public void onResult(RoutePlan plan, Place place, String error) {
                if (cb == null) {
                    return;
                }
                if (plan != null) {
                    cb.onResult(plan, null);
                } else if (place != null) {
                    cb.onResult(null, "这不是路线规划链接，只是一个目的地");
                } else {
                    cb.onResult(null, error);
                }
            }
        });
    }

    /**
     * 统一导入：一次解析出「路线计划」或「单个目的地」，短链只联网抓一次。
     * 回调在工作线程，调用方需切回主线程更新 UI。
     */
    public static void resolveAny(final String raw, final AnyCallback cb) {
        if (cb == null) {
            return;
        }
        if (raw == null || raw.trim().length() == 0) {
            cb.onResult(null, null, "没有内容可解析");
            return;
        }
        final String s = raw.trim();

        // 短链（没有 ? 参数的 http 链接）先不做离线解析：URL 里的数字不是坐标
        if (!isBareShortLink(s)) {
            RoutePlan plan = parseRoute(s);
            if (plan != null) {
                cb.onResult(plan, null, null);
                return;
            }
            Place place = parse(s);
            if (place != null) {
                cb.onResult(null, place, null);
                return;
            }
        }

        if (!isHttpUrl(s)) {
            cb.onResult(null, null, "没识别出坐标：请粘贴高德分享链接或「纬度,经度」坐标");
            return;
        }
        if (!isAmapHttpUrl(s)) {
            cb.onResult(null, null, "只支持高德（amap.com）链接：请用高德 App「分享 → 复制链接」");
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                Fetched f = fetch(s);
                RoutePlan plan = parseRoute(f.finalUrl);
                if (plan == null) {
                    plan = parseRoute(findRouteLink(f.body));
                }
                if (plan != null) {
                    cb.onResult(plan, null, null);
                    return;
                }
                Place place = parse(f.finalUrl);
                if (place == null) {
                    place = placeFromBody(f.body);
                }
                if (place != null) {
                    cb.onResult(null, place, null);
                    return;
                }
                String error = f.error != null
                        ? "解析链接失败：" + f.error + "（可改用地图选点）"
                        : "链接里没找到目的地（HTTP " + f.code
                            + "）：请用高德「分享 → 复制链接」的标准链接，或用地图选点";
                cb.onResult(null, null, error);
            }
        }, "amap-link-resolve").start();
    }

    // ---------------------------------------------------------------- 网络

    private static final class Fetched {
        String finalUrl = "";
        String body = "";
        int code = -1;
        String error;
    }

    /** 跟随重定向抓一次页面；按块读并限量，避免超长响应/单行导致内存暴涨 */
    private static Fetched fetch(String url) {
        Fetched f = new Fetched();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) gaodenav");
            f.code = conn.getResponseCode();
            f.finalUrl = conn.getURL() == null ? url : conn.getURL().toString();
            InputStream in = conn.getInputStream();
            if (in != null) {
                StringBuilder sb = new StringBuilder();
                InputStreamReader r = new InputStreamReader(in, "UTF-8");
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && sb.length() < MAX_BODY_CHARS) {
                    sb.append(buf, 0, Math.min(n, MAX_BODY_CHARS - sb.length()));
                }
                r.close();
                f.body = sb.toString();
            }
        } catch (Throwable t) {
            f.error = String.valueOf(t);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        }
        return f;
    }

    /** HTML 里嵌的路线链接（如短链落地页里的 amapuri://route/plan/...） */
    private static String findRouteLink(String body) {
        if (body == null || body.length() == 0) {
            return null;
        }
        Matcher m = ROUTE_LINK.matcher(body);
        return m.find() ? m.group() : null;
    }

    /** HTML 里嵌的 marker 链接，或显式的 lat/lng 字段（不再对整段 HTML 跑坐标正则） */
    private static Place placeFromBody(String body) {
        if (body == null || body.length() == 0) {
            return null;
        }
        Matcher link = MARKER_LINK.matcher(body);
        if (link.find()) {
            Place p = parse(link.group());
            if (p != null) {
                return p;
            }
        }
        Matcher la = PAGE_LAT.matcher(body);
        Matcher lo = PAGE_LNG.matcher(body);
        if (la.find() && lo.find()) {
            double lat = parseDouble(la.group(1));
            double lng = parseDouble(lo.group(1));
            if (isInChina(lat, lng)) {
                return new Place(lat, lng, firstParam(body, NAME_KEYS), "", "链接页面");
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 小工具

    private static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase(Locale.US);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /** 短链：http(s) 链接且没有 query（有 query 的完整链接可以离线解析） */
    private static boolean isBareShortLink(String s) {
        return isHttpUrl(s) && s.trim().indexOf('?') < 0;
    }

    /** 只允许 amap.com 域名（避免被外部 Intent 指使去抓任意/内网 URL） */
    private static boolean isAmapHttpUrl(String s) {
        try {
            URL u = new URL(s.trim());
            String host = u.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.US);
            return host.equals("amap.com") || host.endsWith(".amap.com");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取 URL query 里某个参数（大小写不敏感，做 URL 解码） */
    private static String param(String raw, String key) {
        List<String> all = params(raw, key);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 取 URL query 里某个参数的全部取值（高德多途经点是重复的 &via=） */
    private static List<String> params(String raw, String key) {
        List<String> out = new ArrayList<String>();
        if (raw == null || key == null) {
            return out;
        }
        int q = raw.indexOf('?');
        String query = q >= 0 ? raw.substring(q + 1) : raw;
        String[] pairs = query.split("[&#]");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = pair.substring(0, eq).trim();
            if (!k.equalsIgnoreCase(key)) {
                continue;
            }
            String v = pair.substring(eq + 1);
            try {
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Throwable ignore) {
                // 保留原值
            }
            out.add(v);
        }
        return out;
    }

    private static String firstParam(String raw, String[] keys) {
        for (String key : keys) {
            String v = param(raw, key);
            if (v != null && v.trim().length() > 0) {
                return v.trim();
            }
        }
        return "";
    }

    private static boolean valid(double lat, double lng) {
        return finite(lat) && finite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(lat == 0 && lng == 0);
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static boolean isLat(double v) {
        return finite(v) && v >= CN_LAT_MIN && v <= CN_LAT_MAX;
    }

    private static boolean isLng(double v) {
        return finite(v) && v >= CN_LNG_MIN && v <= CN_LNG_MAX;
    }
}
