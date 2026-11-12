package com.taggiot.handheld.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Session;
import com.taggiot.handheld.ui.count.CountListActivity;
import com.taggiot.handheld.ui.home.HomeActivity;
import com.taggiot.handheld.ui.inventory.InventoryActivity;
import com.taggiot.handheld.ui.settings.SettingsActivity;
import com.taggiot.handheld.ui.sync.SyncActivity;

/** Navigasi bawah mengambang (Beranda, Hitung, Scan, Sinkron, Profil). Tab Hitung hanya untuk operator+. */
public final class BottomNav {
    private BottomNav() {}

    public enum Tab { HOME, TASKS }

    public static void bind(Activity a, View root, Tab active) {
        highlight(a, root, R.id.navHomeIcon, R.id.navHomeLabel, active == Tab.HOME);
        highlight(a, root, R.id.navTasksIcon, R.id.navTasksLabel, active == Tab.TASKS);
        highlight(a, root, R.id.navSyncIcon, R.id.navSyncLabel, false);
        highlight(a, root, R.id.navProfileIcon, R.id.navProfileLabel, false);

        root.findViewById(R.id.navHome).setOnClickListener(v -> {
            if (active != Tab.HOME) go(a, HomeActivity.class, true);
        });
        View tasks = root.findViewById(R.id.navTasks);
        tasks.setAlpha(Session.get(a).canCount() ? 1f : 0.4f);
        tasks.setOnClickListener(v -> {
            if (!Session.get(a).canCount()) return;
            if (active != Tab.TASKS) go(a, CountListActivity.class, active == Tab.HOME);
        });
        root.findViewById(R.id.navScan).setOnClickListener(v -> go(a, InventoryActivity.class, false));
        root.findViewById(R.id.navSync).setOnClickListener(v -> go(a, SyncActivity.class, false));
        root.findViewById(R.id.navProfile).setOnClickListener(v -> go(a, SettingsActivity.class, false));
    }

    private static void highlight(Activity a, View root, int iconId, int labelId, boolean on) {
        int color = ContextCompat.getColor(a, on ? R.color.brand_300 : R.color.white_70);
        ImageView icon = root.findViewById(iconId);
        ImageViewCompat.setImageTintList(icon, android.content.res.ColorStateList.valueOf(color));
        TextView label = root.findViewById(labelId);
        label.setTextColor(color);
        label.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private static void go(Activity a, Class<?> cls, boolean clearTop) {
        Intent i = new Intent(a, cls);
        if (clearTop) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
    }
}
