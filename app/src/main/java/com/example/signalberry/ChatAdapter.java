package com.example.signalberry;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_PEER = 1;
    private static final int VIEW_ME   = 2;

    private final List<MessageItem> data;

    ChatAdapter(List<MessageItem> data) { this.data = data; }

    @Override public int getItemViewType(int position) {
        return "me".equals(data.get(position).from) ? VIEW_ME : VIEW_PEER;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_ME) {
            View v = inf.inflate(R.layout.item_chat_me, parent, false);
            return new MeVH(v);
        } else {
            View v = inf.inflate(R.layout.item_chat_peer, parent, false);
            return new PeerVH(v);
        }
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        MessageItem m = data.get(pos);
        if (h instanceof MeVH) ((MeVH) h).bind(m);
        else ((PeerVH) h).bind(m);
    }

    @Override public int getItemCount() { return data.size(); }

    static class PeerVH extends RecyclerView.ViewHolder {
        final TextView tvMessage;
        PeerVH(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
        }
        void bind(MessageItem m) { tvMessage.setText(m.text); }
    }

    static class MeVH extends RecyclerView.ViewHolder {
        final TextView tvMessage, tvStatus;
        MeVH(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvStatus  = itemView.findViewById(R.id.tvStatus);
        }
        void bind(MessageItem m) {
            tvMessage.setText(m.text);
            String mark;
            switch (m.status) {
                case Chat.ST_PENDING:   mark = "🕒"; break;
                case Chat.ST_SENT:      mark = "✓";  break;
                case Chat.ST_DELIVERED: // fall-through
                case Chat.ST_READ:      mark = "✓✓"; break;
                default:                mark = "";
            }
            tvStatus.setText(mark);
        }
    }
}
