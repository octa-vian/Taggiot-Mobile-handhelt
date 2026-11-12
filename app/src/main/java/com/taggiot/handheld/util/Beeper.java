package com.taggiot.handheld.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Bunyi dan getar untuk umpan balik di gudang yang bising. */
public final class Beeper {
    private final ToneGenerator tone;
    private final Vibrator vibrator;
    private boolean enabled = true;

    public Beeper(Context ctx) {
        ToneGenerator t;
        try { t = new ToneGenerator(AudioManager.STREAM_MUSIC, 100); } catch (RuntimeException e) { t = null; }
        tone = t;
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setEnabled(boolean on) { enabled = on; }

    public void tick() { play(ToneGenerator.TONE_PROP_BEEP, 40); }
    public void success() { play(ToneGenerator.TONE_PROP_ACK, 120); vibrate(60); }
    public void error() { play(ToneGenerator.TONE_SUP_ERROR, 400); vibrate(300); }

    private void play(int type, int ms) {
        if (enabled && tone != null) tone.startTone(type, ms);
    }

    private void vibrate(int ms) {
        if (enabled && vibrator != null && vibrator.hasVibrator())
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    public void release() { if (tone != null) tone.release(); }
}
