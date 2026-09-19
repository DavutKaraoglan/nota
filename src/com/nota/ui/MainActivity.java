package com.nota.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.nota.R;
import android.app.AlertDialog;
import android.content.DialogInterface;

import com.nota.data.CrashLog;
import com.nota.data.DataSaver;
import com.nota.data.MediaLibrary;
import com.nota.data.Prefs;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    public static final int TAB_HOME = 0;
    public static final int TAB_DISCOVER = 1;
    public static final int TAB_LIBRARY = 2;
    public static final int TAB_HISTORY = 3;
    private static final int TAB_COUNT = 4;

    public static final String EXTRA_OPEN = "open";
    public static final String OPEN_EQUALIZER = "equalizer";
    public static final String OPEN_ARTIST = "artist";
    public static final String EXTRA_ARTIST = "artist_name";

    private static final int REQ_AUDIO = 1;
    private static final int REQ_NOTIFY = 2;

    private FrameLayout content;
    private View tint;
    private View topbar;
    private TextView titleView;
    private EditText searchField;
    private ImageButton btnBack, btnSearch, btnOverflow, btnSettings;
    private final View[] tabViews = new View[TAB_COUNT];
    private final Page[] tabPages = new Page[TAB_COUNT];
    private final List<Page> stack = new ArrayList<Page>();
    private int currentTab = TAB_HOME;
    private boolean searching;
    private MiniPlayer mini;

    /** Long enough that a word typed at speed asks the network once rather than per letter. */
    private static final long SEARCH_DEBOUNCE_MS = 300;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = new Runnable() {
        public void run() {
            // selectTab() stacks the new page before inflating it, and clearing the field
            // fires the watcher in between; a page with no views yet must not be called.
            Page p = current();
            if (p != null && p.root() != null) p.onSearch(searchField.getText().toString());
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        int mode = Prefs.getInt(base, SettingsPage.KEY_THEME, SettingsPage.THEME_SYSTEM);
        if (mode != SettingsPage.THEME_SYSTEM) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (mode == SettingsPage.THEME_DARK
                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.install(this);
        setContentView(R.layout.activity_main);

        content = (FrameLayout) findViewById(R.id.content);
        tint = findViewById(R.id.tint);
        topbar = findViewById(R.id.topbar);
        titleView = (TextView) findViewById(R.id.title);
        searchField = (EditText) findViewById(R.id.search_field);
        btnBack = (ImageButton) findViewById(R.id.btn_back);
        btnSearch = (ImageButton) findViewById(R.id.btn_search);
        btnOverflow = (ImageButton) findViewById(R.id.btn_overflow);
        btnSettings = (ImageButton) findViewById(R.id.btn_settings);

        setupTab(R.id.tab_home, TAB_HOME, R.drawable.ic_home, R.string.tab_home);
        setupTab(R.id.tab_discover, TAB_DISCOVER, R.drawable.ic_search, R.string.tab_discover);
        setupTab(R.id.tab_library, TAB_LIBRARY, R.drawable.ic_note, R.string.tab_library);
        setupTab(R.id.tab_history, TAB_HISTORY, R.drawable.ic_timer, R.string.tab_history);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressed();
            }
        });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (searching) exitSearch();
                else enterSearch();
            }
        });
        btnOverflow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                current().onOverflow(v);
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                push(new SettingsPage());
            }
        });
        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }

            public void afterTextChanged(Editable s) {
            }
        });

        DataSaver.get(this);
        mini = new MiniPlayer(this, (FrameLayout) findViewById(R.id.mini_holder));
        selectTab(TAB_HOME);

        if (hasAudioPermission(this)) {
            MediaLibrary.get().load(this, false);
        } else {
            requestAudioPermission();
        }
        requestNotificationPermission();
        showPendingCrash();
        handleOpenIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        String open = intent.getStringExtra(EXTRA_OPEN);
        if (OPEN_EQUALIZER.equals(open)) {
            intent.removeExtra(EXTRA_OPEN);
            selectTab(TAB_LIBRARY);
            push(new SettingsPage());
            push(new EqualizerPage());
        } else if (OPEN_ARTIST.equals(open)) {
            intent.removeExtra(EXTRA_OPEN);
            String name = intent.getStringExtra(EXTRA_ARTIST);
            if (name == null || name.length() == 0) return;
            Track cur = Playback.get(this).current();
            selectTab(TAB_HOME);
            push(new ArtistPage(name, cur != null && name.equals(cur.artist) ? cur : null));
        }
    }

    private void setupTab(int viewId, final int index, int iconRes, int labelRes) {
        View v = findViewById(viewId);
        tabViews[index] = v;
        ((ImageView) v.findViewById(R.id.tab_icon)).setImageResource(iconRes);
        ((TextView) v.findViewById(R.id.tab_label)).setText(labelRes);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (currentTab == index && stack.size() > 1) {
                    while (stack.size() > 1) popNoRefresh();
                    showCurrent();
                } else {
                    selectTab(index);
                }
            }
        });
    }

    private Page createTab(int index) {
        switch (index) {
            case TAB_DISCOVER:
                return new DiscoverPage();
            case TAB_LIBRARY:
                return new LibraryPage();
            case TAB_HISTORY:
                return new HistoryPage();
            default:
                return new HomePage();
        }
    }

    public void selectTab(int index) {
        while (stack.size() > 1) popNoRefresh();
        if (!stack.isEmpty()) stack.get(0).onHide();
        // Cleared while the outgoing page is still current, so it is the one that gets reset.
        exitSearch();
        if (tabPages[index] == null) tabPages[index] = createTab(index);
        stack.clear();
        stack.add(tabPages[index]);
        currentTab = index;
        for (int i = 0; i < tabViews.length; i++) {
            View v = tabViews[i];
            v.setSelected(i == index);
            ImageView icon = (ImageView) v.findViewById(R.id.tab_icon);
            icon.setColorFilter(getColor(i == index ? R.color.accent : R.color.text_secondary));
        }
        showCurrent();
    }

    public void push(Page page) {
        current().onHide();
        exitSearch();
        stack.add(page);
        showCurrent();
    }

    private void popNoRefresh() {
        Page p = stack.remove(stack.size() - 1);
        p.onHide();
        if (p.root() != null) content.removeView(p.root());
        p.onDestroy();
    }

    public boolean pop() {
        if (stack.size() <= 1) return false;
        popNoRefresh();
        showCurrent();
        return true;
    }

    private Page current() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void showCurrent() {
        Page p = current();
        View v = p.attach(this, content);
        if (v.getParent() == null) {
            content.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            c.setVisibility(c == v ? View.VISIBLE : View.GONE);
        }
        p.onShow();
        updateTopBar();
    }

    /** The colour wash a page paints behind the top bar and itself; null leaves the plain theme. */
    public void setTint(Drawable wash) {
        tint.setBackground(wash);
    }

    private void updateTopBar() {
        Page p = current();
        topbar.setVisibility(p.hidesTopBar() ? View.GONE : View.VISIBLE);
        titleView.setText(p.title());
        btnBack.setVisibility(p.showsBack() || stack.size() > 1 ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(p.hasSearch() ? View.VISIBLE : View.GONE);
        btnOverflow.setVisibility(p.hasOverflow() ? View.VISIBLE : View.GONE);
        btnSettings.setVisibility(p.hasSettings() ? View.VISIBLE : View.GONE);
    }

    private void enterSearch() {
        searching = true;
        titleView.setVisibility(View.GONE);
        searchField.setVisibility(View.VISIBLE);
        searchField.setText("");
        searchField.requestFocus();
        btnSearch.setImageResource(R.drawable.ic_close);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Home has no search of its own, so its button hands the job to Discover. */
    public void openDiscoverSearch() {
        selectTab(TAB_DISCOVER);
        enterSearch();
    }

    /** Fills the search bar as if the term had been typed, so the page reloads through onSearch. */
    public void searchFor(String term) {
        if (!searching) enterSearch();
        searchField.setText(term);
        searchField.setSelection(term.length());
        searchField.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void exitSearch() {
        searchHandler.removeCallbacks(searchRunnable);
        if (searching) {
            Page p = current();
            if (p != null && p.root() != null) p.onSearch("");
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
        searching = false;
        searchField.setVisibility(View.GONE);
        searchField.setText("");
        titleView.setVisibility(View.VISIBLE);
        btnSearch.setImageResource(R.drawable.ic_search);
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            exitSearch();
            return;
        }
        if (current().onBack()) return;
        if (pop()) return;
        if (currentTab != TAB_HOME) {
            selectTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mini != null) mini.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mini != null) mini.onPause();
        DataSaver.get(this).flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        searchHandler.removeCallbacks(searchRunnable);
        if (mini != null) mini.onDestroy();
        // Pages listen to playback, and playback outlives the activity, so a page never told to
        // let go keeps this activity and its whole view tree reachable. A theme change or a
        // rotation then leaves the old copy running behind the new one. Tab roots are held
        // outside the stack and are missed by pop(), so both lists are walked; stack.get(0) is
        // always the current tab's root, which is why the stack is walked from one.
        for (int i = 1; i < stack.size(); i++) stack.get(i).onDestroy();
        for (Page p : tabPages) {
            if (p != null) p.onDestroy();
        }
    }

    /** Lets pages refresh the top bar after changing their own title. */
    public void refreshTopBar() {
        updateTopBar();
    }

    /** The playlists screen is pushed rather than a tab now, so it is looked for in the stack. */
    public void refreshPlaylists() {
        for (Page p : stack) {
            if (p instanceof PlaylistsPage) ((PlaylistsPage) p).refresh();
        }
    }

    private void showPendingCrash() {
        final String report = CrashLog.exportPending(this);
        if (report == null) return;
        new AlertDialog.Builder(this, R.style.NotaTheme_Dialog)
                .setTitle(R.string.crash_title)
                .setMessage(report)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.crash_share, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, report), null));
                    }
                })
                .show();
    }

    // ---- permissions ----

    public static String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasAudioPermission(Context c) {
        return c.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestAudioPermission() {
        requestPermissions(new String[]{audioPermission()}, REQ_AUDIO);
    }

    /** Without this the playback notification is dropped and the service cannot go foreground. */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                MediaLibrary.get().load(this, true);
            }
            for (Page p : tabPages) {
                if (p instanceof LibraryPage) ((LibraryPage) p).refresh();
            }
        }
    }
}
