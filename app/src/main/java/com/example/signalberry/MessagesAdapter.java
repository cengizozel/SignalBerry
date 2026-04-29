package com.example.signalberry;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

class MessagesAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Map<String, String>> data;

    MessagesAdapter(Context ctx, List<Map<String, String>> data) {
        this.ctx  = ctx;
        this.data = data;
    }

    @Override public int getCount()          { return data.size(); }
    @Override public Object getItem(int pos) { return data.get(pos); }
    @Override public long getItemId(int pos) { return pos; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null)
            convertView = LayoutInflater.from(ctx).inflate(R.layout.row_chat, parent, false);

        Map<String, String> item = data.get(position);

        TextView tvName    = (TextView) convertView.findViewById(R.id.name);
        TextView tvSnippet = (TextView) convertView.findViewById(R.id.snippet);
        TextView tvTime    = (TextView) convertView.findViewById(R.id.time);
        TextView tvBadge   = (TextView) convertView.findViewById(R.id.badge);

        tvName.setText(item.get("name"));
        tvSnippet.setText(item.get("snippet"));
        tvTime.setText(item.get("time"));

        int count = 0;
        try { count = Integer.parseInt(item.get("unread")); } catch (Exception ignored) {}

        if (count > 0) {
            tvBadge.setVisibility(View.VISIBLE);
            tvBadge.setText(count > 99 ? "99+" : String.valueOf(count));
            tvName.setTypeface(null, Typeface.BOLD);
            tvSnippet.setTypeface(null, Typeface.BOLD);
        } else {
            tvBadge.setVisibility(View.GONE);
            tvName.setTypeface(null, Typeface.NORMAL);
            tvSnippet.setTypeface(null, Typeface.NORMAL);
        }

        return convertView;
    }
}
