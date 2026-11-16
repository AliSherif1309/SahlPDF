package com.gitlab.mudlej.MjPdfReader.ui.tools

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Only attached cells request previews; bitmap cache and rendering concurrency are bounded. */
class PageManagerAdapter(
    private val model: PageManagerViewModel,
    private val scope: CoroutineScope,
) : RecyclerView.Adapter<PageManagerAdapter.Holder>() {
    private var state = PageManagerState()
    private val holders = mutableSetOf<Holder>()
    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }
    fun submit(next: PageManagerState) {
        val changed = state.source != next.source || state.plan != next.plan || state.busy != next.busy
        state = next
        if (changed) notifyDataSetChanged()
    }
    override fun getItemCount() = state.plan?.pages?.size ?: 0
    override fun getItemId(position: Int) = state.plan!!.pages[position].id
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_managed_page, parent, false))
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holders.add(holder)
        holder.job?.cancel()
        val page = state.plan!!.pages[position]
        val source = state.source ?: return
        val context = holder.itemView.context
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = page.id in state.plan!!.selected
        holder.check.text = context.getString(R.string.page_manager_page, position + 1, page.sourceIndex + 1, page.rotation)
        holder.check.isEnabled = !state.busy
        holder.card.isChecked = holder.check.isChecked
        holder.card.isEnabled = !state.busy
        holder.check.setOnCheckedChangeListener { _, _ -> model.select(page.id) }
        holder.card.setOnClickListener { if (!state.busy) model.select(page.id) }
        holder.image.setImageDrawable(null)
        holder.previewStatus.visibility = View.VISIBLE
        holder.previewStatus.setText(R.string.page_manager_preview_loading)
        holder.job = scope.launch {
            try {
                val bitmap = model.thumbnail(source, page.sourceIndex, page.rotation)
                holder.image.setImageBitmap(bitmap)
                holder.previewStatus.visibility = View.GONE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                holder.previewStatus.setText(R.string.page_manager_preview_failed)
            }
        }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.image.setImageDrawable(null)
        holders.remove(holder)
        super.onViewRecycled(holder)
    }
    fun close() {
        holders.forEach { it.job?.cancel(); it.image.setImageDrawable(null) }
        holders.clear()
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.managed_page_card)
        val image: ImageView = view.findViewById(R.id.managed_page_image)
        val check: CheckBox = view.findViewById(R.id.managed_page_check)
        val previewStatus: TextView = view.findViewById(R.id.managed_page_preview_status)
        var job: Job? = null
    }
}
