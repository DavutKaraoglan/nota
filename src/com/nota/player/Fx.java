package com.nota.player;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;

import com.nota.data.Prefs;

/**
 * Wraps the platform audio effects. Every effect is optional: several vendors ship devices
 * where one of these constructors throws, and a missing equalizer must not break playback.
 */
public class Fx {

    public static final String KEY_ENABLED = "fx_enabled";
    public static final String KEY_PRESET = "fx_preset";
    public static final String KEY_BANDS = "fx_bands";
    public static final String KEY_BASS = "fx_bass";
    public static final String KEY_VIRT = "fx_virt";
    public static final String KEY_LOUD = "fx_loud";

    /** Marks a hand-tuned curve that does not match any built-in preset. */
    public static final int PRESET_CUSTOM = -1;

    private static Fx instance;

    public static synchronized Fx get(Context c) {
        if (instance == null) instance = new Fx(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;
    private LoudnessEnhancer loud;
    private int session = -1;

    private Fx(Context app) {
        this.app = app;
    }

    public boolean isEnabled() {
        return Prefs.getBool(app, KEY_ENABLED, false);
    }

    public void setEnabled(boolean on) {
        Prefs.setBool(app, KEY_ENABLED, on);
        applyAll();
    }

    /** Rebuilds the effect chain when the MediaPlayer session changes. */
    public void attach(int sessionId) {
        if (sessionId == 0) return;
        if (session == sessionId && eq != null) {
            applyAll();
            return;
        }
        release();
        session = sessionId;
        try {
            eq = new Equalizer(1000, sessionId);
        } catch (Throwable t) {
            eq = null;
        }
        try {
            bass = new BassBoost(1000, sessionId);
        } catch (Throwable t) {
            bass = null;
        }
        try {
            virt = new Virtualizer(1000, sessionId);
        } catch (Throwable t) {
            virt = null;
        }
        try {
            loud = new LoudnessEnhancer(sessionId);
        } catch (Throwable t) {
            loud = null;
        }
        applyAll();
    }

    public void release() {
        if (eq != null) {
            try {
                eq.release();
            } catch (Throwable ignored) {
            }
            eq = null;
        }
        if (bass != null) {
            try {
                bass.release();
            } catch (Throwable ignored) {
            }
            bass = null;
        }
        if (virt != null) {
            try {
                virt.release();
            } catch (Throwable ignored) {
            }
            virt = null;
        }
        if (loud != null) {
            try {
                loud.release();
            } catch (Throwable ignored) {
            }
            loud = null;
        }
        session = -1;
    }

    public boolean available() {
        return eq != null;
    }

    public short bandCount() {
        try {
            return eq == null ? 0 : eq.getNumberOfBands();
        } catch (Throwable t) {
            return 0;
        }
    }

    public short[] levelRange() {
        try {
            return eq == null ? new short[]{-1500, 1500} : eq.getBandLevelRange();
        } catch (Throwable t) {
            return new short[]{-1500, 1500};
        }
    }

    public int centerFreqHz(short band) {
        try {
            return eq == null ? 0 : eq.getCenterFreq(band) / 1000;
        } catch (Throwable t) {
            return 0;
        }
    }

    public String[] presetNames() {
        try {
            if (eq == null) return new String[0];
            short n = eq.getNumberOfPresets();
            String[] out = new String[n];
            for (short i = 0; i < n; i++) out[i] = eq.getPresetName(i);
            return out;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    public int preset() {
        return Prefs.getInt(app, KEY_PRESET, PRESET_CUSTOM);
    }

    public void usePreset(int index) {
        Prefs.setInt(app, KEY_PRESET, index);
        if (eq != null && index >= 0) {
            try {
                eq.usePreset((short) index);
                short n = eq.getNumberOfBands();
                StringBuilder sb = new StringBuilder();
                for (short i = 0; i < n; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(eq.getBandLevel(i));
                }
                Prefs.setString(app, KEY_BANDS, sb.toString());
            } catch (Throwable ignored) {
            }
        }
        applyAll();
    }

    public short[] bandLevels() {
        short n = bandCount();
        short[] out = new short[n];
        String saved = Prefs.getString(app, KEY_BANDS, "");
        String[] parts = saved.length() == 0 ? new String[0] : saved.split(",");
        for (int i = 0; i < n; i++) {
            if (i < parts.length) {
                try {
                    out[i] = Short.parseShort(parts[i].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    public void setBandLevel(short band, short level) {
        short[] levels = bandLevels();
        if (band < 0 || band >= levels.length) return;
        levels[band] = level;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(levels[i]);
        }
        Prefs.setString(app, KEY_BANDS, sb.toString());
        Prefs.setInt(app, KEY_PRESET, PRESET_CUSTOM);
        if (eq != null) {
            try {
                eq.setBandLevel(band, level);
            } catch (Throwable ignored) {
            }
        }
    }

    public int bassStrength() {
        return Prefs.getInt(app, KEY_BASS, 0);
    }

    public void setBassStrength(int strength) {
        Prefs.setInt(app, KEY_BASS, strength);
        applyBass();
    }

    public int virtualizerStrength() {
        return Prefs.getInt(app, KEY_VIRT, 0);
    }

    public void setVirtualizerStrength(int strength) {
        Prefs.setInt(app, KEY_VIRT, strength);
        applyVirtualizer();
    }

    /** Extra gain in millibels, for quiet recordings. */
    public int loudnessGain() {
        return Prefs.getInt(app, KEY_LOUD, 0);
    }

    public void setLoudnessGain(int millibels) {
        Prefs.setInt(app, KEY_LOUD, millibels);
        applyLoudness();
    }

    private void applyAll() {
        boolean on = isEnabled();
        if (eq != null) {
            try {
                eq.setEnabled(on);
                if (on) {
                    short[] levels = bandLevels();
                    for (short i = 0; i < levels.length; i++) eq.setBandLevel(i, levels[i]);
                }
            } catch (Throwable ignored) {
            }
        }
        applyBass();
        applyVirtualizer();
        applyLoudness();
    }

    private void applyBass() {
        if (bass == null) return;
        try {
            int s = isEnabled() ? bassStrength() : 0;
            bass.setEnabled(s > 0);
            if (s > 0) bass.setStrength((short) Math.min(1000, s));
        } catch (Throwable ignored) {
        }
    }

    private void applyVirtualizer() {
        if (virt == null) return;
        try {
            int s = isEnabled() ? virtualizerStrength() : 0;
            virt.setEnabled(s > 0);
            if (s > 0) virt.setStrength((short) Math.min(1000, s));
        } catch (Throwable ignored) {
        }
    }

    private void applyLoudness() {
        if (loud == null) return;
        try {
            int g = isEnabled() ? loudnessGain() : 0;
            loud.setEnabled(g > 0);
            if (g > 0) loud.setTargetGain(g);
        } catch (Throwable ignored) {
        }
    }
}
