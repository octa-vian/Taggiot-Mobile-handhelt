package com.taggiot.handheld.ui.sync;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.db.OutboxEntity;
import com.taggiot.handheld.databinding.ItemOutboxBinding;
import com.taggiot.handheld.ui.UiUtil;

import java.util.ArrayList;
import java.util.List;

public class OutboxAdapter extends RecyclerView.Adapter<OutboxAdapter.VH> {

    private List<OutboxEntity> items = new ArrayList<>();

    public void submit(List<OutboxEntity> list) {
        items = list == null ? new ArrayList<>() : list;
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(ItemOutboxBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        OutboxEntity o = items.get(pos);
        ItemOutboxBinding v = h.b;
        v.tvTitle.setText(o.title);
        String when = UiUtil.time(o.status.equals(OutboxEntity.SENT) && o.sentAt > 0 ? o.sentAt : o.createdAt);
        String sub = o.subtitle + " · " + when + " · batch " + o.id.substring(0, Math.min(8, o.id.length()));
        if (o.lastError != null && !o.status.equals(OutboxEntity.SENT)) sub += "\n" + o.lastError;
        v.tvSub.setText(sub);
        v.ivIcon.setImageResource(OutboxEntity.KIND_BIND.equals(o.kind) ? R.drawable.ic_tag : R.drawable.ic_clipboard);
        switch (o.status) {
            case OutboxEntity.SENT: UiUtil.badge(v.tvBadge, UiUtil.Tone.OK, "Terkirim"); break;
            case OutboxEntity.FAILED: UiUtil.badge(v.tvBadge, UiUtil.Tone.BAD, "Ditolak"); break;
            default: UiUtil.badge(v.tvBadge, UiUtil.Tone.WARN, o.attempts > 0 ? "Coba lagi" : "Tertunda");
                v.tvBadge.setCompoundDrawables(null, null, null, null);
        }
        v.getRoot().setBackgroundResource(pos == 0 ? 0 : R.drawable.divider_top);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemOutboxBinding b;
        VH(ItemOutboxBinding b) { super(b.getRoot()); this.b = b; }
    }
}
