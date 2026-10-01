package com.nota.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/** One screen inside MainActivity. Pages are stacked so the mini player never unmounts. */
public abstract class Page {

    protected MainActivity host;
    private View root;

    final View attach(MainActivity host, ViewGroup parent) {
        this.host = host;
        if (root == null) {
            root = onCreateView(LayoutInflater.from(host), parent);
        }
        return root;
    }

    public final View root() {
        return root;
    }

    /**
     * Whether this page is the one on screen. A page under the stack keeps listening, and what
     * it paints on the window itself — the colour wash behind the top bar — belongs to whichever
     * page the listener can actually see.
     */
    public final boolean visible() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    protected abstract View onCreateView(LayoutInflater inflater, ViewGroup parent);

    /** Title shown in the top bar. */
    public abstract String title();

    public boolean showsBack() {
        return false;
    }

    /** True for a page that carries its own bar inside its content, so the shared one gives way. */
    public boolean hidesTopBar() {
        return false;
    }

    public boolean hasSearch() {
        return false;
    }

    public boolean hasOverflow() {
        return false;
    }

    /** Settings has no tab of its own, so the pages that own it show a gear in the top bar. */
    public boolean hasSettings() {
        return false;
    }

    public void onShow() {
    }

    public void onHide() {
    }

    /**
     * The connection came or went. A page shows what can be opened now, and a question the
     * catalogue could not answer is remembered as an empty answer, so neither side of this
     * survives on its own.
     */
    public void onOnline(boolean online) {
    }

    /** Return true if the page consumed the back press. */
    public boolean onBack() {
        return false;
    }

    public void onOverflow(View anchor) {
    }

    public void onSearch(String query) {
    }

    public void onDestroy() {
    }
}
