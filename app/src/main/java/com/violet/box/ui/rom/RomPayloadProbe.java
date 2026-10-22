package com.violet.box.ui.rom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 「云端提取分区镜像」的预检器。
 *
 * <p>云端提取的前提有三个，缺一不可，而这三样在真正开下之前用几个 Range 请求就能查出来：
 * <ol>
 *     <li><b>服务端支持 Range 随机读</b> —— 提取是按 operation 跳着读数据块，
 *         不支持 Range 就只能整包下载；</li>
 *     <li><b>包内要有 payload.bin</b> —— 服务端给的不一定都是 OTA 全量包，实测存在
 *         只有 {@code my_manifest.*.new.dat.br} + {@code transfer.list} 的组件增量包，
 *         那种包根本没有 payload.bin；</li>
 *     <li><b>不能是加密包</b> —— {@code .ozip} 是 OPPO 加密格式，拿到也解不开。</li>
 * </ol>
 *
 * <p>预检只花 2~3 个 1 字节 / 几十 KB 的请求，失败时能给出人话原因，
 * 而不是等到 PayloadCore 抛一句「无法定位 payload.bin」。
 */
public final class RomPayloadProbe {

    public static final class Result {
        /** 是否可以走云端提取。 */
        public final boolean ok;
        /** 不可提取时的原因（人话，直接给用户看）。 */
        public final String reason;
        /** 包内文件名（最多 20 个，用于给用户看包里到底有什么）。 */
        public final List<String> entries;
        public final boolean rangeSupported;
        public final boolean hasPayload;

        Result(boolean ok, String reason, List<String> entries,
               boolean rangeSupported, boolean hasPayload) {
            this.ok = ok;
            this.reason = reason;
            this.entries = entries;
            this.rangeSupported = rangeSupported;
            this.hasPayload = hasPayload;
        }

        static Result bad(String reason) {
            return new Result(false, reason, new ArrayList<>(), false, false);
        }
    }

    /** CDN 会拒掉 okhttp 默认 UA，这里用实测可通过的 Android 原生 UA。 */
    public static final String SAFE_USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 13; SM-G991B Build/TP1A.220624.014)";

    private static final String SAFE_UA = SAFE_USER_AGENT;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private RomPayloadProbe() {
    }

    public static Result probe(String url) {
        if (url == null || url.trim().isEmpty()) {
            return Result.bad("没有可用直链");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int cut = lower.indexOf('?');
        String pathOnly = cut > 0 ? lower.substring(0, cut) : lower;
        if (pathOnly.endsWith(".ozip")) {
            return Result.bad("这是 OPPO 加密包（.ozip），无法直接解析，请改用「下载整包」");
        }

        try {
            // 1) Range 探测：1 字节即可，顺带拿到总长度
            long total;
            boolean rangeSupported;
            try (Response probe = HTTP.newCall(new Request.Builder()
                    .url(url)
                    .header("User-Agent", SAFE_UA)
                    .header("Range", "bytes=0-0")
                    .build()).execute()) {
                if (!probe.isSuccessful()) {
                    return Result.bad("服务器返回 HTTP " + probe.code()
                            + "（直链可能已过期或被限流）");
                }
                String cr = probe.header("Content-Range");
                rangeSupported = probe.code() == 206 && cr != null && cr.contains("/");
                if (!rangeSupported) {
                    return new Result(false,
                            "该链接不支持断点 / 随机读取（Range），无法云端提取，请改用「下载整包」",
                            new ArrayList<>(), false, false);
                }
                total = parseTotal(cr);
            }
            if (total <= 0) {
                return Result.bad("无法获取文件大小");
            }

            // 2) 读 ZIP 尾部找 EOCD
            int tailLen = (int) Math.min(total, 66000);
            byte[] tail = readRange(url, total - tailLen, tailLen);
            int eocd = lastIndexOf(tail, new byte[]{0x50, 0x4B, 0x05, 0x06});
            if (eocd < 0) {
                // 不是 zip：可能本身就是裸 payload.bin
                byte[] head = readRange(url, 0, 4);
                if ("CrAU".equals(new String(head, StandardCharsets.UTF_8))) {
                    return new Result(true, "", new ArrayList<>(), true, true);
                }
                return new Result(false, "这个链接不是 ZIP 包也不是 payload.bin，无法云端提取",
                        new ArrayList<>(), true, false);
            }

            ByteBuffer eb = ByteBuffer.wrap(tail, eocd, Math.min(22, tail.length - eocd))
                    .order(ByteOrder.LITTLE_ENDIAN);
            long cdSize = uint32(eb, 12);
            long cdOffset = uint32(eb, 16);
            if (cdSize <= 0 || cdOffset < 0 || cdOffset + cdSize > total) {
                return Result.bad("ZIP 目录结构异常，无法解析");
            }

            // 3) 读 central directory，遍历文件名
            byte[] cd = readRange(url, cdOffset, (int) cdSize);
            List<String> entries = new ArrayList<>();
            boolean hasPayload = false;
            int pos = 0;
            while (pos + 46 <= cd.length) {
                if (cd[pos] != 0x50 || cd[pos + 1] != 0x4B
                        || cd[pos + 2] != 0x01 || cd[pos + 3] != 0x02) {
                    pos++;
                    continue;
                }
                ByteBuffer hb = ByteBuffer.wrap(cd, pos, cd.length - pos)
                        .order(ByteOrder.LITTLE_ENDIAN);
                int nameLen = hb.getChar(28) & 0xFFFF;
                int extraLen = hb.getChar(30) & 0xFFFF;
                int commentLen = hb.getChar(32) & 0xFFFF;
                if (pos + 46 + nameLen > cd.length) break;
                String name = new String(cd, pos + 46, nameLen, StandardCharsets.UTF_8);
                if (entries.size() < 20) entries.add(name);
                if (name.endsWith("payload.bin")) hasPayload = true;
                pos += 46 + nameLen + extraLen + commentLen;
            }

            if (!hasPayload) {
                StringBuilder sb = new StringBuilder();
                sb.append("这个包里没有 payload.bin，无法云端提取分区镜像。\n")
                        .append("服务端给的可能是组件增量包，包内实际是：");
                for (int i = 0; i < Math.min(entries.size(), 8); i++) {
                    sb.append('\n').append("· ").append(entries.get(i));
                }
                sb.append("\n\n建议：换一个版本再试，或改用「下载整包」。");
                return new Result(false, sb.toString(), entries, true, false);
            }
            return new Result(true, "", entries, true, true);
        } catch (IOException e) {
            String m = e.getMessage();
            return Result.bad("预检失败：" + (m == null ? e.getClass().getSimpleName() : m));
        }
    }

    // ------------------------------------------------------------------ 内部

    private static byte[] readRange(String url, long start, int length) throws IOException {
        long end = start + length - 1;
        try (Response r = HTTP.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", SAFE_UA)
                .header("Range", "bytes=" + start + "-" + end)
                .build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code() + " 读取 bytes=" + start);
            }
            if (r.body() == null) throw new IOException("空响应");
            byte[] out = r.body().bytes();
            if (out.length != length) {
                // 服务端可能少给，按实际返回处理（后续解析会再校验）
                return out;
            }
            return out;
        }
    }

    private static long parseTotal(String contentRange) {
        try {
            int slash = contentRange.lastIndexOf('/');
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private static long uint32(ByteBuffer b, int offset) {
        return b.getInt(offset) & 0xFFFFFFFFL;
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            boolean hit = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) return i;
        }
        return -1;
    }
}
