package com.taggiot.handheld.ui.count;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.data.db.CountSessionEntity;
import com.taggiot.handheld.databinding.ActivityTaskListBinding;
import com.taggiot.handheld.net.Gateway;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.reader.BarcodeReceiver;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.BottomNav;
import com.taggiot.handheld.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;

/**
 * Daftar sesi hitung dari Site Gateway (POST /handheld/counts/list): drawer check yang dibuat GMF,
 * stock opname, dan inventory count. Operator+ saja. Scan barcode label laci membuka sesi terbuka untuk laci itu.
 */
public class CountListActivity extends BaseActivity {

    private static final int PAGE = 200, MAX_PAGES = 5;

    private ActivityTaskListBinding b;
    private CountAdapter adapter;
    private List<CountSessionEntity> all = new ArrayList<>();
    private int segment;   // 0 terbuka, 1 review, 2 selesai
    private int pendingRows;
    private BarcodeReceiver barcode;
    private boolean loading;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.get(this).canCount()) { toast("Peran viewer hanya bisa memakai Cari Tag dan Scan."); finish(); return; }
        b = ActivityTaskListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);
        setupTopBar(b.topBar.getRoot(), getString(R.string.tasks_title), getString(R.string.tasks_sub), R.drawable.ic_plus, v -> create());
        BottomNav.bind(this, b.bottomNav.getRoot(), BottomNav.Tab.TASKS);

        adapter = new CountAdapter(this::open, Session.get(this).isSupervisor());
        b.rvTasks.setLayoutManager(new LinearLayoutManager(this));
        b.rvTasks.setAdapter(adapter);
        b.segActive.setOnClickListener(v -> setSegment(0));
        b.segApproval.setOnClickListener(v -> setSegment(1));
        b.segDone.setOnClickListener(v -> setSegment(2));
        setSegment(0);

        Repo.get(this).dao().sessionsLive().observe(this, list -> { all = list == null ? new ArrayList<>() : list; render(); });
        Repo.get(this).dao().pendingRowsLive().observe(this, n -> { pendingRows = n == null ? 0 : n; render(); });
    }

    @Override protected void onResume() {
        super.onResume();
        if (b == null) return;
        refresh();
        barcode = BarcodeReceiver.register(this, data -> openByArea(data.trim().toUpperCase()));
    }

    @Override protected void onPause() {
        BarcodeReceiver.unregister(this, barcode);
        barcode = null;
        super.onPause();
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        setTopBarSubtitle(b.topBar.getRoot(), "Memuat dari Site Gateway…");
        AppExecutors.netThenMain(() -> {
            Gateway gw = Gateways.get(this);
            List<Dtos.CountSession> out = new ArrayList<>();
            String cursor = null;
            for (int i = 0; i < MAX_PAGES; i++) {
                Dtos.CountListResponse page = gw.countList(null, null, cursor, PAGE);
                if (page == null || page.items == null) break;
                out.addAll(page.items);
                cursor = page.nextCursor;
                if (cursor == null) break;
            }
            Repo.get(this).cacheSessions(out, true);
            return out.size();
        }, (n, err) -> {
            loading = false;
            if (err != null) {
                setTopBarSubtitle(b.topBar.getRoot(), "Offline · data tersimpan di perangkat");
                String msg = apiError(err);
                if (!(err instanceof com.taggiot.handheld.net.ApiException) || !((com.taggiot.handheld.net.ApiException) err).isNetwork()) toast(msg);
                return;
            }
            setTopBarSubtitle(b.topBar.getRoot(), getString(R.string.tasks_sub));
        });
    }

    private void setSegment(int s) {
        segment = s;
        b.segActive.setSelected(s == 0);
        b.segApproval.setSelected(s == 1);
        b.segDone.setSelected(s == 2);
        render();
    }

    private void render() {
        List<CountSessionEntity> shown = new ArrayList<>();
        int open = 0, review = 0;
        for (CountSessionEntity e : all) {
            if ("open".equals(e.status)) open++;
            if ("review".equals(e.status)) review++;
            boolean done = "closed".equals(e.status) || "cancelled".equals(e.status);
            if ((segment == 0 && "open".equals(e.status)) || (segment == 1 && "review".equals(e.status)) || (segment == 2 && done)) shown.add(e);
        }
        b.tvStatOpen.setText(String.valueOf(open));
        b.tvStatApproval.setText(String.valueOf(review));
        b.tvStatLocations.setText(String.valueOf(pendingRows));
        adapter.submit(shown);
        b.tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(Dtos.CountSession s) {
        if ("review".equals(s.status) || "closed".equals(s.status) || "cancelled".equals(s.status)) {
            if (Session.get(this).isSupervisor()) { startActivity(ReviewActivity.intent(this, s.id)); return; }
            if ("review".equals(s.status)) {
                info(s.number, "Sesi sedang di-review supervisor. Penghitungan berhenti sampai supervisor membukanya lagi.");
                return;
            }
        }
        startActivity(CountSessionActivity.intent(this, s.id));
    }

    /** Scan label laci → buka drawer check terbuka untuk laci itu. */
    private void openByArea(String code) {
        for (CountSessionEntity e : all) {
            Dtos.CountSession s = Repo.parse(e);
            if (s != null && "open".equals(s.status) && code.equalsIgnoreCase(CountUi.area(s))) { beeper.tick(); open(s); return; }
        }
        beeper.error();
        toast("Tidak ada sesi terbuka untuk laci " + code + ". Mulai drawer check di GMF, lalu muat ulang.");
    }

    // ---------- buat sesi ----------

    private void create() {
        boolean sup = Session.get(this).isSupervisor();
        String[] labels = sup
                ? new String[]{"Inventory count (hitung bebas area)", "Drawer check / stock opname (supervisor)"}
                : new String[]{"Inventory count (hitung bebas area)"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Mulai sesi hitung baru")
                .setItems(labels, (d, which) -> askArea(which == 1 ? "opname" : "inventory"))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void askArea(String kind) {
        boolean opname = "opname".equals(kind);
        EditText et = new EditText(this);
        et.setHint(opname ? "Kode laci GMF, mis. D3" : "Area, mis. Rak A3 (opsional)");
        et.setInputType(InputType.TYPE_CLASS_TEXT | (opname ? InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS : 0));
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(opname ? 16 : 60)});
        if (!opname) et.setText(prefs.lastArea());
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(this)
                .setTitle(opname ? "Drawer check" : "Inventory count")
                .setMessage(opname
                        ? "Biasanya drawer check dimulai di GMF. Bila dimulai di sini, kode laci HARUS sama persis dengan kode laci di GMF agar hasilnya masuk ke laci itu."
                        : "Hitung bebas tanpa daftar harapan.")
                .setView(box)
                .setPositiveButton("Mulai", (d, w) -> {
                    String area = et.getText().toString().trim();
                    if (opname) area = area.toUpperCase();
                    if (opname && area.isEmpty()) { toast("Kode laci wajib untuk drawer check"); return; }
                    doCreate(kind, area.isEmpty() ? null : area);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doCreate(String kind, String area) {
        Dtos.CountCreateRequest req = new Dtos.CountCreateRequest();
        req.kind = kind;
        req.area = area;
        if ("opname".equals(kind)) req.expectedSource = "items";
        if (area != null) prefs.setLastArea(area);
        AppExecutors.netThenMain(() -> {
            Dtos.CountSession s = Gateways.get(this).countCreate(req);
            if (s.area == null) s.area = area;     // simpan area lokal bila server tidak mengembalikannya
            Repo.get(this).cacheSession(s);
            return s;
        }, (s, err) -> {
            if (err != null) { beeper.error(); toast(apiError(err)); return; }
            beeper.success();
            toast("Sesi " + s.number + " dibuat");
            startActivity(CountSessionActivity.intent(this, s.id));
        });
    }

    private void info(String title, String msg) {
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg).setPositiveButton(R.string.ok, null).show();
    }

    public static Intent intent(android.content.Context c) { return new Intent(c, CountListActivity.class); }
}
