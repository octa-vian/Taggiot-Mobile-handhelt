package com.taggiot.handheld.ui.inventory;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.taggiot.handheld.R;
import com.taggiot.handheld.databinding.ItemTagBinding;
import com.taggiot.handheld.ui.UiUtil;
import com.taggiot.handheld.util.Hex;

import java.util.Objects;

/** Daftar EPC dengan nama alat, kode alat GMF, dan status tag dari finder Site Gateway. */
public class TagAdapter extends ListAdapter<TagRow, TagAdapter.VH> {

    public interface OnClick { void onClick(TagRow row); }

    private final OnClick onClick;

    public TagAdapter(OnClick onClick) {
        super(new DiffUtil.ItemCallback<TagRow>() {
            @Override public boolean areItemsTheSame(@NonNull TagRow a, @NonNull TagRow b) { return a.epc.equals(b.epc); }
            @Override public boolean areContentsTheSame(@NonNull TagRow a, @NonNull TagRow b) {
                return a.rssi == b.rssi && a.resolved() == b.resolved() && a.known() == b.known()
                        && (a.info == null || Objects.equals(a.info.tagStatus, b.info.tagStatus));
            }
        });
        this.onClick = onClick;
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(ItemTagBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        TagRow r = getItem(pos);
        ItemTagBinding v = h.b;
        android.content.Context c = v.getRoot().getContext();
        if (r.known()) {
            String code = UiUtil.toolCode(r.info);
            v.tvName.setText(r.info.name != null ? r.info.name : code);
            v.tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            v.tvSub.setText(code + " · " + UiUtil.tagStatusLabel(r.info.tagStatus));
            boolean warn = "blocked".equals(r.info.tagStatus);
            v.ivTile.setBackgroundResource(warn ? R.drawable.bg_tile_amber : R.drawable.bg_tile_blue);
            v.ivTile.setImageResource(warn ? R.drawable.ic_alert : R.drawable.ic_settings);
            ImageViewCompat.setImageTintList(v.ivTile, ContextCompat.getColorStateList(c, warn ? R.color.status_warn : R.color.brand_600));
        } else {
            v.tvName.setText(Hex.group4Lines(r.epc));
            v.tvName.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            v.tvSub.setText(r.unknown() ? "Tidak dikenal Site Gateway" : "Mencari di Site Gateway…");
            v.ivTile.setBackgroundResource(r.unknown() ? R.drawable.bg_tile_amber : R.drawable.bg_tile_blue);
            v.ivTile.setImageResource(r.unknown() ? R.drawable.ic_alert : R.drawable.ic_tag);
            ImageViewCompat.setImageTintList(v.ivTile, ContextCompat.getColorStateList(c, r.unknown() ? R.color.status_warn : R.color.ink_3));
        }
        if (r.rssi != Integer.MIN_VALUE) {
            v.signal.setVisibility(View.VISIBLE);
            v.signal.setRssi(r.rssi);
            v.tvMeta.setText(String.valueOf(r.rssi).replace('-', '−') + " dBm");
        } else {
            v.signal.setVisibility(View.GONE);
            v.tvMeta.setText(r.unknown() ? "Salin EPC" : "");
        }
        v.card.setOnClickListener(x -> onClick.onClick(r));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemTagBinding b;
        VH(ItemTagBinding b) { super(b.getRoot()); this.b = b; }
    }
}
