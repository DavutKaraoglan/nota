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
import com.nota.model.Track;

import java.util.ArrayList;
import java.util.List;

/** Rows that stand for a collection (artist, folder, playlist) rather than a single track. */
public class GroupAdapter extends BaseAdapter {

    public static class Group {
        public String title;
        public String subtitle;
        public Track art;
        public int iconRes;
        public Object payload;

        public Group(String title, String subtitle, Track art, int iconRes, Object payload) {
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.iconRes = iconRes;
            this.payload = payload;
        }
    }

    private final Context ctx;
    private final ArtLoader art;
    private final int artPx;
    private List<Group> items;

    public GroupAdapter(Context ctx, List<Group> items) {
        this.ctx = ctx;
        this.items = items == null ? new ArrayList<Group>() : items;
        this.art = ArtLoader.get(ctx);
        this.artPx = ctx.getResources().getDimensionPixelSize(R.dimen.row_art);
    }

    public void setItems(List<Group> list) {
        this.items = list == null ? new ArrayList<Group>() : list;
        notifyDataSetChanged();
    }

    public int getCount() {
        return items.size();
    }

    public Group getItem(int position) {
        return items.get(position);
    }

    public long getItemId(int position) {
        return position;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.row_track, parent, false);
            TrackAdapter.Holder h = new TrackAdapter.Holder();
            h.art = (ImageView) v.findViewById(R.id.art);
            h.title = (TextView) v.findViewById(R.id.title);
            h.subtitle = (TextView) v.findViewById(R.id.subtitle);
            h.duration = (TextView) v.findViewById(R.id.duration);
            Ui.round(h.art, ctx.getResources().getDimension(R.dimen.art_radius));
            v.setTag(h);
        }
        TrackAdapter.Holder h = (TrackAdapter.Holder) v.getTag();
        Group g = items.get(position);

        h.title.setTextColor(ctx.getColor(R.color.text_primary));
        h.title.setText(g.title);
        h.subtitle.setText(g.subtitle);
        h.duration.setText("");

        int pad = Ui.dp(ctx, 10);
        if (g.art != null) {
            art.bind(h.art, g.art, artPx, g.iconRes, pad);
        } else {
            h.art.setTag(null);
            h.art.setPadding(pad, pad, pad, pad);
            h.art.setImageResource(g.iconRes);
        }
        return v;
    }
}
