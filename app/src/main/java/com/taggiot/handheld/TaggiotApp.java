package com.taggiot.handheld;

import android.app.Application;

import com.taggiot.handheld.data.Prefs;
import com.taggiot.handheld.net.Gateways;
import com.taggiot.handheld.reader.ReaderManager;
import com.taggiot.handheld.sync.SyncWorker;
import com.taggiot.handheld.util.AppExecutors;

public class TaggiotApp extends Application {

    @Override public void onCreate() {
        super.onCreate();
        AppExecutors.db(() -> {
            // Reader simulasi: tag "di lapangan" = isi laci D3 skenario demo (hilang 1, nyasar 1, tidak dikenal 1)
            if (Prefs.get(this).demoMode()) ReaderManager.get(this).setFakePopulation(Gateways.demo(this).fieldEpcs("D3"));
        });
        SyncWorker.schedulePeriodic(this);
    }
}
