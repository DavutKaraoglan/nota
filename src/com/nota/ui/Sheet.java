package com.nota.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nota.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's own action menu: a panel that rises from the bottom edge.
 *
 * <p>PopupMenu draws itself from the platform's widget theme rather than this app's, so the one
 * place the listener saw a foreign typeface, corner and colour was the menu they open most. A
 * panel at the bottom is also where a thumb already is, which a popup pinned to the row is not.
 */
public class Sheet {

    public interface OnPick {
        void picked(int id);
    }

    private static class Item {
        final int id;
        final int icon;
        final CharSequence label;

        Item(int id, int icon, CharSequence label) {
            this.id = id;
            this.icon = icon;
            this.label = label;
        }
    }

    private final Activity host;
    private final List<Item> items = new ArrayList<Item>();
    private View header;

    public Sheet(Activity host) {
        this.host = host;
    }

    /** An optional panel above the actions, naming what they will act on. */
    public Sheet header(View view) {
        header = view;
        return this;
    }

    public Sheet add(int id, int iconRes, CharSequence label) {
        items.add(new Item(id, iconRes, label));
        return this;
    }

    public Sheet add(int id, int iconRes, int labelRes) {
        return add(id, iconRes, host.getString(labelRes));
    }

    public void show(final OnPick pick) {
        final Dialog dialog = new Dialog(host, R.style.NotaTheme_Dialog);
        LayoutInflater inflater = LayoutInflater.from(host);
        View root = inflater.inflate(R.layout.sheet_menu, null, false);

        FrameLayout headerBox = (FrameLayout) root.findViewById(R.id.sheet_header);
        if (header == null) {
            headerBox.setVisibility(View.GONE);
        } else {
            headerBox.addView(header);
        }

        LinearLayout box = (LinearLayout) root.findViewById(R.id.sheet_items);
        for (Item item : items) {
            final int id = item.id;
            View row = inflater.inflate(R.layout.row_sheet_item, box, false);
            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            icon.setImageResource(item.icon);
            icon.setColorFilter(host.getColor(R.color.text_primary));
            ((TextView) row.findViewById(R.id.label)).setText(item.label);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dialog.dismiss();
                    pick.picked(id);
                }
            });
            box.addView(row);
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            // The dialog theme is sized and centred for an alert; a sheet is neither, so the
            // window is widened to the screen and pinned to the bottom edge by hand.
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(R.style.SheetAnimation);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
        }
        dialog.show();
    }
}
