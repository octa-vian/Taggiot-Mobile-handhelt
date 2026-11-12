package com.taggiot.handheld.ui.count;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonElement;
import com.taggiot.handheld.R;
import com.taggiot.handheld.databinding.ItemOutboxBinding;
import com.taggiot.handheld.net.dto.Dtos;
import com.taggiot.handheld.ui.FinderCache;
import com.taggiot.handheld.ui.UiUtil;
import com.taggiot.handheld.util.Hex;

import java.util.ArrayList;
import java.util.List;

/** Baris selisih review: nama alat, kode GMF, jenis selisih, status tag (dipinjam ≠ hilang), resolusi. */
public class DiffAdapter extends RecyclerView.Adapter<DiffAdapter.VH> {

    public interface OnClick { void onClick(Dtos.Difference d); }

    private final OnClick onClick;
    private final FinderCache finder;
    private List<Dtos.Difference> items = new ArrayList<>();

    public DiffAdapter(OnClick onClick, FinderCache finder) { this.onClick = onClick; this.finder = finder; }

    public void submit(List<Dtos.Difference> list) { items = list; notifyDataSetChanged(); }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(ItemOutboxBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
    }

    static String detail(Dtos.Difference d, String key) {
        if (d.detail == null) return null;
        JsonElement e = d.detail.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public static String name(Dtos.Difference d, Dtos.FinderItem f) {
        if (f != null && f.known && f.name != null) return f.name;
        String n = detail(d, "name");
        if (n != null) return n;
        if (d.sku != null) return d.sku;
        return "unknown".equals(d.kind) ? "Tag tidak dikenal" : "Alat";
    }

    public static String tagStatus(Dtos.Difference d, Dtos.FinderItem f) {
        if (f != null && f.tagStatus != null) return f.tagStatus;
        return detail(d, "tag_status");
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Dtos.Difference d = items.get(pos);
        ItemOutboxBinding v = h.b;
        Dtos.FinderItem f = d.epc == null ? null : finder.get(d.epc);
        v.tvTitle.setText(name(d, f));
        StringBuilder sub = new StringBuilder(CountUi.diffKindLabel(d.kind));
        String code = f != null && f.known ? UiUtil.toolCode(f) : d.sku;
        if (code != null && !code.isEmpty()) sub.append(" · ").append(code);
        String st = tagStatus(d, f);
        if ("missing".equals(d.kind) && "released".equals(st)) sub.append("\nDipinjam (released): cek pinjaman di GMF, kemungkinan tidak hilang");
        else if (st != null) sub.append("\n").append(UiUtil.tagStatusLabel(st));
        if (d.expectedQty != null && d.countedQty != null && ("short".equals(d.kind) || "over".equals(d.kind)))
            sub.append(" · ").append(d.countedQty).append("/").append(d.expectedQty);
        if (d.epc != null) sub.append("\nEPC ").append(Hex.group4(d.epc));
        if (d.comment != null && !d.comment.isEmpty()) sub.append("\n“").append(d.comment).append("”");
        v.tvSub.setMaxLines(5);
        v.tvSub.setText(sub);

        int icon, tint, bg;
        switch (d.kind == null ? "" : d.kind) {
            case "missing": icon = R.drawable.ic_alert; tint = R.color.status_bad; bg = R.drawable.bg_badge_bad; break;
            case "unknown": icon = R.drawable.ic_tag; tint = R.color.status_warn; bg = R.drawable.bg_tile_amber; break;
            default: icon = R.drawable.ic_box; tint = R.color.brand_600; bg = R.drawable.bg_tile_blue;
        }
        v.ivIcon.setImageResource(icon);
        v.ivIcon.setBackgroundResource(bg);
        ImageViewCompat.setImageTintList(v.ivIcon, ContextCompat.getColorStateList(v.getRoot().getContext(), tint));
        switch (d.resolution == null ? "open" : d.resolution) {
            case "accepted": UiUtil.badge(v.tvBadge, UiUtil.Tone.OK, "Diterima"); break;
            case "recount": UiUtil.badge(v.tvBadge, UiUtil.Tone.INFO, "Hitung ulang"); break;
            default: UiUtil.badge(v.tvBadge, UiUtil.Tone.WARN, "Terbuka");
        }
        v.tvBadge.setCompoundDrawables(null, null, null, null);
        v.getRoot().setBackgroundResource(pos == 0 ? 0 : R.drawable.divider_top);
        v.getRoot().setOnClickListener(x -> onClick.onClick(d));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemOutboxBinding b;
        VH(ItemOutboxBinding b) { super(b.getRoot()); this.b = b; }
    }
}
