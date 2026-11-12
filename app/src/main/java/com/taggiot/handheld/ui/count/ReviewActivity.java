package com.taggiot.handheld.ui.count;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.databinding.ActivityReviewBinding;
import com.taggiot.handheld.net.ApiException;
import com.taggiot.handheld.net.Gateway;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.FinderCache;
import com.taggiot.handheld.ui.find.FindTagActivity;
import com.taggiot.handheld.util.AppExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Review supervisor (counts/review → differences → resolve → reopen / close).
 * Review inilah yang menghasilkan event count_review ke GMF: found/expected (mis. 86/87),
 * alat yang hilang dengan nama, jumlah extra dan unknown. found = expected − missing.
 */
public class ReviewActivity extends BaseActivity {

    private static final String EXTRA_ID = "id";
    private static final int PAGE = 200, MAX_PAGES = 10;

    private ActivityReviewBinding b;
    private DiffAdapter adapter;
    private FinderCache finder;
    private String id;
    private Dtos.CountSession session;
    private final List<Dtos.Difference> diffs = new ArrayList<>();

    public static Intent intent(Context c, String id) { return new Intent(c, ReviewActivity.class).putExtra(EXTRA_ID, id); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.get(this).isSupervisor()) { toast("Review hanya untuk supervisor."); finish(); return; }
        b = ActivityReviewBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);
        id = getIntent().getStringExtra(EXTRA_ID);
        setupTopBar(b.topBar.getRoot(), "Review", "", R.drawable.ic_sync, v -> load());
        finder = new FinderCache(this, new FinderCache.Listener() {
            @Override public void onResolved() { adapter.notifyDataSetChanged(); }
            @Override public void onError(ApiException e) { if (e.needsRelogin()) apiError(e); }
        });
        adapter = new DiffAdapter(this::onDiff, finder);
        b.rvDiffs.setLayoutManager(new LinearLayoutManager(this));
        b.rvDiffs.setAdapter(adapter);
        b.btnReopen.setOnClickListener(v -> reopen());
        b.btnClose.setOnClickListener(v -> closeOrReview());
        load();
    }

    private void load() {
        setTopBarSubtitle(b.topBar.getRoot(), "Memuat…");
        AppExecutors.netThenMain(() -> {
            Gateway gw = Gateways.get(this);
            Dtos.CountSession s = gw.countGet(id);
            Dtos.CountSession old = Repo.get(this).cachedSession(id);
            if (s.area == null && old != null) s.area = old.area;
            Repo.get(this).cacheSession(s);
            List<Dtos.Difference> all = new ArrayList<>();
            if (!"open".equals(s.status)) {
                String cursor = null;
                for (int i = 0; i < MAX_PAGES; i++) {
                    Dtos.DifferencesResponse r = gw.countDifferences(id, null, null, cursor, PAGE);
                    if (r == null || r.items == null) break;
                    all.addAll(r.items);
                    cursor = r.nextCursor;
                    if (cursor == null) break;
                }
            }
            session = s;
            return all;
        }, (all, err) -> {
            if (err != null) { toast(apiError(err)); setTopBarSubtitle(b.topBar.getRoot(), "Gagal memuat"); return; }
            diffs.clear();
            diffs.addAll(all);
            Collections.sort(diffs, (x, y) -> Integer.compare(rank(x.kind), rank(y.kind)));
            List<String> epcs = new ArrayList<>();
            for (Dtos.Difference d : diffs) if (d.epc != null) epcs.add(d.epc);
            finder.request(epcs);
            render();
        });
    }

    private static int rank(String kind) {
        switch (kind == null ? "" : kind) {
            case "missing": return 0;
            case "short": return 1;
            case "extra": return 2;
            case "area_conflict": return 3;
            case "over": return 4;
            default: return 5;
        }
    }

    private void render() {
        Dtos.CountSession s = session;
        String area = CountUi.area(s);
        setupTopBar(b.topBar.getRoot(), "Review " + s.number, CountUi.kindLabel(s) + (area == null ? "" : " · laci " + area), R.drawable.ic_sync, v -> load());
        int missing = 0, extra = 0, unknown = 0;
        for (Dtos.Difference d : diffs) {
            if ("missing".equals(d.kind)) missing++;
            else if ("extra".equals(d.kind) || "over".equals(d.kind) || "area_conflict".equals(d.kind)) extra++;
            else if ("unknown".equals(d.kind)) unknown++;
        }
        int expected = CountUi.expected(s.tally);
        if (expected > 0) {
            b.tvResult.setText((expected - missing) + "/" + expected);
            b.tvResultLabel.setText(R.string.review_found);
        } else {
            b.tvResult.setText(String.valueOf(CountUi.distinct(s.tally)));
            b.tvResultLabel.setText("EPC unik terhitung");
        }
        b.tvMissing.setText(String.valueOf(missing));
        b.tvExtra.setText(String.valueOf(extra));
        b.tvUnknown.setText(String.valueOf(unknown));
        b.tvStatus.setText(statusLabel(s.status));
        adapter.submit(new ArrayList<>(diffs));
        b.tvEmpty.setVisibility(diffs.isEmpty() ? View.VISIBLE : View.GONE);
        b.tvEmpty.setText("open".equals(s.status) ? "Sesi masih terbuka. Tekan Review sekarang untuk menghitung selisih." : getString(R.string.review_empty));

        boolean review = "review".equals(s.status), open = "open".equals(s.status);
        b.sheet.setVisibility(review || open ? View.VISIBLE : View.GONE);
        b.btnReopen.setVisibility(review ? View.VISIBLE : View.GONE);
        b.btnClose.setText(open ? "Review sekarang" : getString(R.string.review_close));
        if (s.comment != null && !review && !open) b.tvNote.setText("Catatan: " + s.comment);
    }

    private static String statusLabel(String st) {
        switch (st == null ? "" : st) {
            case "open": return "Terbuka";
            case "review": return "Review";
            case "closed": return "Ditutup";
            case "cancelled": return "Dibatalkan";
            default: return st;
        }
    }

    // ---------- aksi ----------

    private void onDiff(Dtos.Difference d) {
        boolean review = session != null && "review".equals(session.status);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (review) {
            labels.add("Terima selisih (accepted)"); acts.add(() -> resolve(d, "accepted"));
            labels.add("Tandai hitung ulang (recount)"); acts.add(() -> resolve(d, "recount"));
            if (!"open".equals(d.resolution)) { labels.add("Kembalikan ke terbuka"); acts.add(() -> resolve(d, "open")); }
        }
        if (d.epc != null) { labels.add("Cari dengan handheld"); acts.add(() -> startActivity(FindTagActivity.intent(this, new String[]{d.epc}))); }
        if (labels.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(DiffAdapter.name(d, d.epc == null ? null : finder.get(d.epc)))
                .setItems(labels.toArray(new String[0]), (dlg, w) -> acts.get(w).run())
                .show();
    }

    private void resolve(Dtos.Difference d, String resolution) {
        askText("Catatan resolusi", "Mis. alat dipinjam WO 1234", 1, c ->
                AppExecutors.netThenMain(() -> Gateways.get(this).countResolve(id, d.id, resolution, c), (res, err) -> {
                    if (err != null) { beeper.error(); toast(apiError(err)); return; }
                    for (int i = 0; i < diffs.size(); i++) if (diffs.get(i).id.equals(res.id)) diffs.set(i, res);
                    render();
                }));
    }

    private void reopen() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Buka lagi untuk hitung ulang?")
                .setMessage("Sesi kembali terbuka; operator bisa membaca laci lagi, lalu review ulang.")
                .setPositiveButton("Buka lagi", (dlg, w) -> AppExecutors.netThenMain(() -> {
                    Dtos.CountSession s = Gateways.get(this).countReopen(id);
                    Dtos.CountSession old = Repo.get(this).cachedSession(id);
                    if (s.area == null && old != null) s.area = old.area;
                    Repo.get(this).cacheSession(s);
                    return s;
                }, (s, err) -> {
                    if (err != null) { toast(apiError(err)); return; }
                    startActivity(CountSessionActivity.intent(this, id));
                    finish();
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void closeOrReview() {
        if (session == null) return;
        if ("open".equals(session.status)) {
            AppExecutors.netThenMain(() -> Gateways.get(this).countReview(id), (r, err) -> {
                if (err != null) { toast(apiError(err)); return; }
                load();
            });
            return;
        }
        int open = 0;
        for (Dtos.Difference d : diffs) if ("open".equals(d.resolution)) open++;
        String warn = open > 0 ? open + " selisih belum diselesaikan dan tetap tercatat sebagai belum selesai. " : "";
        askText("Tutup " + session.number, warn + "Catatan penutupan (3–500 karakter)", 3, c ->
                AppExecutors.netThenMain(() -> {
                    Dtos.CountSession s = Gateways.get(this).countClose(id, c);
                    Repo.get(this).cacheSession(s);
                    return s;
                }, (s, err) -> {
                    if (err != null) { beeper.error(); toast(apiError(err)); return; }
                    beeper.success();
                    toast(s.number + " ditutup; laporan dibekukan");
                    finish();
                }));
    }

    interface OnText { void accept(String s); }

    private void askText(String title, String hint, int min, OnText ok) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        FrameLayout box = new FrameLayout(this);
        int p = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String c = et.getText().toString().trim();
                    if (c.length() < min) { toast("Catatan minimal " + min + " karakter"); return; }
                    ok.accept(c);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override protected void onDestroy() {
        if (finder != null) finder.stop();
        super.onDestroy();
    }
}
