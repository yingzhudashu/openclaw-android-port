package ai.openclaw.poc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话数据类
 *
 * @param id 会话ID
 * @param title 会话标题
 * @param messageCount 消息数量
 * @param updatedAt 更新时间
 */
data class Session(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAt: String
)

/**
 * 会话列表 RecyclerView 适配器
 */
class SessionAdapter(
    private val sessions: List<Session>,
    private val onItemClick: (Session) -> Unit,
    private val onItemLongClick: (Session) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun getItemCount(): Int = sessions.size

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSessionTitle: TextView = itemView.findViewById(R.id.tvSessionTitle)
        private val tvSessionMessageCount: TextView = itemView.findViewById(R.id.tvSessionMessageCount)
        private val tvSessionUpdatedAt: TextView = itemView.findViewById(R.id.tvSessionUpdatedAt)
        private val cardView: MaterialCardView = itemView.findViewById(R.id.cardView)

        fun bind(session: Session) {
            val shortId = session.id.take(8)
            tvSessionTitle.text = session.title
            tvSessionMessageCount.text = itemView.context.getString(R.string.chat_session_info, shortId, session.messageCount)
            tvSessionUpdatedAt.text = session.updatedAt

            cardView.setOnClickListener {
                onItemClick(session)
            }
            cardView.setOnLongClickListener {
                onItemLongClick(session)
                true
            }
        }
    }
}