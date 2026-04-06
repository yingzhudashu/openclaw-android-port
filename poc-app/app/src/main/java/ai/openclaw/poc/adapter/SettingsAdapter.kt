package ai.openclaw.poc.adapter

import ai.openclaw.poc.R
import ai.openclaw.poc.model.SettingItem
import ai.openclaw.poc.model.SettingItemType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SettingsAdapter(
    private val items: List<SettingItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_ITEM = 0
        const val TYPE_SEPARATOR = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            SettingItemType.SEPARATOR -> TYPE_SEPARATOR
            else -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_SEPARATOR -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.divider_setting_group, parent, false)
                SeparatorViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_setting_cell, parent, false)
                SettingItemViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SettingItemViewHolder -> holder.bind(items[position])
            is SeparatorViewHolder -> {} // Nothing to bind for separator
        }
    }

    override fun getItemCount(): Int = items.size

    class SettingItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIcon: TextView = itemView.findViewById(R.id.tvIcon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSummary: TextView = itemView.findViewById(R.id.tvSummary)
        private val tvArrow: TextView = itemView.findViewById(R.id.tvArrow)

        fun bind(item: SettingItem) {
            tvIcon.text = item.icon
            tvTitle.text = item.title
            tvSummary.text = item.summary
            
            // Show arrow only for navigable items
            tvArrow.visibility = if (item.type == SettingItemType.NAVIGATE) View.VISIBLE else View.GONE
            tvSummary.visibility = if (item.summary.isNotEmpty()) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                item.onClick?.invoke()
            }
        }
    }

    class SeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}