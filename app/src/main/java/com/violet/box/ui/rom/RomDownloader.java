package com.violet.box.ui.rom;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ROM 整包下载器。
 *
 * <p>为什么不用系统 DownloadManager：交给它之后 App 完全看不到结果——成功与否、卡在几 %、
 * 失败原因统统拿不到，用户只能看到一个「直链已复制」的 Toast，会以为根本没下载。
 * 这里跟「模块下载中心」一致，用 OkHttp 自己下：进度能进进度条和通知栏，失败能给出原因。
 *
 * <p>先写 .part 再改名，避免中断留下半截 zip 被当成完整包。
 */
public final class RomDownloader {

    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes);
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private RomDownloader() {
    }

    static void download(String url, Map<String, String> headers, File outFile,
                         ProgressListener listener) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (key == null || value == null) continue;
                // 交给 OkHttp 自己管的头别手动塞，否则会被覆盖或触发严格模式校验
                if ("Host".equalsIgnoreCase(key)) continue;
                if ("Connection".equalsIgnoreCase(key)) continue;
                builder.header(key, value);
            }
        }

        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent.getAbsolutePath());
        }
        File part = new File(parent, outFile.getName() + ".part");
        if (part.exists() && !part.delete()) {
            throw new IOException("无法清理旧临时文件：" + part.getName());
        }

        try (Response response = HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("服务器返回 HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("服务器没有返回数据");
            }
            long total = body.contentLength();
            long done = 0L;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (listener != null && done % (1024 * 1024) == 0 || (listener != null && done == total)) {
                        listener.onProgress(done, total);
                    }
                }
                out.flush();
            }
            if (total > 0 && done != total) {
                throw new IOException("下载中断：" + done + " / " + total + " 字节");
            }
            if (outFile.exists() && !outFile.delete()) {
                throw new IOException("无法覆盖已存在文件：" + outFile.getName());
            }
            if (!part.renameTo(outFile)) {
                throw new IOException("无法写入：" + outFile.getAbsolutePath());
            }
            if (listener != null) {
                listener.onProgress(done, total);
            }
        } finally {
            if (part.exists() && !outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }
}
