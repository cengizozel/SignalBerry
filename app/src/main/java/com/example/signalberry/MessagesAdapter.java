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
    private final Map<String, Integer> unread;

    MessagesAdapter(Context ctx, List<Map<String, String>> data, Map<String, Integer> unread) {
        this.ctx    = ctx;
        this.data   = data;
        this.unread = unread;
    }

    @Override public int getCount()              { return data.size(); }
    @Override public Object getItem(int pos)     { return data.get(pos); }
    @Override public long getItemId(int pos)     { return pos; }

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

        String number = item.get("number");
        String uuid   = item.get("uuid");
        String peer   = !isEmpty(number) ? digits(number) : safeTrim(uuid);

        int count = (peer != null && unread.containsKey(peer)) ? unread.get(peer) : 0;

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

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safeTrim(String s) { return s == null ? null : s.trim(); }
    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }
}
