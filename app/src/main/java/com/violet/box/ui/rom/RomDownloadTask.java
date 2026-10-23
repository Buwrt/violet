package com.violet.box.ui.rom;

import java.io.File;
import java.util.Map;

/**
 * 一个 ROM 下载任务。
 *
 * <p>字段都是公开的 final / volatile：UI 线程读、下载线程写，靠 volatile 保证可见性；
 * 状态切换集中在 {@link RomDownloadManager} 里，避免各处直接改。
 */
public final class RomDownloadTask {

    public enum State {
        /** 排队中，还没轮到下载线程。 */
        QUEUED,
        /** 正在下载。 */
        RUNNING,
        /** 已暂停，.part 保留在磁盘上，可继续。 */
        PAUSED,
        /** 已完成，finalFile 有效。 */
        DONE,
        /** 失败，errorMessage 有效；已下的字节仍在 .part 里，可继续重试。 */
        FAILED,
        /** 已取消，.part 会删掉。 */
        CANCELLED
    }

    public final String id;
    public final String fileName;
    public final String url;
    public final Map<String, String> headers;
    public final File finalFile;
    public final File partFile;
    public final long createdAt;

    public volatile State state = State.QUEUED;
    /** 已写入的字节数（含本次之前续传累积的部分）。 */
    public volatile long doneBytes = 0L;
    /** 总字节数；服务端没给 Content-Length 时为 -1。 */
    public volatile long totalBytes = -1L;
    public volatile String errorMessage = "";
    /** 轮换重试时试过的「域名/UA=状态码」记录，失败时展示，便于定位是哪一步被拒。 */
    public volatile String fallbackTrace = "";

    /** 暂停标志：下载线程在块边界上检查它，置位后主动退出并保留 .part。 */
    volatile boolean pauseRequested = false;
    volatile boolean cancelRequested = false;

    RomDownloadTask(String id, String fileName, String url, Map<String, String> headers,
                    File finalFile, File partFile) {
        this.id = id;
        this.fileName = fileName;
        this.url = url;
        this.headers = headers;
        this.finalFile = finalFile;
        this.partFile = partFile;
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isFinished() {
        return state == State.DONE || state == State.CANCELLED;
    }

    /** 是否可以「继续」：暂停或失败且已下了一部分。 */
    public boolean canResume() {
        return state == State.PAUSED || state == State.FAILED;
    }

    /** 是否可以「暂停」：只在真正跑着的时候才有意义。 */
    public boolean canPause() {
        return state == State.RUNNING || state == State.QUEUED;
    }

    public int percent() {
        if (totalBytes <= 0) return 0;
        return (int) Math.min(100, doneBytes * 100 / totalBytes);
    }
}
