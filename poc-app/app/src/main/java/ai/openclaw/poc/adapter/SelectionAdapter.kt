package ai.openclaw.poc.adapter

import ai.openclaw.poc.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SelectionAdapter(
    private val items: List<String>,
    private val selectedItem: String,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<SelectionAdapter.SelectionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selection_cell, parent, false)
        return SelectionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SelectionViewHolder, position: Int) {
        holder.bind(items[position], selectedItem, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class SelectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val radioButton: RadioButton = itemView.findViewById(R.id.radioButton)

        fun bind(item: String, selectedItem: String, onItemClick: (String) -> Unit) {
            tvTitle.text = item
            radioButton.isChecked = item == selectedItem

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}