package com.violet.box.ui.rom;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.violet.box.R;
import com.violet.box.core.util.VioletPaths;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载管理页 —— 集中查看 / 暂停 / 继续 / 取消 / 打开 ROM 下载任务。
 *
 * <p>任务本体在 {@link RomDownloadManager} 里常驻，本页面只负责展示和操作，
 * 所以关掉页面再回来进度还在，下载也不会停。
 */
public class RomDownloadsActivity extends AppCompatActivity
        implements RomDownloadManager.Listener {

    private RecyclerView rv;
    private TextView tvSummary;
    private TextView tvEmpty;
    private SwipeRefreshLayout swipe;
    private TaskAdapter adapter;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rom_downloads);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rv = findViewById(R.id.rvTasks);
        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        swipe = findViewById(R.id.swipe);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TaskAdapter();
        rv.setAdapter(adapter);

        swipe.setOnRefreshListener(() -> {
            refresh();
            swipe.setRefreshing(false);
        });

        RomDownloadManager.get().init(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RomDownloadManager.get().addListener(this);
        refresh();
        main.post(ticker);          // 每秒刷新一次进度
    }

    @Override
    protected void onPause() {
        super.onPause();
        RomDownloadManager.get().removeListener(this);
        main.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(ticker);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_rom_downloads, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_clear_finished) {
            RomDownloadManager.get().clearFinished();
            refresh();
            return true;
        }
        if (id == R.id.action_open_folder) {
            openFolder(VioletPaths.resolveDir(this, "ROM"));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onChanged() {
        refresh();
    }

    private void refresh() {
        List<RomDownloadTask> list = RomDownloadManager.get().snapshot();
        adapter.submit(list);

        int active = RomDownloadManager.get().activeCount();
        tvSummary.setText(active > 0
                ? "进行中 " + active + " 个 · 共 " + list.size() + " 条记录"
                : "共 " + list.size() + " 条记录");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openFolder(File dir) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", dir);
            intent.setDataAndType(uri, "resource/folder");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开目录：" + dir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ 适配器

    private class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.VH> {

        private final List<RomDownloadTask> data = new ArrayList<>();

        void submit(List<RomDownloadTask> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_rom_download, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            h.bind(data.get(position));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvStatus;
            final android.widget.ProgressBar pb;
            final View btnPause, btnResume, btnCancel, btnOpen, btnRemove, btnCopyLink, btnBrowse;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvStatus = v.findViewById(R.id.tvStatus);
                pb = v.findViewById(R.id.pbTask);
                btnPause = v.findViewById(R.id.btnPause);
                btnResume = v.findViewById(R.id.btnResume);
                btnCancel = v.findViewById(R.id.btnCancel);
                btnOpen = v.findViewById(R.id.btnOpen);
                btnRemove = v.findViewById(R.id.btnRemove);
                btnCopyLink = v.findViewById(R.id.btnCopyLink);
                btnBrowse = v.findViewById(R.id.btnBrowse);
            }

            void bind(RomDownloadTask t) {
                tvName.setText(t.fileName);

                long done = RomDownloadManager.realDoneBytes(t);
                String sizeText = t.totalBytes > 0
                        ? RomDownloadManager.formatSize(done) + " / "
                        + RomDownloadManager.formatSize(t.totalBytes)
                        : RomDownloadManager.formatSize(done);

                switch (t.state) {
                    case QUEUED:
                        tvStatus.setText("等待中 · " + sizeText);
                        break;
                    case RUNNING:
                        tvStatus.setText("下载中 " + t.percent() + "% · " + sizeText);
                        break;
                    case PAUSED:
                        tvStatus.setText("已暂停（可继续）· " + sizeText);
                        break;
                    case DONE:
                        tvStatus.setText("已完成 · " + RomDownloadManager.formatSize(
                                t.finalFile.length() > 0 ? t.finalFile.length() : done));
                        break;
                    case FAILED:
                        tvStatus.setText("失败：" + t.errorMessage + " · " + sizeText);
                        break;
                    case CANCELLED:
                    default:
                        tvStatus.setText("已取消");
                        break;
                }

                pb.setProgress(t.percent());
                pb.setVisibility(t.state == RomDownloadTask.State.DONE
                        || t.state == RomDownloadTask.State.CANCELLED ? View.GONE : View.VISIBLE);

                boolean running = t.canPause();
                btnPause.setVisibility(running ? View.VISIBLE : View.GONE);
                btnResume.setVisibility(t.canResume() ? View.VISIBLE : View.GONE);
                btnCancel.setVisibility(t.isFinished() ? View.GONE : View.VISIBLE);
                btnOpen.setVisibility(t.state == RomDownloadTask.State.DONE ? View.VISIBLE : View.GONE);
                // 失败时给用户退路：复制直链用别的下载器，或直接丢给浏览器
                boolean failedOrPaused = t.state == RomDownloadTask.State.FAILED
                        || t.state == RomDownloadTask.State.PAUSED;
                btnCopyLink.setVisibility(failedOrPaused ? View.VISIBLE : View.GONE);
                btnBrowse.setVisibility(failedOrPaused ? View.VISIBLE : View.GONE);

                btnPause.setOnClickListener(v -> {
                    RomDownloadManager.get().pause(t.id);
                    refresh();
                });
                btnResume.setOnClickListener(v -> {
                    RomDownloadManager.get().resume(t.id);
                    refresh();
                });
                btnCancel.setOnClickListener(v -> {
                    RomDownloadManager.get().cancel(t.id);
                    refresh();
                });
                btnOpen.setOnClickListener(v -> {
                    if (!t.finalFile.exists()) {
                        Toast.makeText(RomDownloadsActivity.this, "文件已不存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        Uri uri = FileProvider.getUriForFile(RomDownloadsActivity.this,
                                getPackageName() + ".fileprovider", t.finalFile);
                        intent.setDataAndType(uri, "application/zip");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(RomDownloadsActivity.this,
                                "没有能打开该文件的应用", Toast.LENGTH_SHORT).show();
                    }
                });
                btnCopyLink.setOnClickListener(v -> {
                    android.content.ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("rom_link", t.url));
                        Toast.makeText(RomDownloadsActivity.this, "直链已复制", Toast.LENGTH_SHORT).show();
                    }
                });
                btnBrowse.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(t.url)));
                    } catch (Exception e) {
                        Toast.makeText(RomDownloadsActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
                    }
                });
                btnRemove.setOnClickListener(v -> {
                    RomDownloadManager.get().remove(t.id);
                    refresh();
                });
            }
        }
    }
}
