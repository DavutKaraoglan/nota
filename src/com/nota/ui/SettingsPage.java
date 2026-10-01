package com.nota.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.nota.R;
import com.nota.data.DataSaver;
import com.nota.data.Db;
import com.nota.data.MediaLibrary;
import com.nota.data.Prefs;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.List;

public class SettingsPage extends Page {

    public static final String KEY_THEME = "theme";
    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    /** A language tag, or empty for whatever the phone itself is set to. */
    public static final String KEY_LANGUAGE = "language";
    private static final String[] LANGUAGES = {"", "tr", "en"};
    /** Each language named in itself, the way a speaker of it would look for it in a list. */
    private static final String[] LANGUAGE_NAMES = {null, "Türkçe", "English"};

    private static final int ROW_THEME = 0, ROW_LANGUAGE = 1, ROW_SLEEP = 2, ROW_EQ = 3,
            ROW_AUTOPLAY = 4, ROW_DATA = 5, ROW_TASTE = 6, ROW_CLEAR = 7, ROW_ABOUT = 8;

    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90};

    private final List<Integer> rows = new ArrayList<Integer>();
    private RowAdapter adapter;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        ListView list = (ListView) root.findViewById(R.id.list);

        rows.add(ROW_THEME);
        rows.add(ROW_LANGUAGE);
        rows.add(ROW_SLEEP);
        rows.add(ROW_EQ);
        rows.add(ROW_AUTOPLAY);
        rows.add(ROW_DATA);
        rows.add(ROW_TASTE);
        rows.add(ROW_CLEAR);
        rows.add(ROW_ABOUT);

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                onRow(rows.get(pos));
            }
        });
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_settings);
    }

    @Override
    public void onShow() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void onRow(int row) {
        switch (row) {
            case ROW_THEME:
                pickTheme();
                break;
            case ROW_LANGUAGE:
                pickLanguage();
                break;
            case ROW_SLEEP:
                pickSleep();
                break;
            case ROW_EQ:
                host.push(new EqualizerPage());
                break;
            case ROW_AUTOPLAY:
                Prefs.setBool(host, Playback.KEY_AUTOPLAY,
                        !Prefs.getBool(host, Playback.KEY_AUTOPLAY, true));
                adapter.notifyDataSetChanged();
                break;
            case ROW_DATA:
                showDataUsage();
                break;
            case ROW_TASTE:
                showTaste();
                break;
            case ROW_CLEAR:
                Db.get(host).clearHistory();
                Toast.makeText(host, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                break;
            default:
                new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                        .setTitle(R.string.about)
                        .setMessage(R.string.about_body)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                break;
        }
    }

    private void showDataUsage() {
        final DataSaver meter = DataSaver.get(host);
        String body = host.getString(R.string.data_used,
                DataSaver.format(meter.downloadedBytes()))
                + "\n" + host.getString(R.string.data_saved_detail,
                DataSaver.format(meter.savedBytes()), meter.savedPercent());
        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.data_saver)
                .setMessage(body + "\n\n" + host.getString(R.string.data_saver_note))
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.data_reset, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        meter.reset();
                        adapter.notifyDataSetChanged();
                    }
                })
                .show();
    }

    /** The only window onto what the recommender has learned, until it starts suggesting. */
    private void showTaste() {
        Db db = Db.get(host);
        StringBuilder body = new StringBuilder(host.getString(R.string.taste_counts,
                db.eventCount(), db.transitionCount()));
        List<Track> top = MediaLibrary.resolve(host, db.topScoredKeys(10));
        for (Track t : top) body.append("\n• ").append(t.title);
        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.taste)
                .setMessage(body.toString())
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void pickTheme() {
        final String[] labels = {
                host.getString(R.string.theme_system),
                host.getString(R.string.theme_light),
                host.getString(R.string.theme_dark),
        };
        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.theme)
                .setSingleChoiceItems(labels, Prefs.getInt(host, KEY_THEME, THEME_SYSTEM),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                if (which != Prefs.getInt(host, KEY_THEME, THEME_SYSTEM)) {
                                    Prefs.setInt(host, KEY_THEME, which);
                                    host.recreate();
                                }
                            }
                        })
                .show();
    }

    private void pickLanguage() {
        final String[] labels = new String[LANGUAGES.length];
        labels[0] = host.getString(R.string.theme_system);
        for (int i = 1; i < LANGUAGES.length; i++) labels[i] = LANGUAGE_NAMES[i];
        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.language)
                .setSingleChoiceItems(labels, chosenLanguage(),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                if (which == chosenLanguage()) return;
                                Prefs.setString(host, KEY_LANGUAGE, LANGUAGES[which]);
                                // The whole app is already drawn in the old language, and only
                                // being built again picks up the new one.
                                host.recreate();
                            }
                        })
                .show();
    }

    private int chosenLanguage() {
        String tag = Prefs.getString(host, KEY_LANGUAGE, "");
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(tag)) return i;
        }
        return 0;
    }

    private void pickSleep() {
        final String[] labels = new String[SLEEP_MINUTES.length];
        labels[0] = host.getString(R.string.sleep_off);
        for (int i = 1; i < SLEEP_MINUTES.length; i++) {
            labels[i] = host.getString(R.string.sleep_minutes, SLEEP_MINUTES[i]);
        }
        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.sleep_timer)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) Playback.get(host).cancelSleepTimer();
                        else Playback.get(host).setSleepTimer(SLEEP_MINUTES[which]);
                        adapter.notifyDataSetChanged();
                    }
                })
                .show();
    }

    private String subtitle(int row) {
        switch (row) {
            case ROW_THEME: {
                int mode = Prefs.getInt(host, KEY_THEME, THEME_SYSTEM);
                return host.getString(mode == THEME_LIGHT ? R.string.theme_light
                        : mode == THEME_DARK ? R.string.theme_dark : R.string.theme_system);
            }
            case ROW_LANGUAGE: {
                int at = chosenLanguage();
                return at == 0 ? host.getString(R.string.theme_system) : LANGUAGE_NAMES[at];
            }
            case ROW_SLEEP: {
                long left = Playback.get(host).sleepRemainingMs();
                return left > 0 ? host.getString(R.string.sleep_active, Ui.duration(left))
                        : host.getString(R.string.sleep_off);
            }
            case ROW_AUTOPLAY:
                return host.getString(Prefs.getBool(host, Playback.KEY_AUTOPLAY, true)
                        ? R.string.autoplay_on : R.string.autoplay_off);
            case ROW_DATA: {
                DataSaver meter = DataSaver.get(host);
                return host.getString(R.string.data_saved_short,
                        DataSaver.format(meter.savedBytes()));
            }
            case ROW_TASTE:
                return host.getString(R.string.taste_short, Db.get(host).eventCount());
            case ROW_CLEAR:
                return "";
            case ROW_ABOUT:
                return "";
            default:
                return "";
        }
    }

    private int label(int row) {
        switch (row) {
            case ROW_THEME:
                return R.string.theme;
            case ROW_LANGUAGE:
                return R.string.language;
            case ROW_SLEEP:
                return R.string.sleep_timer;
            case ROW_EQ:
                return R.string.equalizer;
            case ROW_AUTOPLAY:
                return R.string.autoplay;
            case ROW_DATA:
                return R.string.data_saver;
            case ROW_TASTE:
                return R.string.taste;
            case ROW_CLEAR:
                return R.string.clear_history;
            default:
                return R.string.about;
        }
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int position) {
            return rows.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(host).inflate(R.layout.row_setting, parent, false);
            }
            int row = rows.get(position);
            ((TextView) v.findViewById(R.id.title)).setText(label(row));
            TextView sub = (TextView) v.findViewById(R.id.subtitle);
            String text = subtitle(row);
            sub.setText(text);
            sub.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
