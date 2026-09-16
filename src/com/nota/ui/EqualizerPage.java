package com.nota.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.nota.R;
import com.nota.player.Fx;
import com.nota.player.Playback;

public class EqualizerPage extends Page {

    private Fx fx;
    private LinearLayout bandBox, extrasBox;
    private TextView presetChip, note;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        fx = Fx.get(host);
        fx.attach(Playback.get(host).audioSessionId());

        View root = inflater.inflate(R.layout.view_equalizer, parent, false);
        bandBox = (LinearLayout) root.findViewById(R.id.fx_bands);
        extrasBox = (LinearLayout) root.findViewById(R.id.fx_extras);
        presetChip = (TextView) root.findViewById(R.id.fx_preset);
        note = (TextView) root.findViewById(R.id.fx_note);

        Switch toggle = (Switch) root.findViewById(R.id.fx_switch);
        toggle.setChecked(fx.isEnabled());
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                fx.setEnabled(checked);
            }
        });

        presetChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickPreset();
            }
        });

        buildBands();
        buildExtras();
        updatePresetLabel();
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.equalizer);
    }

    @Override
    public boolean showsBack() {
        return true;
    }

    private void buildBands() {
        bandBox.removeAllViews();
        if (!fx.available()) {
            note.setVisibility(View.VISIBLE);
            note.setText(R.string.nothing_playing);
            presetChip.setVisibility(View.GONE);
            return;
        }
        note.setVisibility(View.GONE);
        presetChip.setVisibility(View.VISIBLE);

        short[] range = fx.levelRange();
        short[] levels = fx.bandLevels();
        short count = fx.bandCount();
        for (short i = 0; i < count; i++) {
            final short band = i;
            int hz = fx.centerFreqHz(i);
            final TextView label = new TextView(host);
            label.setTextSize(13);
            label.setTextColor(host.getColor(R.color.text_secondary));
            label.setText(freqLabel(hz) + "   " + dbLabel(levels[i]));

            SeekBar bar = new SeekBar(host);
            bar.setMax(range[1] - range[0]);
            bar.setProgress(levels[i] - range[0]);
            final short min = range[0];
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                    short level = (short) (value + min);
                    label.setText(label.getText().toString().split("   ")[0]
                            + "   " + dbLabel(level));
                    if (fromUser) {
                        fx.setBandLevel(band, level);
                        updatePresetLabel();
                    }
                }

                public void onStartTrackingTouch(SeekBar s) {
                }

                public void onStopTrackingTouch(SeekBar s) {
                }
            });

            bandBox.addView(label);
            bandBox.addView(bar);
        }
    }

    private void buildExtras() {
        extrasBox.removeAllViews();
        addSlider(R.string.equalizer_bass, 1000, fx.bassStrength(), new OnValue() {
            public void set(int value) {
                fx.setBassStrength(value);
            }
        });
        addSlider(R.string.equalizer_virtual, 1000, fx.virtualizerStrength(), new OnValue() {
            public void set(int value) {
                fx.setVirtualizerStrength(value);
            }
        });
        addSlider(R.string.equalizer_loudness, 1500, fx.loudnessGain(), new OnValue() {
            public void set(int value) {
                fx.setLoudnessGain(value);
            }
        });
    }

    private interface OnValue {
        void set(int value);
    }

    private void addSlider(int labelRes, int max, int value, final OnValue sink) {
        TextView label = new TextView(host);
        label.setTextSize(13);
        label.setTextColor(host.getColor(R.color.text_secondary));
        label.setText(labelRes);
        label.setPadding(0, Ui.dp(host, 10), 0, 0);

        SeekBar bar = new SeekBar(host);
        bar.setMax(max);
        bar.setProgress(Math.min(max, value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (fromUser) sink.set(v);
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        extrasBox.addView(label);
        extrasBox.addView(bar);
    }

    private void pickPreset() {
        final String[] presets = fx.presetNames();
        if (presets.length == 0) return;
        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.equalizer_preset)
                .setItems(presets, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        fx.usePreset(which);
                        buildBands();
                        updatePresetLabel();
                    }
                })
                .show();
    }

    private void updatePresetLabel() {
        int index = fx.preset();
        String[] presets = fx.presetNames();
        String name = index >= 0 && index < presets.length
                ? presets[index] : host.getString(R.string.equalizer_custom);
        presetChip.setText(host.getString(R.string.equalizer_preset) + ": " + name);
    }

    private static String freqLabel(int hz) {
        return hz >= 1000 ? (hz / 1000) + " kHz" : hz + " Hz";
    }

    private static String dbLabel(short millibels) {
        int db = millibels / 100;
        return (db > 0 ? "+" : "") + db + " dB";
    }
}
