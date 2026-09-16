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
import com.nota.model.Album;

import java.util.ArrayList;
import java.util.List;

public class AlbumAdapter extends BaseAdapter {

    private final Context ctx;
    private final ArtLoader art;
    private final int artPx;
    private List<Album> items;

    public AlbumAdapter(Context ctx, List<Album> items) {
        this.ctx = ctx;
        this.items = items == null ? new ArrayList<Album>() : items;
        this.art = ArtLoader.get(ctx);
        this.artPx = Ui.dp(ctx, 180);
    }

    public void setItems(List<Album> list) {
        this.items = list == null ? new ArrayList<Album>() : list;
        notifyDataSetChanged();
    }

    public int getCount() {
        return items.size();
    }

    public Album getItem(int position) {
        return items.get(position);
    }

    public long getItemId(int position) {
        return position;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_album, parent, false);
            Holder h = new Holder();
            h.art = (ImageView) v.findViewById(R.id.art);
            h.title = (TextView) v.findViewById(R.id.title);
            h.subtitle = (TextView) v.findViewById(R.id.subtitle);
            Ui.round(h.art, Ui.dp(ctx, 10));
            v.setTag(h);
        }
        Holder h = (Holder) v.getTag();
        Album a = items.get(position);
        h.title.setText(Ui.albumOr(ctx, a.title));
        h.subtitle.setText(Ui.artistOr(ctx, a.artist));
        if (!a.tracks.isEmpty()) {
            art.bind(h.art, a.tracks.get(0), artPx, R.drawable.ic_album, Ui.dp(ctx, 34));
        }
        return v;
    }

    static class Holder {
        ImageView art;
        TextView title, subtitle;
    }
}
