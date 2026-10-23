package com.violet.box.ui.rom;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.violet.box.core.util.VioletPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ROM 下载管理器（应用级单例）。
 *
 * <p>为什么要自己写而不是交给系统 DownloadManager：系统那个 App 完全看不到结果，
 * 想暂停 / 继续 / 取消也无从下手。这里做的事：
 * <ul>
 *     <li>任务列表常驻内存，页面关掉、甚至 Activity 重建都还在；</li>
 *     <li>暂停保留 .part，继续时带 {@code Range} 头续传，ROM 动辄几个 G 也不怕断；</li>
 *     <li>取消会删掉 .part，不留垃圾；</li>
 *     <li>进度同时进通知栏和 UI 回调。</li>
 * </ul>
 */
public final class RomDownloadManager {

    private static final RomDownloadManager INSTANCE = new RomDownloadManager();

    public static RomDownloadManager get() {
        return INSTANCE;
    }

    public interface Listener {
        void onChanged();
    }

    private static final String CH_DOWNLOAD = "violet_rom_download";
    private static final int NOTIFY_ID = 0x524F;      // "RO"

    /** 任务表：id → 任务。用 ConcurrentHashMap，UI 线程和下载线程都会读。 */
    private final ConcurrentHashMap<String, RomDownloadTask> tasks = new ConcurrentHashMap<>();
    /** 保持插入顺序，列表按创建时间倒序展示。 */
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());

    /** 下载线程池：退出页面也继续跑，所以是 static 语义的常驻池。 */
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * UA 候选，按顺序试。
     *
     * <p>OPPO 的 OTA CDN（gauss-componentotacostmanual-cn.allawnfs.com）会 403 掉
     * {@code okhttp/x.y.z} 默认 UA，也拒浏览器 / SmartTool 风格；能通过的是不带 UA、
     * 空 UA、Dalvik（Android 原生）、Python-urllib。
     *
     * <p>但小米那边的偏好未必一样——所以不再赌某一个"万能 UA"，改成列表轮换：
     * 哪个通就记下来，下次优先用它（见 {@link #preferredUa}）。
     */
    private static final String[] UA_CANDIDATES = {
            // Android 原生（小米设备），对小米 CDN 最自然
            "Dalvik/2.1.0 (Linux; U; Android 14; M2102K1C Build/UKQ1.230804.001)",
            // Android 原生（通用），OPPO gauss CDN 也要这个风格
            "Dalvik/2.1.0 (Linux; U; Android 13; SM-G991B Build/TP1A.220624.014)",
            // 浏览器
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; M2102K1C) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            // OkHttp 默认
            "okhttp/4.12.0",
            // 空 UA（有些 CDN 反而不拦"没有 UA"的朴素请求）
            "",
    };

    /** Referer 候选，同样按顺序试。 */
    private static final String[] REFERER_CANDIDATES = {
            "https://www.miui.com/",
            "https://xiaomirom.com/",
            "https://hyperos.mi.com/",
            "",
    };

    /**
     * 小米官方镜像：路径完全一致，只换 host —— 换 host 就等于换出口 IP。
     * 这正是"一个 IP 访问不了就换下一个"在客户端能做的事（公网 IP 是运营商的，
     * 客户端改不了，但请求打到哪台服务器可以选）。
     */
    private static final String[] MIUI_HOSTS = {
            "bigota.d.miui.com",
            "cdnorg.d.miui.com",
            "bn.d.miui.com",
            "hugeota.d.miui.com",
    };

    /** 上次成功的 UA / Referer，下次优先试，避免每次都从头撞一遍。 */
    private static volatile String preferredUa = null;
    private static volatile String preferredReferer = null;

    /** 兼容旧引用。 */
    private static final String SAFE_UA = UA_CANDIDATES[0];

    /** 把候选按顺序排好，优先放上次成功的那个。 */
    private static String[] ordered(String[] base, String preferred) {
        if (preferred == null) return base;
        for (String b : base) {
            if (b.equals(preferred)) {
                List<String> out = new ArrayList<>();
                out.add(preferred);
                for (String b2 : base) {
                    if (!b2.equals(preferred)) out.add(b2);
                }
                return out.toArray(new String[0]);
            }
        }
        return base;
    }

    /** 若 url 命中小米官方域，生成换 host 不改路径的镜像列表。 */
    private static List<String> mirrorUrls(String url) {
        List<String> out = new ArrayList<>();
        if (url == null) return out;
        String host = null;
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Throwable ignored) {
        }
        if (host == null) {
            out.add(url);
            return out;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        boolean miui = lower.endsWith("miui.com");
        if (!miui) {
            out.add(url);
            return out;
        }
        // 先保留原 host，再依次换其它官方镜像
        out.add(url);
        for (String h : MIUI_HOSTS) {
            if (h.equals(lower)) continue;
            out.add(url.replace(lower, h));
        }
        return out;
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private Context appContext;
    private final List<Listener> listeners = new ArrayList<>();

    private RomDownloadManager() {
    }

    // ------------------------------------------------------------ 生命周期

    /** 在 Application 或首个使用方里调一次，拿到 context 用于通知与落盘。 */
    public void init(Context context) {
        if (context == null) return;
        this.appContext = context.getApplicationContext();
        ensureChannel();
    }

    public void addListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyChanged() {
        main.post(() -> {
            synchronized (listeners) {
                for (Listener l : listeners) {
                    try {
                        l.onChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 列表数据：最新的排前面。 */
    public List<RomDownloadTask> snapshot() {
        List<RomDownloadTask> out = new ArrayList<>();
        synchronized (order) {
            for (int i = order.size() - 1; i >= 0; i--) {
                RomDownloadTask t = tasks.get(order.get(i));
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    public RomDownloadTask get(String id) {
        return id == null ? null : tasks.get(id);
    }

    public int activeCount() {
        int n = 0;
        for (RomDownloadTask t : tasks.values()) {
            if (t.state == RomDownloadTask.State.RUNNING || t.state == RomDownloadTask.State.QUEUED) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ 任务操作

    public RomDownloadTask enqueue(Context context, String url, Map<String, String> headers,
                                   String fileName) {
        if (appContext == null) init(context);
        File dir = VioletPaths.resolveDir(appContext != null ? appContext : context, "ROM");
        File finalFile = new File(dir, fileName);
        File part = new File(dir, fileName + ".part");

        RomDownloadTask task = new RomDownloadTask(
                UUID.randomUUID().toString(), fileName, url, headers, finalFile, part);

        // 续传起点：磁盘上已存在的 .part 长度
        if (part.exists()) task.doneBytes = part.length();

        tasks.put(task.id, task);
        synchronized (order) {
            order.add(task.id);
        }
        notifyChanged();
        pool.execute(() -> run(task));
        return task;
    }

    public void pause(String id) {
        RomDownloadTask t = tasks.get(id);
        if (t == null) return;
        if (t.state == RomDownloadTask.State.RUNNING || t.state == RomDownloadTask.State.QUEUED) {
            t.pauseRequested = true;
            // QUEUED 状态下线程可能还没跑起来，直接置状态
            if (t.state == RomDownloadTask.State.QUEUED) {
                t.state = RomDownloadTask.State.PAUSED;
            }
            notifyChanged();
        }
    }

    public void resume(String id) {
        RomDownloadTask t = tasks.get(id);
        if (t == null) return;
        if (!t.canResume()) return;
        t.pauseRequested = false;
        t.cancelRequested = false;
        t.errorMessage = "";
        t.state = RomDownloadTask.State.QUEUED;
        if (t.partFile.exists()) t.doneBytes = t.partFile.length();
        notifyChanged();
        pool.execute(() -> run(t));
    }

    public void cancel(String id) {
        RomDownloadTask t = tasks.get(id);
        if (t == null) return;
        t.cancelRequested = true;
        if (t.state == RomDownloadTask.State.QUEUED) {
            t.state = RomDownloadTask.State.CANCELLED;
            deleteQuietly(t.partFile);
        }
        notifyChanged();
    }

    /** 从列表里移除一条记录（不删已完成的文件，只清列表项）。 */
    public void remove(String id) {
        RomDownloadTask t = tasks.remove(id);
        if (t == null) return;
        synchronized (order) {
            order.remove(id);
        }
        notifyChanged();
    }

    public void clearFinished() {
        List<String> toRemove = new ArrayList<>();
        for (RomDownloadTask t : tasks.values()) {
            if (t.isFinished()) toRemove.add(t.id);
        }
        for (String id : toRemove) {
            tasks.remove(id);
            synchronized (order) {
                order.remove(id);
            }
        }
        if (!toRemove.isEmpty()) notifyChanged();
    }

    // ------------------------------------------------------------ 下载主体

    private void run(RomDownloadTask task) {
        if (task.cancelRequested) {
            task.state = RomDownloadTask.State.CANCELLED;
            deleteQuietly(task.partFile);
            notifyChanged();
            return;
        }

        task.state = RomDownloadTask.State.RUNNING;
        notifyChanged();

        long startAt = task.partFile.exists() ? task.partFile.length() : 0L;
        if (startAt < 0) startAt = 0L;

        File dir = task.finalFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            fail(task, "无法创建目录：" + dir.getAbsolutePath());
            return;
        }

        Response response = openWithFallback(task, startAt);
        if (response == null) {
            String trace = task.fallbackTrace == null ? "" : task.fallbackTrace;
            fail(task, "所有镜像与 UA 组合均无法连接"
                    + (trace.isEmpty() ? "" : "\n已试：" + trace));
            return;
        }
        try (Response resp = response) {
            // 206 = 续传成功；200 = 服务端不支持 Range，得从头写
            boolean resumed = resp.code() == 206;
            if (!resp.isSuccessful()) {
                String hint = "";
                if (resp.code() == 403) {
                    hint = "（CDN 拒绝：已自动轮换镜像域名/UA/Referer 重试）";
                } else if (resp.code() == 404) {
                    hint = "（文件不存在，链接可能已失效）";
                } else if (resp.code() == 429) {
                    hint = "（请求过于频繁，请稍后再试）";
                }
                String trace = task.fallbackTrace == null ? "" : task.fallbackTrace;
                fail(task, "服务器返回 HTTP " + resp.code() + hint
                        + (trace.isEmpty() ? "" : "\n已试组合：" + trace));
                return;
            }
            ResponseBody body = resp.body();
            if (body == null) {
                fail(task, "服务器没有返回数据");
                return;
            }

            long contentLength = body.contentLength();
            if (resumed) {
                // 续传时 Content-Length 只是剩余部分，总量要加上已有的
                task.totalBytes = startAt + (contentLength > 0 ? contentLength : 0);
                task.doneBytes = startAt;
            } else {
                if (startAt > 0) {
                    // 服务端不认 Range：只能重来，清空 .part
                    deleteQuietly(task.partFile);
                    task.doneBytes = 0L;
                }
                task.totalBytes = contentLength;
            }

            boolean append = resumed;
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(task.partFile, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long lastNotify = 0L;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    task.doneBytes += n;

                    if (task.pauseRequested) {
                        out.flush();
                        task.state = RomDownloadTask.State.PAUSED;
                        notifyChanged();
                        updateNotification();
                        return;
                    }
                    if (task.cancelRequested) {
                        out.flush();
                        task.state = RomDownloadTask.State.CANCELLED;
                        deleteQuietly(task.partFile);
                        notifyChanged();
                        updateNotification();
                        return;
                    }

                    // 进度回调节流：约每 256KB 一次，避免刷爆主线程
                    if (task.doneBytes - lastNotify >= 256 * 1024) {
                        lastNotify = task.doneBytes;
                        notifyChanged();
                        updateNotification();
                    }
                }
                out.flush();
            }

            // 完整性校验：服务端给了长度就必须对得上，否则当成失败保留 .part 供续传
            if (task.totalBytes > 0 && task.doneBytes != task.totalBytes) {
                fail(task, "下载不完整：" + task.doneBytes + " / " + task.totalBytes + " 字节");
                return;
            }

            if (task.finalFile.exists() && !task.finalFile.delete()) {
                fail(task, "无法覆盖已存在文件：" + task.finalFile.getName());
                return;
            }
            if (!task.partFile.renameTo(task.finalFile)) {
                fail(task, "无法写入：" + task.finalFile.getAbsolutePath());
                return;
            }

            // 分区存储下可能落在私有目录，下完搬到公共 Download/VioletBox/ROM
            if (appContext != null) {
                File published = VioletPaths.publishToPublic(appContext, task.finalFile, "ROM");
                if (published != null && published.exists() && !published.equals(task.finalFile)) {
                    // finalFile 只是中转，搬走后删掉
                    deleteQuietly(task.finalFile);
                }
            }

            task.state = RomDownloadTask.State.DONE;
            task.doneBytes = task.totalBytes > 0 ? task.totalBytes : task.doneBytes;
            notifyChanged();
            updateNotification();
        } catch (IOException e) {
            fail(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (Throwable t) {
            fail(task, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    /**
     * 依次尝试「镜像域名 × UA × Referer」直到拿到 2xx/3xx。
     *
     * <p>403 不等于链接失效 —— 实测同一个链接在 runner 上所有组合都返回 200/206，
     * 而用户侧 403，说明多半是出口 / 节点层面的拒绝，不是包没了。
     * 所以这里不猜"已过期"，而是真去换路子重试：
     * <ol>
     *     <li>换小米官方镜像域名（路径不变，只换 host = 换出口 IP）；</li>
     *     <li>换 UA（小米 / 通用 / 浏览器 / OkHttp / 空）；</li>
     *     <li>换 Referer。</li>
     * </ol>
     * 全试过仍不行，才判定失败，并把试过的组合写进错误信息，便于定位。
     */
    private Response openWithFallback(RomDownloadTask task, long startAt) {
        List<String> urls = mirrorUrls(task.url);
        String[] uas = ordered(UA_CANDIDATES, preferredUa);
        String[] refs = ordered(REFERER_CANDIDATES, preferredReferer);

        StringBuilder trace = new StringBuilder();
        Response last = null;
        int attempts = 0;

        // 顺序很关键：**优先换域名**。IP 被拒时换 UA/Referer 是白费劲，
        // 必须换到另一台服务器（另一组 IP）才有用。所以对每个域名只试少数几种
        // UA/Referer，尽快把 4 个镜像都走一遍。
        int perHost = Math.max(1, MAX_ATTEMPTS / Math.max(1, urls.size()));
        for (String u : urls) {
            int used = 0;
            for (String ua : uas) {
                for (String ref : refs) {
                    if (attempts >= MAX_ATTEMPTS) break;
                    if (used >= perHost) break;
                    used++;
                    attempts++;
                    Request.Builder b = new Request.Builder().url(u);
                    if (ua != null && !ua.isEmpty()) {
                        b.header("User-Agent", ua);
                    }
                    if (ref != null && !ref.isEmpty()) {
                        b.header("Referer", ref);
                    }
                    if (task.headers != null) {
                        for (Map.Entry<String, String> e : task.headers.entrySet()) {
                            String k = e.getKey();
                            String v = e.getValue();
                            if (k == null || v == null) continue;
                            // Host / Connection 交给 OkHttp 管，手动塞会冲突
                            if ("Host".equalsIgnoreCase(k) || "Connection".equalsIgnoreCase(k)) continue;
                            b.header(k, v);
                        }
                    }
                    if (startAt > 0) {
                        b.header("Range", "bytes=" + startAt + "-");
                    }
                    // 注意：这里只发真正的下载请求，不掺探测。
                    // 探测（Range: 0-0）在 probe() 里单独做，拿到的是 1 字节响应，
                    // 绝不能当成正式数据流返回给调用方，否则会写出一个 1 字节的文件。
                    // 先轻量探测：Range 0-0，只拿响应头，确认这条组合能通
                    int probeCode = probe(u, ua, ref, task.headers);
                    if (probeCode != 200 && probeCode != 206) {
                        recordTrace(trace, u, ua, probeCode);
                        continue;
                    }
                    // 探测通过，用同一套组合发真正的下载请求
                    Response r;
                    try {
                        r = http.newCall(b.build()).execute();
                    } catch (IOException e) {
                        recordTrace(trace, u, ua, -1);
                        continue;                       // 换下一套组合
                    }
                    if (r.isSuccessful() || (r.code() >= 300 && r.code() < 400)) {
                        // 记下这套组合，下次优先
                        preferredUa = ua;
                        preferredReferer = ref;
                        if (last != null) {
                            try {
                                last.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        return r;
                    }
                    recordTrace(trace, u, ua, r.code());
                    if (last != null) {
                        try {
                            last.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    last = r;
                }
            }
        }
        task.fallbackTrace = trace.toString().trim();
        return last;
    }

    /** 最多试多少种组合就收手，避免把 CDN 当靶子打。 */
    private static final int MAX_ATTEMPTS = 8;

    /**
     * 轻量探测：只发 Range: bytes=0-0，读响应头就关掉。
     *
     * <p>目的是在真正开始传几个 G 之前先确认这条「域名+UA+Referer」能不能通，
     * 返回 200/206 才算数。探测响应最多 1 字节，不会拿来当正式数据。
     */
    private int probe(String url, String ua, String ref, Map<String, String> extra) {
        Request.Builder b = new Request.Builder().url(url);
        if (ua != null && !ua.isEmpty()) b.header("User-Agent", ua);
        if (ref != null && !ref.isEmpty()) b.header("Referer", ref);
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) continue;
                if ("Host".equalsIgnoreCase(k) || "Connection".equalsIgnoreCase(k)) continue;
                b.header(k, v);
            }
        }
        b.header("Range", "bytes=0-0");
        try (Response r = http.newCall(b.build()).execute()) {
            if (r.body() != null) {
                try {
                    r.body().bytes();       // 读完并丢弃，确保连接可复用
                } catch (Throwable ignored) {
                }
            }
            return r.code();
        } catch (IOException e) {
            return -1;                      // 网络层失败
        }
    }

    private static void recordTrace(StringBuilder sb, String url, String ua, int code) {
        String host = "";
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Throwable ignored) {
        }
        if (host == null) host = "";
        sb.append(host).append('/')
                .append(ua == null || ua.isEmpty() ? "(空UA)" : ua.substring(0, Math.min(16, ua.length())))
                .append('=').append(code).append(' ');
    }

    private void fail(RomDownloadTask task, String reason) {
        task.state = RomDownloadTask.State.FAILED;
        task.errorMessage = reason == null ? "未知错误" : reason;
        notifyChanged();
        updateNotification();
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ------------------------------------------------------------ 通知

    private void ensureChannel() {
        if (appContext == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_DOWNLOAD) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CH_DOWNLOAD, "ROM 下载", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("ROM 下载进度与结果");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** 聚合通知：正在下几条 + 总体百分比；全部结束则提示完成。 */
    private void updateNotification() {
        if (appContext == null) return;
        NotificationManager nm =
                (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        int running = 0;
        int failed = 0;
        long done = 0L;
        long total = 0L;
        String lastName = "";
        for (RomDownloadTask t : tasks.values()) {
            if (t.state == RomDownloadTask.State.RUNNING || t.state == RomDownloadTask.State.QUEUED) {
                running++;
                lastName = t.fileName;
                done += t.doneBytes;
                if (t.totalBytes > 0) total += t.totalBytes;
            } else if (t.state == RomDownloadTask.State.FAILED) {
                failed++;
            }
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(appContext, CH_DOWNLOAD);
        try {
            if (running > 0) {
                int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                b.setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("正在下载 " + running + " 个文件")
                        .setContentText(lastName + " · " + formatSize(done)
                                + (total > 0 ? " / " + formatSize(total) : ""))
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setProgress(100, percent, total <= 0);
            } else {
                String text = failed > 0 ? (failed + " 个下载失败，点开查看") : "全部下载完成";
                b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("ROM 下载")
                        .setContentText(text)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setProgress(0, 0, false);
            }
            nm.notify(NOTIFY_ID, b.build());
        } catch (SecurityException ignored) {
            // 没通知权限就算了，列表页照样能看到进度
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
    }

    /** 已下字节数（供 UI 显示），磁盘 .part 优先。 */
    public static long realDoneBytes(RomDownloadTask t) {
        if (t == null) return 0L;
        if (t.partFile != null && t.partFile.exists() && t.state != RomDownloadTask.State.DONE) {
            return Math.max(t.doneBytes, t.partFile.length());
        }
        return t.doneBytes;
    }
}
