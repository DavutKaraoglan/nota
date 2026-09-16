package com.nota.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Db;
import com.nota.model.Track;

import java.util.ArrayList;
import java.util.List;

public class TrackAdapter extends BaseAdapter {

    private final Context ctx;
    private final ArtLoader art;
    private final int artPx;
    private List<Track> items;
    private String activeKey;
    /** Album detail lists show a track number instead of repeating the album name. */
    private boolean numbered;

    public TrackAdapter(Context ctx, List<Track> items) {
        this.ctx = ctx;
        this.items = items == null ? new ArrayList<Track>() : items;
        this.art = ArtLoader.get(ctx);
        this.artPx = ctx.getResources().getDimensionPixelSize(R.dimen.row_art);
    }

    public void setItems(List<Track> list) {
        this.items = list == null ? new ArrayList<Track>() : list;
        notifyDataSetChanged();
    }

    public void setNumbered(boolean numbered) {
        this.numbered = numbered;
    }

    public void setActiveKey(String key) {
        if (key == null ? activeKey == null : key.equals(activeKey)) return;
        activeKey = key;
        notifyDataSetChanged();
    }

    public List<Track> items() {
        return items;
    }

    public int getCount() {
        return items.size();
    }

    public Track getItem(int position) {
        return items.get(position);
    }

    public long getItemId(int position) {
        return position;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.row_track, parent, false);
            Holder h = new Holder();
            h.art = (ImageView) v.findViewById(R.id.art);
            h.title = (TextView) v.findViewById(R.id.title);
            h.subtitle = (TextView) v.findViewById(R.id.subtitle);
            h.duration = (TextView) v.findViewById(R.id.duration);
            h.number = (TextView) v.findViewById(R.id.number);
            h.fav = (ImageView) v.findViewById(R.id.fav);
            h.fav.setColorFilter(ctx.getColor(R.color.accent));
            Ui.round(h.art, ctx.getResources().getDimension(R.dimen.art_radius));
            v.setTag(h);
        }
        Holder h = (Holder) v.getTag();
        Track t = items.get(position);

        h.title.setText(t.title);
        h.subtitle.setText(subtitleFor(t));
        h.duration.setText(Ui.duration(t.durationMs));
        h.fav.setVisibility(Db.get(ctx).isFavorite(t.key()) ? View.VISIBLE : View.GONE);

        boolean active = activeKey != null && activeKey.equals(t.key());
        h.title.setTextColor(ctx.getColor(active ? R.color.accent : R.color.text_primary));

        if (numbered) {
            h.art.setTag(null);
            h.art.setVisibility(View.INVISIBLE);
            h.number.setVisibility(View.VISIBLE);
            h.number.setText(t.trackNo > 0 ? String.valueOf(t.trackNo)
                    : String.valueOf(position + 1));
            h.number.setTextColor(ctx.getColor(active ? R.color.accent : R.color.text_secondary));
        } else {
            h.number.setVisibility(View.GONE);
            h.art.setVisibility(View.VISIBLE);
            art.bind(h.art, t, artPx, R.drawable.ic_note, Ui.dp(ctx, 10));
        }
        return v;
    }

    private String subtitleFor(Track t) {
        String artist = Ui.artistOr(ctx, t.artist);
        if (numbered) return artist;
        String album = t.album;
        return album == null ? artist : artist + " · " + album;
    }

    static class Holder {
        ImageView art, fav;
        TextView title, subtitle, duration, number;
    }
}
