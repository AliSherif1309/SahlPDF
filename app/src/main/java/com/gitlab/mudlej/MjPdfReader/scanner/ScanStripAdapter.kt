package com.gitlab.mudlej.MjPdfReader.scanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ScanStripAdapter(
    private val model: ScannerViewModel,
    private val scope: CoroutineScope,
) : RecyclerView.Adapter<ScanStripAdapter.Holder>() {
    private var state = ScannerState()
    private val holders = mutableSetOf<Holder>()
    init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    fun submit(next: ScannerState) {
        val changed = state.draft != next.draft || state.busy != next.busy
        state = next
        if (changed) notifyDataSetChanged()
    }
    override fun getItemCount() = state.draft?.pages?.size ?: 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_scan_page, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holders.add(holder)
        holder.job?.cancel()
        val draft = state.draft ?: return
        val page = draft.pages[position]
        holder.label.text = holder.itemView.context.getString(R.string.scanner_page_number, position + 1)
        holder.card.isChecked = draft.selectedPage()?.id == page.id
        holder.card.isEnabled = !state.busy
        holder.card.contentDescription = holder.label.text
        holder.card.setOnClickListener { model.select(page.id) }
        holder.image.setImageDrawable(null)
        holder.job = scope.launch {
            try { holder.image.setImageBitmap(model.thumbnail(draft, page)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { holder.label.setText(R.string.page_manager_preview_failed) }
        }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel(); holder.image.setImageDrawable(null); holders.remove(holder)
        super.onViewRecycled(holder)
    }
    fun close() { holders.forEach { it.job?.cancel(); it.image.setImageDrawable(null) }; holders.clear() }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.scan_page_card)
        val image: ImageView = view.findViewById(R.id.scan_page_image)
        val label: TextView = view.findViewById(R.id.scan_page_label)
        var job: Job? = null
    }
}
