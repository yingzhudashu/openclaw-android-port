package ai.openclaw.poc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class CronFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val tasks = mutableListOf<CronTask>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_cron, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rvCronTasks)
        tvEmpty = view.findViewById(R.id.tvCronEmpty)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<FloatingActionButton>(R.id.fabAddCron).setOnClickListener {
            showEditDialog(null)
        }

        loadTasks()
    }

    override fun onResume() {
        super.onResume()
        loadTasks()
    }

    private fun loadTasks() {
        tasks.clear()
        tasks.addAll(CronManager.getTasks(requireContext()))
        if (tasks.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = CronAdapter(tasks,
                onToggle = { task, enabled ->
                    CronManager.saveTask(requireContext(), task.copy(enabled = enabled))
                    CronWorker.scheduleAll(requireContext())
                },
                onEdit = { showEditDialog(it) },
                onDelete = { task ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.cron_delete_confirm)
                        .setPositiveButton(R.string.chat_delete) { _, _ ->
                            CronManager.deleteTask(requireContext(), task.id)
                            CronWorker.cancelTask(requireContext(), task.id)
                            loadTasks()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            )
        }
    }

    private fun showEditDialog(existing: CronTask?) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cron_edit, null)
        val etName = view.findViewById<EditText>(R.id.etCronName)
        val etPrompt = view.findViewById<EditText>(R.id.etCronPrompt)
        val etInterval = view.findViewById<EditText>(R.id.etCronInterval)
        val switchNotify = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchCronNotify)

        existing?.let {
            etName.setText(it.name)
            etPrompt.setText(it.prompt)
            etInterval.setText(it.intervalMinutes.toString())
            switchNotify.isChecked = it.notify
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (existing != null) R.string.cron_edit else R.string.cron_create)
            .setView(view)
            .setPositiveButton(R.string.cron_save) { _, _ ->
                val name = etName.text.toString().trim()
                val prompt = etPrompt.text.toString().trim()
                val interval = etInterval.text.toString().toIntOrNull() ?: 60

                if (name.isNotEmpty() && prompt.isNotEmpty()) {
                    val task = (existing ?: CronTask(name = name, prompt = prompt, intervalMinutes = interval)).let {
                        it.copy(name = name, prompt = prompt, intervalMinutes = interval.coerceAtLeast(15),
                            notify = switchNotify.isChecked)
                    }
                    CronManager.saveTask(requireContext(), task)
                    CronWorker.scheduleAll(requireContext())
                    loadTasks()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/**
 * Cron task list adapter
 */
class CronAdapter(
    private val tasks: List<CronTask>,
    private val onToggle: (CronTask, Boolean) -> Unit,
    private val onEdit: (CronTask) -> Unit,
    private val onDelete: (CronTask) -> Unit
) : RecyclerView.Adapter<CronAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvCronName)
        val tvPrompt: TextView = view.findViewById(R.id.tvCronPrompt)
        val tvInterval: TextView = view.findViewById(R.id.tvCronInterval)
        val tvLastRun: TextView = view.findViewById(R.id.tvCronLastRun)
        val switchEnabled: com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.switchCronEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cron_task, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val task = tasks[position]
        holder.tvName.text = task.name
        holder.tvPrompt.text = task.prompt
        holder.tvInterval.text = holder.itemView.context.getString(R.string.cron_interval_fmt, task.intervalMinutes)
        holder.tvLastRun.text = if (task.lastRun > 0) {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            "${fmt.format(java.util.Date(task.lastRun))} ${task.lastResult.take(50)}"
        } else {
            holder.itemView.context.getString(R.string.cron_never_run)
        }
        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = task.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(task, checked) }
        holder.itemView.setOnClickListener { onEdit(task) }
        holder.itemView.setOnLongClickListener { onDelete(task); true }
    }

    override fun getItemCount() = tasks.size
}
