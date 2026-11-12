package com.taggiot.handheld.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.data.db.AppDao;
import com.taggiot.handheld.databinding.ActivityHomeBinding;
import com.taggiot.handheld.reader.UhfReader;
import com.taggiot.handheld.ui.BaseActivity;
import com.taggiot.handheld.ui.BottomNav;
import com.taggiot.handheld.ui.UiUtil;
import com.taggiot.handheld.ui.count.CountListActivity;
import com.taggiot.handheld.ui.encode.EncodeActivity;
import com.taggiot.handheld.ui.find.FindTagActivity;
import com.taggiot.handheld.ui.inventory.InventoryActivity;
import com.taggiot.handheld.ui.sync.SyncActivity;

import java.util.Calendar;

/**
 * Beranda: operator & peran, perangkat, masa berlaku token; menu sesuai peran:
 * viewer → Cari Tag & Scan bebas; operator+ → Drawer check / Stock opname; supervisor non-GMF → Encode.
 */
public class HomeActivity extends BaseActivity {

    private ActivityHomeBinding b;
    private int openSessions, pendingBatches;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setStatusBar(false);

        Session s = Session.get(this);
        b.tvAvatar.setText(s.initials());
        b.tvName.setText(s.displayName());
        b.tvGreeting.setText(greeting());

        b.tileInventory.setOnClickListener(v -> startActivity(new Intent(this, InventoryActivity.class)));
        b.tileFind.setOnClickListener(v -> startActivity(new Intent(this, FindTagActivity.class)));
        b.tileEncode.setOnClickListener(v -> startActivity(new Intent(this, EncodeActivity.class)));
        b.tileCount.setOnClickListener(v -> startActivity(new Intent(this, CountListActivity.class)));
        b.btnBell.setOnClickListener(v -> startActivity(new Intent(this, SyncActivity.class)));
        b.cardActiveTask.setOnClickListener(v -> startActivity(new Intent(this, SyncActivity.class)));
        BottomNav.bind(this, b.bottomNav.getRoot(), BottomNav.Tab.HOME);

        AppDao dao = Repo.get(this).dao();
        dao.openSessionCountLive().observe(this, n -> { openSessions = n == null ? 0 : n; renderCountTile(); });
        dao.pendingCountLive().observe(this, n -> { pendingBatches = n == null ? 0 : n; renderSession(); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { moveTaskToBack(true); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (b == null) return;
        applyRole();
        renderSession();
        renderReader();
    }

    /** Tombol hanya untuk peran yang boleh: tidak ada tombol bind/encode di site GMF. */
    private void applyRole() {
        Session s = Session.get(this);
        boolean count = s.canCount();
        b.tileCount.setVisibility(count ? View.VISIBLE : View.GONE);
        boolean encode = !prefs.gmfSite() && s.isSupervisor();
        b.tileEncode.setVisibility(encode ? View.VISIBLE : View.INVISIBLE);
    }

    private void renderSession() {
        Session s = Session.get(this);
        b.tvNoTask.setVisibility(View.GONE);
        b.cardActiveTask.setVisibility(View.VISIBLE);
        b.tvTaskPct.setText(Session.roleLabel(s.role()));
        String hh = s.handheldName();
        b.tvTaskTitle.setText((hh.isEmpty() ? "Perangkat " + prefs.deviceId() : hh)
                + (prefs.demoMode() ? " · mode demo" : ""));
        long now = System.currentTimeMillis(), exp = s.expiresAt();
        long leftMin = Math.max(0, (exp - now) / 60_000);
        int pct = (int) Math.max(0, Math.min(100, 100 * (exp - now) / (12 * 3600_000L)));
        b.progressTask.setProgressCompat(pct, true);
        String meta = "Token berlaku s.d. " + UiUtil.time(exp) + " (" + (leftMin >= 60 ? (leftMin / 60) + " jam " : "") + (leftMin % 60) + " mnt)";
        if (pendingBatches > 0) meta += " · " + pendingBatches + " batch belum terkirim";
        b.tvTaskMeta.setText(meta);
    }

    private void renderCountTile() {
        b.tvCountSub.setText(openSessions > 0 ? openSessions + " sesi hitung terbuka" : "Drawer check & inventory count");
        if (openSessions > 0) {
            UiUtil.badge(b.tvCountBadge, UiUtil.Tone.INFO, openSessions + " terbuka");
            b.tvCountBadge.setCompoundDrawables(null, null, null, null);
            b.tvCountBadge.setVisibility(View.VISIBLE);
        } else {
            b.tvCountBadge.setVisibility(View.GONE);
        }
    }

    private void renderReader() {
        UhfReader r = readers.reader();
        boolean sim = readers.isSimulated();
        b.dotReader.setBackgroundResource(sim ? R.drawable.bg_dot_warn : R.drawable.bg_dot_ok);
        b.tvReaderStatus.setText(sim ? "Reader simulasi (tanpa hardware)" : r.name() + " siap");
        b.tvReaderInfo.setText(prefs.readPower() + " dBm");
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = h < 11 ? R.string.home_morning : h < 15 ? R.string.home_afternoon : h < 19 ? R.string.home_evening : R.string.home_night;
        return getString(res);
    }
}
