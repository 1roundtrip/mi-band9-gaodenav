#!/usr/bin/env bash
# AmapLink 纯逻辑自测：AmapLink 不依赖 Android / 高德 SDK，本脚本也不依赖，
# 所以在 CI（ubuntu）和本机（Git bash）都能直接跑。
# ponytail: 链接解析有分支，留一个能跑的 check；断言失败直接非零退出。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android-app/src"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -d "$OUT" "$SRC/cn/dsh/gaodenav/AmapLink.java"

cat > "$OUT/SelfTest.java" << 'JAVA'
import cn.dsh.gaodenav.AmapLink;

public class SelfTest {
    static int pass = 0, fail = 0;
    static void chk(String label, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + label);
        if (ok) pass++; else fail++;
    }
    static boolean near(double a, double b) { return Math.abs(a - b) < 0.0001; }

    public static void main(String[] args) {
        AmapLink.RoutePlan p1 = AmapLink.parseRoute(
            "https://uri.amap.com/navigation?from=116.4,39.9,起点&to=116.5,40.0,终点&via=116.45,39.95,途经点&mode=ride&policy=1");
        chk("navigation 解析非空", p1 != null);
        if (p1 != null) {
            chk("起点 116.4,39.9", p1.from != null && near(p1.from.lng,116.4) && near(p1.from.lat,39.9));
            chk("终点 116.5,40.0", p1.to != null && near(p1.to.lng,116.5) && near(p1.to.lat,40.0));
            chk("1 个途经点", p1.vias.size() == 1 && near(p1.vias.get(0).lng,116.45));
            chk("mode=ride -> 1", p1.mode == 1);
            chk("policy=1", p1.policy == 1);
        }

        AmapLink.RoutePlan p2 = AmapLink.parseRoute(
            "amapuri://route/plan/?slon=116.4&slat=39.9&sname=A&dlon=116.5&dlat=40.0&dname=B&vian=2&vialons=116.8|116.5&vialats=39.5|39.7&vianames=P1|P2");
        chk("route/plan 解析非空", p2 != null);
        if (p2 != null) {
            chk("终点 dlon/dlat", p2.to != null && near(p2.to.lng,116.5) && near(p2.to.lat,40.0));
            chk("起点 sname=A", p2.from != null && "A".equals(p2.from.name));
            chk("2 个途经点", p2.vias.size() == 2);
            if (p2.vias.size() == 2) {
                chk("途经点1 116.8,39.5", near(p2.vias.get(0).lng,116.8) && near(p2.vias.get(0).lat,39.5));
                chk("途经点2 名称 P2", "P2".equals(p2.vias.get(1).name));
            }
        }

        AmapLink.Place old = AmapLink.parse("https://uri.amap.com/marker?position=116.3975,39.9087&name=天安门");
        chk("旧单点解析仍可用", old != null && near(old.lng,116.3975) && "天安门".equals(old.name));

        AmapLink.RoutePlan p3 = AmapLink.parseRoute("https://uri.amap.com/navigation?to=116.5,40.0,终点&via=116.45,39.95,途经点");
        chk("缺起点仍解析出终点+途经点", p3 != null && p3.to != null && p3.from == null && p3.vias.size() == 1);

        // ---- H1 回归：裸文本不再把「数字,数字」当坐标（千分位/日期/序号）----
        chk("35,000 不是坐标", AmapLink.parse("订单金额 35,000 元") == null);
        chk("2026,11 不是坐标", AmapLink.parse("2026,11 月") == null);
        chk("2,3 不是坐标", AmapLink.parse("共 2,3 人") == null);
        chk("HTML 里的 36,120 不是坐标", AmapLink.parse("<div>price 36,120</div>") == null);

        // ---- 裸坐标仍可用（两侧带小数 + 中国范围）----
        AmapLink.Place b1 = AmapLink.parse("39.9087, 116.3975");
        chk("裸坐标 lat,lng", b1 != null && near(b1.lat, 39.9087) && near(b1.lng, 116.3975));
        AmapLink.Place b2 = AmapLink.parse("116.3975, 39.9087");
        chk("裸坐标 lng,lat", b2 != null && near(b2.lat, 39.9087) && near(b2.lng, 116.3975));
        chk("海外坐标不再兜底", AmapLink.parse("48.8566, 2.3522") == null);

        // ---- 短链 URL 里的数字不应被离线解析成坐标 ----
        chk("短链不误判坐标", AmapLink.parse("https://s.amap.com/Abc123") == null);

        // ---- 中国范围校验（供导出 Intent 使用）----
        chk("isInChina 北京", AmapLink.isInChina(39.9087, 116.3975));
        chk("isInChina 拒绝巴黎", !AmapLink.isInChina(48.8566, 2.3522));
        chk("isInChina 拒绝 NaN/Inf", !AmapLink.isInChina(Double.NaN, 116.4) && !AmapLink.isInChina(39.9, Double.POSITIVE_INFINITY));

        // ---- M1 回归：终点坏了不要返回「只有途经点」的半成品 ----
        chk("dlat 坏的路线返回 null",
            AmapLink.parseRoute("amapuri://route/plan/?dlon=abc&dlat=def&vialons=116.8&vialats=39.5") == null);
        chk("无终点的 navigation 返回 null",
            AmapLink.parseRoute("https://uri.amap.com/navigation?from=116.4,39.9,起点&mode=car") == null);

        // ---- L2 回归：重复的 &via= 全部收集 ----
        AmapLink.RoutePlan p4 = AmapLink.parseRoute(
            "https://uri.amap.com/navigation?to=116.5,40.0,终点&via=116.41,39.91,A&via=116.42,39.92,B");
        chk("重复 via 收集 2 个", p4 != null && p4.vias.size() == 2);

        // ---- resolveAny 的离线分支（不联网）----
        final Object[] got = new Object[3];
        AmapLink.resolveAny("116.3975,39.9087", new AmapLink.AnyCallback() {
            public void onResult(AmapLink.RoutePlan plan, AmapLink.Place place, String error) {
                got[0] = plan; got[1] = place; got[2] = error;
            }
        });
        chk("resolveAny 裸坐标走 place", got[1] != null && got[0] == null && got[2] == null);

        final String[] err = new String[1];
        AmapLink.resolveAny("https://example.com/foo", new AmapLink.AnyCallback() {
            public void onResult(AmapLink.RoutePlan plan, AmapLink.Place place, String error) {
                err[0] = error;
            }
        });
        chk("非 amap 域名被拒绝", err[0] != null && err[0].contains("amap.com"));

        System.out.println("\nPASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
JAVA

javac -encoding UTF-8 -classpath "$OUT" -d "$OUT" "$OUT/SelfTest.java"
java -cp "$OUT" SelfTest
