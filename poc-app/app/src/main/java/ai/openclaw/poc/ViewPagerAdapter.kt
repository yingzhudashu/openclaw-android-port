package ai.openclaw.poc

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 适配器
 *
 * 管理 3 个 Tab 页面：
 * - 0: 💬 对话（ChatFragment）
 * - 1: ⚙️ 设置（SettingsFragment）
 * - 2: 📊 状态（StatusFragment）
 */
class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val TAB_CHAT = 0
        const val TAB_SETTINGS = 1
        const val TAB_STATUS = 2
        const val TAB_CRON = 3
        const val TAB_COUNT = 4
    }

    override fun getItemCount(): Int = TAB_COUNT

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            TAB_CHAT -> ChatFragment()
            TAB_SETTINGS -> SettingsFragment()
            TAB_STATUS -> StatusFragment()
            TAB_CRON -> CronFragment()
            else -> ChatFragment()
        }
    }
}
