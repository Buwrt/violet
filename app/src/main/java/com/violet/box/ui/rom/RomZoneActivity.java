package com.violet.box.ui.rom;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseBooleanArray;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.violet.box.R;
import com.violet.box.core.util.VioletPaths;
import com.violet.box.ui.payload.PayloadCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ROM 专区（V13）：六级筛选 + 版本多选 + 批量下载 + boot / init_boot 单独提取。
 *
 * <p>筛选顺序：品牌 → 区域 → 版本类型 → ROM 类型 → 系列 → 机型。
 * 每一级只列出「在前面所有条件下仍有可下载版本」的选项，
 * 所以用户不会选出一个空结果。
 *
 * <p>纯本地：清单读 assets/rom_catalog.json，筛选全程不联网。
 */
public class RomZoneActivity extends AppCompatActivity {

    private static final String ALL = "全部";
    private static final String PLACEHOLDER = "—";

    private Spinner spBrand;
    private Spinner spRegion;
    private Spinner spVersionType;
    private Spinner spRomType;
    private Spinner spSeries;
    private Spinner spDevice;
    private Spinner spPartition;
    private ListView lvVersions;
    private TextView tvLog;
    private TextView tvCount;
    private TextView tvDeviceInfo;
    private EditText etDeviceSearch;
    private ProgressBar progressBar;

    /** 当前机型池（已按品牌 + 搜索词过滤），系列和机型下拉都从它派生。 */
    private List<LocalRomCatalog.Device> pool = new ArrayList<>();
    /** 筛选出来的版本，与 lvVersions 下标一一对应。 */
    private final List<LocalRomCatalog.Version> versionResults = new ArrayList<>();
    /** 每个版本所属机型名，用于下载文件命名。 */
    private final List<String> versionDeviceNames = new ArrayList<>();
    /** 当前选中的版本（单条操作用）。 */
    private LocalRomCatalog.Version currentVer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 搜索防抖用的实际动作，配合 removeCallbacks 去重。 */
    private final Runnable searchAction = new Runnable() {
        @Override public void run() { onBrandChanged(); }
    };
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rom_zone);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        spBrand = findViewById(R.id.spBrand);
        spRegion = findViewById(R.id.spRegion);
        spVersionType = findViewById(R.id.spVersionType);
        spRomType = findViewById(R.id.spRomType);
        spSeries = findViewById(R.id.spSeries);
        spDevice = findViewById(R.id.spDevice);
        spPartition = findViewById(R.id.spPartition);
        lvVersions = findViewById(R.id.lvVersions);
        tvLog = findViewById(R.id.tvLog);
        tvCount = findViewById(R.id.tvCount);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        etDeviceSearch = findViewById(R.id.etDeviceSearch);
        progressBar = findViewById(R.id.progressBar);

        MaterialButton btnAll = findViewById(R.id.btnSelectAll);
        MaterialButton btnNone = findViewById(R.id.btnClearSel);
        MaterialButton btnBatch = findViewById(R.id.btnBatchDownload);
        MaterialButton btnBoot = findViewById(R.id.btnExtractBoot);
        MaterialButton btnInitBoot = findViewById(R.id.btnExtractInitBoot);
        MaterialButton btnReload = findViewById(R.id.btnReload);
        MaterialButton btnFetchLink = findViewById(R.id.btnFetchLink);
        MaterialButton btnDownloadRom = findViewById(R.id.btnDownloadRom);
        MaterialButton btnExtractPartition = findViewById(R.id.btnExtractPartition);
        MaterialButton btnDownloads = findViewById(R.id.btnDownloads);

        // 品牌
        List<String> brands = new ArrayList<>();
        brands.add(ALL);
        brands.addAll(LocalRomCatalog.brands(this));
        setSpinner(spBrand, brands);

        spBrand.setOnItemSelectedListener(new SimpleSelected(this::onBrandChanged));
        spRegion.setOnItemSelectedListener(new SimpleSelected(this::onRegionChanged));
        spVersionType.setOnItemSelectedListener(new SimpleSelected(this::onVersionTypeChanged));
        spRomType.setOnItemSelectedListener(new SimpleSelected(this::onRomTypeChanged));
        spSeries.setOnItemSelectedListener(new SimpleSelected(this::onSeriesChanged));
        spDevice.setOnItemSelectedListener(new SimpleSelected(this::onDeviceChanged));

        // 输入防抖：停止输入 300ms 后才筛选，避免逐字符重建六个下拉框
        etDeviceSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                mainHandler.removeCallbacks(searchAction);
                mainHandler.postDelayed(searchAction, 300);
            }
        });

        btnAll.setOnClickListener(v -> setAllChecked(true));
        btnNone.setOnClickListener(v -> setAllChecked(false));
        btnBatch.setOnClickListener(v -> batchDownload());
        btnBoot.setOnClickListener(v -> extractBoot(false));
        btnInitBoot.setOnClickListener(v -> extractBoot(true));
        btnReload.setOnClickListener(v -> {
            LocalRomCatalog.brands(this);
            onBrandChanged();
            log("已重新载入本地清单");
        });
        btnFetchLink.setOnClickListener(v -> copyCurrentLink());
        btnDownloadRom.setOnClickListener(v -> downloadCurrent());
        btnExtractPartition.setOnClickListener(v -> extractPartitionFromCloud());
        btnDownloads.setOnClickListener(v ->
                startActivity(new Intent(this, RomDownloadsActivity.class)));

        ensureChannel();
        onBrandChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------ 六级级联
    //
    // 两个坑，都已修掉：
    // 1) 早期实现在 onSeriesChanged() 里重建了 spSeries 自己，新 adapter 一挂上
    //    选中项就回到第 0 项「全部」，用户刚点的 Ace 系列当场被抹掉。
    //    → 现在 setSpinner() 先比对内容，一模一样就完全不动；内容真变了才重建，
    //      且重建后按原值找回位置（值还在就恢复，被过滤掉才退回「全部」）。
    // 2) 换 adapter 会触发选中回调，回调又级联 → 每次点击都在主线程里循环重建
    //    六个下拉框并重排整个页面，所以特别卡。
    //    → 现在用 cascade 标志抑制，且改成单向级联：改哪一级只重算它下面的，
    //      上面几级原样不动。

    /** 级联抑制：程序化重建下拉期间吞掉所有选中回调，避免消息风暴。 */
    private boolean cascade;

    private void runCascade(Runnable body) {
        cascade = true;
        try {
            body.run();
        } finally {
            // 延后解除：Spinner 的选中回调可能是下一帧才投递的
            mainHandler.post(() -> cascade = false);
        }
    }

    /** 第 1 级：品牌变了 → 重算机型池，再向下重算区域及之后。 */
    private void onBrandChanged() {
        if (cascade) return;
        String brand = current(spBrand);
        List<LocalRomCatalog.Device> base = (brand.isEmpty() || ALL.equals(brand))
                ? LocalRomCatalog.allDevices(this)
                : LocalRomCatalog.devices(this, brand);
        pool = filterBySearch(base);
        runCascade(() -> {
            setSpinner(spRegion, optionsFor(d -> d.region, false, false, false));
            cascadeFromVersionType();
        });
    }

    private void onRegionChanged() {
        if (cascade) return;
        runCascade(this::cascadeFromVersionType);
    }

    private void onVersionTypeChanged() {
        if (cascade) return;
        runCascade(this::cascadeFromRomType);
    }

    private void onRomTypeChanged() {
        if (cascade) return;
        runCascade(this::cascadeFromSeries);
    }

    private void onSeriesChanged() {
        if (cascade) return;
        runCascade(this::cascadeFromDevice);
    }

    private void onDeviceChanged() {
        if (cascade) return;
        runCascade(this::refreshVersions);
    }

    /** 版本类型 → ROM 类型 → 系列 → 机型 → 版本列表。 */
    private void cascadeFromVersionType() {
        setSpinner(spVersionType, optionsFor(d -> d.versionType, true, false, false));
        cascadeFromRomType();
    }

    private void cascadeFromRomType() {
        setSpinner(spRomType, optionsFor(d -> d.romType, true, true, false));
        cascadeFromSeries();
    }

    private void cascadeFromSeries() {
        List<String> series = new ArrayList<>();
        for (LocalRomCatalog.Device d : pool) {
            if (matchesVersionFilter(d) && d.series != null && !d.series.isEmpty()
                    && !series.contains(d.series)) {
                series.add(d.series);
            }
        }
        if (series.size() > 1) series.add(0, ALL);
        setSpinner(spSeries, series);
        cascadeFromDevice();
    }

    private void cascadeFromDevice() {
        String selSeries = current(spSeries);
        List<String> names = new ArrayList<>();
        List<LocalRomCatalog.Device> devs = new ArrayList<>();
        for (LocalRomCatalog.Device d : pool) {
            if (!matchesVersionFilter(d)) continue;
            if (!selSeries.isEmpty() && !ALL.equals(selSeries) && !selSeries.equals(d.series)) {
                continue;
            }
            devs.add(d);
        }
        if (devs.size() > 1) names.add(ALL);
        for (LocalRomCatalog.Device d : devs) names.add(d.name + "（" + d.key + "）");
        setSpinner(spDevice, names);
        refreshVersions();
    }

    /** 第 6 级：机型 → 列出版本。 */
    private void refreshVersions() {
        String devText = current(spDevice);
        String deviceKey = "";
        if (!devText.isEmpty() && !ALL.equals(devText) && !PLACEHOLDER.equals(devText)) {
            int a = devText.lastIndexOf('（');
            int b = devText.lastIndexOf('）');
            if (a >= 0 && b > a) deviceKey = devText.substring(a + 1, b);
        }
        String selSeries = current(spSeries);

        versionResults.clear();
        versionDeviceNames.clear();
        for (LocalRomCatalog.Device d : pool) {
            if (!matchesVersionFilter(d)) continue;
            if (!deviceKey.isEmpty() && !deviceKey.equals(d.key)) continue;
            if (!selSeries.isEmpty() && !ALL.equals(selSeries) && !selSeries.equals(d.series)) {
                continue;
            }
            for (LocalRomCatalog.Version v : d.versions) {
                if (!v.hasLink()) continue;
                versionResults.add(v);
                versionDeviceNames.add(d.name + "_" + d.key);
            }
        }

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < versionResults.size(); i++) {
            LocalRomCatalog.Version v = versionResults.get(i);
            String size = v.size > 0 ? "（" + formatSize(v.size) + "）" : "";
            labels.add(versionDeviceNames.get(i) + " · " + v.label() + size);
        }
        lvVersions.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels));
        lvVersions.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText(pool.size() + " 个机型 · 匹配 " + versionResults.size() + " 个版本");
        }
        updateCount();
    }

    /** 区域 + 版本类型 + ROM 类型 三项全过（系列和机型由列表自己收窄）。 */
    private boolean matchesVersionFilter(LocalRomCatalog.Device d) {
        return matchesLevels(d, true, true, true);
    }

    /**
     * 按「排在它前面」的几级过滤机型。
     * 算第 N 级的可选值时只启用第 1..N-1 级，否则下拉里会出现选了就没结果的项。
     */
    private boolean matchesLevels(LocalRomCatalog.Device d,
                                  boolean useRegion, boolean useVerType, boolean useRomType) {
        String region = current(spRegion);
        String verType = current(spVersionType);
        String romType = current(spRomType);
        for (LocalRomCatalog.Version v : d.versions) {
            if (!v.hasLink()) continue;
            if (useRegion && !region.isEmpty() && !ALL.equals(region)
                    && !region.equals(v.region)) continue;
            if (useVerType && !verType.isEmpty() && !ALL.equals(verType)
                    && !verType.equals(v.versionType)) continue;
            if (useRomType && !romType.isEmpty() && !ALL.equals(romType)
                    && !romType.equals(v.romType)) continue;
            return true;
        }
        return false;
    }

    /** 从满足条件的版本里抽出某一字段的可选值。 */
    private List<String> optionsFor(FieldPicker picker,
                                    boolean useRegion, boolean useVerType, boolean useRomType) {
        Set<String> set = new LinkedHashSet<>();
        for (LocalRomCatalog.Device d : pool) {
            if (!matchesLevels(d, useRegion, useVerType, useRomType)) continue;
            for (LocalRomCatalog.Version v : d.versions) {
                if (v.hasLink()) set.add(picker.pick(v));
            }
        }
        List<String> out = new ArrayList<>(set);
        if (out.size() > 1) out.add(0, ALL);
        return out;
    }

    /** 从一条版本记录里取出某一级筛选字段。 */
    private interface FieldPicker {
        String pick(LocalRomCatalog.Version v);
    }

    private List<LocalRomCatalog.Device> filterBySearch(List<LocalRomCatalog.Device> base) {
        String q = etDeviceSearch == null ? "" : String.valueOf(etDeviceSearch.getText()).trim();
        if (q.isEmpty()) return base;
        String[] terms = q.toLowerCase(Locale.ROOT).split("\\s+");
        List<LocalRomCatalog.Device> out = new ArrayList<>();
        for (LocalRomCatalog.Device d : base) {
            String hay = (d.name + " " + d.key + " " + d.code + " " + d.note)
                    .toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String t : terms) {
                if (!hay.contains(t)) { all = false; break; }
            }
            if (all) out.add(d);
        }
        return out;
    }


    // ------------------------------------------------------------ 版本选择与下载

    private List<Integer> checkedPositions() {
        List<Integer> out = new ArrayList<>();
        SparseBooleanArray checked = lvVersions.getCheckedItemPositions();
        for (int i = 0; i < versionResults.size(); i++) {
            if (checked.get(i)) out.add(i);
        }
        if (out.isEmpty() && !versionResults.isEmpty()) {
            out.add(0);
            lvVersions.setItemChecked(0, true);
        }
        return out;
    }

    private void setAllChecked(boolean value) {
        for (int i = 0; i < versionResults.size(); i++) lvVersions.setItemChecked(i, value);
        updateCount();
    }

    private void updateCount() {
        if (tvCount == null) return;
        int n = 0;
        SparseBooleanArray checked = lvVersions.getCheckedItemPositions();
        for (int i = 0; i < versionResults.size(); i++) if (checked.get(i)) n++;
        tvCount.setText(n == 0
                ? "共 " + versionResults.size() + " 个版本，未选择（单选操作默认用第一条）"
                : "已选 " + n + " / " + versionResults.size() + " 个版本");
    }

    private LocalRomCatalog.Version currentVersion() {
        List<Integer> pos = checkedPositions();
        if (pos.isEmpty()) return null;
        return versionResults.get(pos.get(0));
    }

    private String currentDeviceName() {
        List<Integer> pos = checkedPositions();
        if (pos.isEmpty()) return "";
        return versionDeviceNames.get(pos.get(0));
    }

    private void copyCurrentLink() {
        LocalRomCatalog.Version v = currentVersion();
        if (v == null) {
            Toast.makeText(this, "列表里没有可下载的版本", Toast.LENGTH_SHORT).show();
            return;
        }
        currentVer = v;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ROM 直链", v.url));
        log(describe(v));
        Toast.makeText(this, "直链已复制", Toast.LENGTH_SHORT).show();
    }

    private void downloadCurrent() {
        LocalRomCatalog.Version v = currentVersion();
        if (v == null) {
            Toast.makeText(this, "请先勾选一个版本", Toast.LENGTH_SHORT).show();
            return;
        }
        currentVer = v;
        startDownload(currentDeviceName(), v.label(), v.url, null);
    }

    /**
     * 批量下载：把勾选的每一条都丢进下载队列。
     * 队列串行执行，不并发抢带宽，也不会因并发过高触发站点限流。
     */
    private void batchDownload() {
        List<Integer> pos = checkedPositions();
        if (pos.isEmpty()) {
            Toast.makeText(this, "请先勾选要下载的版本", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = 0;
        for (int i : pos) {
            LocalRomCatalog.Version v = versionResults.get(i);
            if (!v.hasLink()) continue;
            startDownload(versionDeviceNames.get(i), v.label(), v.url, null);
            n++;
        }
        log("已把 " + n + " 个版本加入下载队列（串行执行）。\n"
                + "点「下载管理」可暂停 / 继续 / 取消，关掉本页面也不会中断。");
        Toast.makeText(this, "已加入 " + n + " 个下载任务", Toast.LENGTH_SHORT).show();
    }

    private void startDownload(String device, String version, String link,
                               Map<String, String> headers) {
        String outName = buildFileName(device, version, guessSuffix(link));
        RomDownloadManager.get().init(this);
        RomDownloadTask task = RomDownloadManager.get().enqueue(this, link, headers, outName);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        log("已加入下载队列：" + outName);
        Toast.makeText(this, "已加入下载队列：" + outName, Toast.LENGTH_SHORT).show();
        startProgressTicker(task);
    }

    private void startProgressTicker(RomDownloadTask task) {
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            if (isFinishing() || isDestroyed()) return;
            progressBar.setProgress(task.percent());
            long done = RomDownloadManager.realDoneBytes(task);
            log(task.fileName + " · " + stateLabel(task.state) + " · "
                    + RomDownloadManager.formatSize(done)
                    + (task.totalBytes > 0 ? " / " + RomDownloadManager.formatSize(task.totalBytes) : "")
                    + (task.percent() > 0 ? "（" + task.percent() + "%）" : ""));
            if (!task.isFinished() && task.state != RomDownloadTask.State.FAILED) {
                mainHandler.postDelayed(holder[0], 1000);
            } else if (task.state == RomDownloadTask.State.DONE) {
                log("下载完成 → " + task.finalFile.getAbsolutePath()
                        + "（" + RomDownloadManager.formatSize(task.finalFile.length()) + "）");
            }
        };
        mainHandler.post(holder[0]);
    }

    private static String stateLabel(RomDownloadTask.State state) {
        switch (state) {
            case QUEUED: return "等待中";
            case RUNNING: return "下载中";
            case PAUSED: return "已暂停";
            case DONE: return "已完成";
            case FAILED: return "失败";
            case CANCELLED:
            default: return "已取消";
        }
    }

    // ------------------------------------------------------------ boot / init_boot 提取

    /**
     * 提取 boot / init_boot —— 玩机最高频的需求，单独给按钮。
     *
     * <p>两条路径：
     * <ul>
     *     <li>含 payload.bin 的 OTA 全量包：走 PayloadCore 按块 Range 拉取，
     *         <b>不下载整包</b>，几 GB 的包只取几十 MB 的那一个分区。</li>
     *     <li>分区镜像归档（一加）：包本身就是解好的镜像集合，体积很小，
     *         直接下载即可，解压即得 boot.img / init_boot.img。</li>
     * </ul>
     */
    private void extractBoot(boolean initBoot) {
        LocalRomCatalog.Version v = currentVersion();
        if (v == null) {
            Toast.makeText(this, "请先勾选一个版本", Toast.LENGTH_SHORT).show();
            return;
        }
        currentVer = v;
        final String part = initBoot ? "init_boot" : "boot";
        if (!v.canExtractBoot()) {
            log("该包无法直接提取 " + part + ".img：既不含 payload.bin，也不是分区镜像归档。\n"
                    + "只能下载整包后本地解包。");
            Toast.makeText(this, "该包不支持直接提取 " + part, Toast.LENGTH_LONG).show();
            return;
        }
        if (v.payload) {
            setPartitionSilently(part);
            log("该包含 payload.bin，正在不下整包直接提取 " + part + ".img ...");
            extractPartitionFromCloud();
            return;
        }
        startDownload(currentDeviceName(), v.label() + "_" + part, v.url, null);
        log("这是分区镜像归档，包里已经是解好的镜像，直接下载即可。\n"
                + "解压后取 " + part + ".img（同包内还含 boot / init_boot / vendor_boot 等）。");
    }

    private void setPartitionSilently(String name) {
        for (int i = 0; i < spPartition.getCount(); i++) {
            if (name.equals(spPartition.getItemAtPosition(i))) {
                spPartition.setSelection(i);
                return;
            }
        }
    }

    /** 云端提取：不下整包，按块 Range 拉取指定分区。 */
    private void extractPartitionFromCloud() {
        if (busy) return;
        LocalRomCatalog.Version v = currentVer != null ? currentVer : currentVersion();
        if (v == null) {
            Toast.makeText(this, "请先勾选一个版本", Toast.LENGTH_SHORT).show();
            return;
        }
        String sel = current(spPartition);
        if (sel.isEmpty()) sel = "boot";
        final String partition = sel;
        final String link = v.url;
        final String device = currentDeviceName();
        final String verLabel = v.label();

        setBusy(true);
        log("准备云端提取 " + partition + "（不下整包，按块 Range 拉取）\n" + link);

        ioExecutor.execute(() -> {
            PayloadCore.PayloadSession session = null;
            try {
                session = PayloadCore.parseFromUrl(link, RomPayloadProbe.SAFE_USER_AGENT);
                List<PayloadCore.PartitionInfoRow> rows =
                        PayloadCore.getPartitionInfoList(session);
                PayloadCore.PartitionInfoRow target = null;
                for (PayloadCore.PartitionInfoRow row : rows) {
                    if (partition.equals(row.name)) { target = row; break; }
                }
                if (target == null) {
                    StringBuilder names = new StringBuilder();
                    for (PayloadCore.PartitionInfoRow row : rows) {
                        names.append(row.name).append(' ');
                    }
                    throw new IOException("包内没有分区 " + partition
                            + "，可用分区：" + names.toString().trim());
                }
                final long total = target.size;
                File tempDir = new File(getCacheDir(), "rom_extract");
                if (!tempDir.exists() && !tempDir.mkdirs()) {
                    throw new IOException("无法创建临时目录");
                }
                mainHandler.post(() -> {
                    progressBar.setProgress(0);
                    progressBar.setVisibility(View.VISIBLE);
                });
                PayloadCore.extractPartition(session, partition, tempDir.getAbsolutePath(),
                        (bytes, outPath) -> mainHandler.post(() -> {
                            int pct = total > 0 ? (int) Math.min(100, bytes * 100 / total) : 0;
                            progressBar.setProgress(pct);
                            log("提取中：" + partition + " · 已写入 " + formatSize(bytes)
                                    + "（" + pct + "%）");
                        }));
                File tempFile = new File(tempDir, partition + ".img");
                String outName = buildFileName(device, verLabel + "_" + partition, ".img");
                File dir = VioletPaths.resolveDir(this, "ROM");
                File dstFile = new File(dir, outName);
                copyFile(tempFile, dstFile);
                File published = VioletPaths.publishToPublic(this, dstFile, "ROM");
                if (tempFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
                if (published != null && !published.equals(dstFile) && dstFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dstFile.delete();
                }
                final File finalFile = published != null ? published : dstFile;
                final String sizeText = formatSize(finalFile.length());
                mainHandler.post(() -> {
                    progressBar.setProgress(100);
                    log("提取完成 → " + finalFile.getAbsolutePath() + "（" + sizeText + "）");
                    Toast.makeText(this, "已保存到 " + finalFile.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                    setBusy(false);
                });
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                mainHandler.post(() -> {
                    log("提取失败：" + msg
                            + "\n若提示找不到 payload.bin，说明该包不是 OTA 全量包，"
                            + "请换版本或改用「下载整包」。");
                    progressBar.setVisibility(View.GONE);
                    setBusy(false);
                });
            } finally {
                if (session != null) {
                    try {
                        session.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------ 工具

    private String describe(LocalRomCatalog.Version v) {
        StringBuilder sb = new StringBuilder();
        sb.append("机型：").append(currentDeviceName()).append('\n');
        sb.append("版本：").append(v.label()).append('\n');
        if (v.size > 0) sb.append("大小：").append(formatSize(v.size)).append('\n');
        if (v.source != null && !v.source.isEmpty()) {
            sb.append("来源：").append(v.source).append('\n');
        }
        sb.append("直链：").append(v.url).append('\n');
        if (!v.extra.isEmpty()) {
            sb.append("\n同固件的其它分块：\n");
            for (Map.Entry<String, String> e : v.extra.entrySet()) {
                sb.append(" · ").append(e.getKey()).append("\n   ").append(e.getValue()).append('\n');
            }
        }
        if (v.note != null && !v.note.isEmpty()) sb.append("\n说明：").append(v.note).append('\n');
        if (v.payload) {
            sb.append("\n该包含 payload.bin，可点「提取 boot.img / init_boot.img」"
                    + "不下整包直接抠镜像。");
        }
        return sb.toString().trim();
    }

    private void setSpinner(Spinner spinner, List<String> items) {
        List<String> data = items == null || items.isEmpty()
                ? Collections.singletonList(PLACEHOLDER) : new ArrayList<>(items);
        // 内容没变就完全不动 —— 重建 adapter 会把用户刚选中的值抹回「全部」
        if (sameContent(spinner, data)) return;
        String keep = current(spinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, data);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // 重建后恢复原选中值；该项已被过滤掉才退回「全部」
        int idx = data.indexOf(keep);
        spinner.setSelection(idx >= 0 ? idx : 0);
    }

    /** 逐项比对，避免无谓重建。 */
    private static boolean sameContent(Spinner spinner, List<String> data) {
        ArrayAdapter<?> ad = (ArrayAdapter<?>) spinner.getAdapter();
        if (ad == null || ad.getCount() != data.size()) return false;
        for (int i = 0; i < data.size(); i++) {
            if (!data.get(i).equals(ad.getItem(i))) return false;
        }
        return true;
    }

    private String current(Spinner spinner) {
        Object item = spinner.getSelectedItem();
        if (item == null) return "";
        String value = String.valueOf(item).trim();
        return PLACEHOLDER.equals(value) ? "" : value;
    }

    private void setBusy(boolean value) {
        busy = value;
    }

    private void log(String message) {
        if (tvLog != null) tvLog.setText(message);
    }

    private void ensureChannel() {
        RomDownloadManager.get().init(this);
    }

    private static String buildFileName(String device, String version, String suffix) {
        String dev = device == null ? "" : device.trim().replaceAll("^\\[[^\\]]*\\]", "").trim();
        String name = dev;
        String ver = version == null ? "" : version.trim();
        if (!ver.isEmpty()) name = name + "_" + ver;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("_+$", "");
        return name + suffix;
    }

    private static String guessSuffix(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int cut = lower.indexOf('?');
        if (cut > 0) lower = lower.substring(0, cut);
        if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) return ".tgz";
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".7z")) return ".7z";
        if (lower.endsWith(".img")) return ".img";
        return ".bin";
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int i = (int) (Math.log(bytes) / Math.log(1024));
        i = Math.min(i, units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", bytes / Math.pow(1024, i), units[i]);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static final class SimpleSelected implements AdapterView.OnItemSelectedListener {
        private final Runnable action;

        SimpleSelected(Runnable action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            action.run();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
