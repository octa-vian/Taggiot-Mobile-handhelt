package com.taggiot.handheld.ui.count;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.taggiot.handheld.R;
import com.taggiot.handheld.data.Repo;
import com.taggiot.handheld.data.db.CountSessionEntity;
import com.taggiot.handheld.databinding.ItemTaskBinding;
import com.taggiot.handheld.net.dto.Dtos;

import java.util.ArrayList;
import java.util.List;

/** Kartu sesi hitung: nomor SO-/IC-, laci, status, tally. */
public class CountAdapter extends RecyclerView.Adapter<CountAdapter.VH> {

    public interface OnOpen { void open(Dtos.CountSession s); }

    private final OnOpen onOpen;
    private final boolean supervisor;
    private List<Dtos.CountSession> items = new ArrayList<>();

    public CountAdapter(OnOpen onOpen, boolean supervisor) { this.onOpen = onOpen; this.supervisor = supervisor; }

    public void submit(List<CountSessionEntity> list) {
        List<Dtos.CountSession> out = new ArrayList<>();
        for (CountSessionEntity e : list) { Dtos.CountSession s = Repo.parse(e); if (s != null) out.add(s); }
        items = out;
        notifyDataSetChanged();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(ItemTaskBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
    }

    @Override public int getItemCount() { return items.size(); }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Dtos.CountSession s = items.get(pos);
        ItemTaskBinding v = h.b;
        String area = CountUi.area(s);
        v.tvTitle.setText(s.number + (area == null ? "" : " · laci " + area));
        v.tvSub.setText(CountUi.kindLabel(s) + " · " + CountUi.creatorLabel(s.createdBy));
        CountUi.statusBadge(v.tvBadge, s.status);

        int expected = CountUi.expected(s.tally), distinct = CountUi.distinct(s.tally);
        if (expected > 0) {
            int found = CountUi.countedExpected(s.tally);
            v.tvProgress.setText(found + " / " + expected + " alat terbaca");
            v.progress.setProgressCompat(Math.round(100f * found / expected), false);
            v.progress.setVisibility(View.VISIBLE);
        } else {
            v.tvProgress.setText(distinct + " EPC terbaca");
            v.progress.setVisibility(View.GONE);
        }
        v.tvDue.setText("");
        boolean open = "open".equals(s.status), review = "review".equals(s.status);
        v.root.setBackgroundResource(open ? R.drawable.bg_task_active : R.drawable.bg_card);
        v.progress.setIndicatorColor(ContextCompat.getColor(v.getRoot().getContext(), review ? R.color.status_warn : R.color.brand_500));
        boolean action = open || (review && supervisor);
        v.btnContinue.setVisibility(action ? View.VISIBLE : View.GONE);
        v.btnContinue.setText(open ? "Hitung" : "Review");
        v.btnContinue.setOnClickListener(x -> onOpen.open(s));
        v.root.setOnClickListener(x -> onOpen.open(s));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemTaskBinding b;
        VH(ItemTaskBinding b) { super(b.getRoot()); this.b = b; }
    }
}
