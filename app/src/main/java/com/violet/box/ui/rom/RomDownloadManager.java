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
     * OPPO 的 OTA CDN（gauss-componentotacostmanual-cn.allawnfs.com）会直接 403 掉
     * {@code okhttp/x.y.z} 这个默认 UA，也会拒掉浏览器 / SmartTool 风格的 UA。
     * 实测能通过的有：不带 UA、空 UA、Dalvik（Android 原生）、Python-urllib。
     * 这里伪装成 Android 原生 UA——既通过校验，也比"完全没有 UA"更像正常客户端。
     */
    private static final String SAFE_UA = "Dalvik/2.1.0 (Linux; U; Android 13; SM-G991B Build/TP1A.220624.014)";

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

        Request.Builder builder = new Request.Builder().url(task.url);
        // 先压上安全 UA，避免 OkHttp 默认的 okhttp/x.y.z 被 CDN 403
        builder.header("User-Agent", SAFE_UA);
        if (task.headers != null) {
            for (Map.Entry<String, String> e : task.headers.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (key == null || value == null) continue;
                // Host / Connection 交给 OkHttp 自己管，手动塞会冲突
                if ("Host".equalsIgnoreCase(key) || "Connection".equalsIgnoreCase(key)) continue;
                builder.header(key, value);
            }
        }
        // 断点续传：从已下完的位置继续要
        if (startAt > 0) {
            builder.header("Range", "bytes=" + startAt + "-");
        }

        File dir = task.finalFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            fail(task, "无法创建目录：" + dir.getAbsolutePath());
            return;
        }

        try {
        Response response = http.newCall(builder.build()).execute();
        if (!response.isSuccessful() && retryOnForbidden(task, response)) {
            // 被 CDN 以 403 拒绝：换一组更"朴素"的 UA 重试一次
            Request.Builder retry = new Request.Builder().url(task.url);
            retry.header("User-Agent", "");
            if (startAt > 0) {
                retry.header("Range", "bytes=" + startAt + "-");
            }
            response = http.newCall(retry.build()).execute();
        }
        try (Response resp = response) {
            // 206 = 续传成功；200 = 服务端不支持 Range，得从头写
            boolean resumed = resp.code() == 206;
            if (!resp.isSuccessful()) {
                fail(task, "服务器返回 HTTP " + resp.code()
                        + (resp.code() == 403 ? "（CDN 拒绝，直链可能已过期或被限流）" : ""));
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
        } catch (IOException e) {
            // 发起请求 / 重试请求本身失败（如 DNS、连接超时）
            fail(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 只在 403 时重试一次，其他错误直接失败，避免无效重放。 */
    private boolean retryOnForbidden(RomDownloadTask task, Response response) {
        if (response == null || response.code() != 403) return false;
        try {
            response.close();
        } catch (Throwable ignored) {
        }
        return true;
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
