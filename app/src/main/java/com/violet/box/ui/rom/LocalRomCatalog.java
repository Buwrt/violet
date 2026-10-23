package com.violet.box.ui.rom;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置 ROM 清单：只读 assets/rom_catalog.json。
 *
 * <p>整个查询过程不发起任何网络请求 —— 品牌 / 区域 / 版本类型 / ROM 类型 / 系列 / 机型
 * 六级筛选全在内存里算。只有用户点下载时才会去连厂商 CDN。
 */
public final class LocalRomCatalog {

    /** 外链条目在「区域 / 版本类型」上的通配值：任何筛选条件都匹配。 */
    public static final String WILDCARD = "见页面";

    /** 清单里声明的全部系统版本取值，供 UI 建下拉框。 */
    public static List<String> systems(Context ctx) {
        List<String> out = new ArrayList<>(load(ctx).systems);
        if (out.isEmpty()) out.add(WILDCARD);
        return out;
    }

    /** 一条版本记录。 */
    public static final class Version {
        public final String version;
        /** 区域代码：CN / IN / EU / GLO / NA / RU / ID / TW / JP / TR。 */
        public final String regionCode;
        /** 区域中文名：国行版 / 印度版 / 欧洲版 ... */
        public final String region;
        /** 版本类型：全量包 / 售后包。 */
        public final String versionType;
        /** ROM 类型：卡刷包 / 线刷包 / 分区镜像。 */
        public final String romType;
        public final String android;
        /** 为空表示无公开直链。 */
        public final String url;
        public final long size;
        public final String date;
        public final String note;
        public final String source;
        /** 含 payload.bin → 可不下整包直接抠 boot / init_boot。 */
        public final boolean payload;
        /** true = 这是第三方站的机型下载页，要用浏览器打开，不是文件直链。 */
        public final boolean external;
        /** 系统版本名：MIUI 14 / 澎湃OS 3 / ColorOS 16 / OxygenOS 15 / realme UI 5 … */
        public final String system;
        /** 同固件的其它分块（一加归档的 firmware / logical 分卷 / hash）。 */
        public final Map<String, String> extra;

        Version(String version, String regionCode, String region, String versionType,
                String romType, String system, String android, String url, long size,
                String date, String note, String source, boolean payload, boolean external,
                Map<String, String> extra) {
            this.version = version;
            this.regionCode = regionCode;
            this.region = region;
            this.versionType = versionType;
            this.romType = romType;
            this.android = android;
            this.url = url;
            this.size = size;
            this.date = date;
            this.note = note;
            this.source = source;
            this.payload = payload;
            this.external = external;
            this.system = system;
            this.extra = extra == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        }

        public boolean hasLink() {
            return url != null && !url.trim().isEmpty();
        }

        /**
         * 版本号主体：{@code 16.0.1.300(CN01)} → {@code 16.0.1.300}。
         * 没有括号后缀时原样返回。
         */
        public String versionBase() {
            if (version == null || version.isEmpty()) return "";
            int i = version.lastIndexOf('(');
            if (i > 0 && version.endsWith(")")) return version.substring(0, i).trim();
            return version.trim();
        }

        /**
         * 版本后缀：{@code 16.0.1.300(CN01)} → {@code CN01}；没有后缀返回空串。
         *
         * <p>后缀其实是区域代码（CN01 国行 / EX01 海外印度），与「区域」一级同源，
         * 单独拆一级是为了让版本号本身更清爽，也便于按后缀精确筛选。
         */
        public String versionSuffix() {
            if (version == null || version.isEmpty()) return "";
            int i = version.lastIndexOf('(');
            if (i >= 0 && version.endsWith(")")) {
                return version.substring(i + 1, version.length() - 1).trim();
            }
            return "";
        }

        /**
         * 能不能不下整包直接拿到 boot / init_boot。
         *
         * <p>两条路：包里有 payload.bin（用 PayloadCore 按块 Range 拉取），
         * 或者本身就是分区镜像归档（解压即是成品镜像）。
         */
        public boolean canExtractBoot() {
            if (external) return false;   // 外链页面不能直接抠镜像
            return payload || (romType != null && romType.contains("分区镜像"));
        }

        /** 给列表显示的一行文字。 */
        public String label() {
            StringBuilder sb = new StringBuilder();
            sb.append(version);
            if (romType != null && !romType.isEmpty()) sb.append(" · ").append(romType);
            if (versionType != null && !versionType.isEmpty()) sb.append(" · ").append(versionType);
            if (system != null && !system.isEmpty() && !WILDCARD.equals(system)) {
                sb.append(" · ").append(system);
            }
            if (region != null && !region.isEmpty()) sb.append(" · ").append(region);
            if (!hasLink()) sb.append(" · 暂无直链");
            else if (external) sb.append(" · 浏览器打开下载页");
            return sb.toString();
        }
    }

    /** 一个机型。 */
    public static final class Device {
        public final String brand;
        public final String name;
        public final String key;
        /** 开发代号；与 key 相同或为空时界面不重复显示。 */
        public final String code;
        public final String note;
        /** 系列：数字系列 / Ace 系列 / K 系列 / Note 系列 ... */
        public final String series;
        public final List<Version> versions;

        Device(String brand, String name, String key, String code, String note, String series,
               List<Version> versions) {
            this.brand = brand;
            this.name = name;
            this.key = key;
            this.code = code;
            this.note = note;
            this.series = series;
            this.versions = versions;
        }

        @Override
        public String toString() {
            return name + "（" + key + "）";
        }
    }

    private static volatile Catalog cached;

    private LocalRomCatalog() {
    }

    private static final class Catalog {
        final List<String> brands;
        final List<String> regions;
        final List<String> versionTypes;
        final List<String> romTypes;
        /** 系统版本取值：MIUI 14 / 澎湃OS 3 / ColorOS 16 / OxygenOS 15 … */
        final List<String> systems;
        final Map<String, List<Device>> byBrand;

        Catalog(List<String> brands, List<String> regions, List<String> versionTypes,
                List<String> romTypes, List<String> systems,
                Map<String, List<Device>> byBrand) {
            this.brands = brands;
            this.regions = regions;
            this.versionTypes = versionTypes;
            this.romTypes = romTypes;
            this.systems = systems;
            this.byBrand = byBrand;
        }
    }

    private static synchronized Catalog load(Context ctx) {
        if (cached != null) return cached;
        cached = parse(ctx);
        return cached;
    }

    public static List<String> brands(Context ctx) {
        return new ArrayList<>(load(ctx).brands);
    }

    public static List<String> regions(Context ctx) {
        return new ArrayList<>(load(ctx).regions);
    }

    public static List<String> versionTypes(Context ctx) {
        return new ArrayList<>(load(ctx).versionTypes);
    }

    public static List<String> romTypes(Context ctx) {
        return new ArrayList<>(load(ctx).romTypes);
    }

    /** 某品牌下的机型。 */
    public static List<Device> devices(Context ctx, String brand) {
        List<Device> list = load(ctx).byBrand.get(brand);
        return list == null ? new ArrayList<Device>() : new ArrayList<>(list);
    }

    /** 全部机型（不限品牌）。 */
    public static List<Device> allDevices(Context ctx) {
        List<Device> out = new ArrayList<>();
        for (List<Device> l : load(ctx).byBrand.values()) out.addAll(l);
        return out;
    }

    public static int deviceCount(Context ctx) {
        int n = 0;
        for (List<Device> l : load(ctx).byBrand.values()) n += l.size();
        return n;
    }

    public static int versionCount(Context ctx) {
        int n = 0;
        for (Device d : allDevices(ctx)) n += d.versions.size();
        return n;
    }

    /** 某品牌下出现过的系列。 */
    public static List<String> seriesOf(Context ctx, List<Device> pool) {
        List<String> out = new ArrayList<>();
        for (Device d : pool) {
            if (d.series != null && !d.series.isEmpty() && !out.contains(d.series)) {
                out.add(d.series);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 解析

    private static Catalog parse(Context ctx) {
        List<String> brands = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        List<String> versionTypes = new ArrayList<>();
        List<String> romTypes = new ArrayList<>();
        List<String> systems = new ArrayList<>();
        Map<String, List<Device>> byBrand = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(readAsset(ctx, "rom_catalog.json"));
            brands = strArray(root.optJSONArray("brands"));
            regions = strArray(root.optJSONArray("regions"));
            versionTypes = strArray(root.optJSONArray("versionTypes"));
            romTypes = strArray(root.optJSONArray("romTypes"));
            systems = strArray(root.optJSONArray("systems"));
            JSONArray arr = root.optJSONArray("devices");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String brand = o.optString("brand", "");
                    Device d = deviceOf(brand, o);
                    List<Device> list = byBrand.get(brand);
                    if (list == null) {
                        list = new ArrayList<>();
                        byBrand.put(brand, list);
                    }
                    list.add(d);
                }
            }
        } catch (Throwable ignored) {
            // 清单损坏时返回空结构，界面会提示"没有数据"
        }
        return new Catalog(brands, regions, versionTypes, romTypes, systems, byBrand);
    }

    private static Device deviceOf(String brand, JSONObject o) {
        String key = o.optString("key", "");
        List<Version> vers = new ArrayList<>();
        JSONArray vs = o.optJSONArray("versions");
        if (vs != null) {
            for (int i = 0; i < vs.length(); i++) {
                JSONObject v = vs.optJSONObject(i);
                if (v == null) continue;
                vers.add(versionOf(v));
            }
        }
        return new Device(brand, o.optString("name", key), key,
                o.optString("code", ""), o.optString("note", ""),
                o.optString("series", "其他"), vers);
    }

    private static Version versionOf(JSONObject o) {
        Map<String, String> extra = new LinkedHashMap<>();
        JSONObject ex = o.optJSONObject("extra");
        if (ex != null) {
            java.util.Iterator<String> it = ex.keys();
            while (it.hasNext()) {
                String k = it.next();
                String val = ex.optString(k, "");
                if (val != null && !val.isEmpty()) extra.put(k, val);
            }
        }
        return new Version(
                o.optString("version", ""),
                o.optString("regionCode", ""),
                o.optString("region", ""),
                o.optString("versionType", ""),
                o.optString("romType", ""),
                o.optString("system", WILDCARD),
                o.optString("android", ""),
                o.optString("url", ""),
                o.optLong("size", 0L),
                o.optString("date", ""),
                o.optString("note", ""),
                o.optString("source", ""),
                o.optBoolean("payload", false),
                o.optBoolean("external", false),
                extra);
    }

    private static List<String> strArray(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String readAsset(Context ctx, String name) throws java.io.IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }
}
