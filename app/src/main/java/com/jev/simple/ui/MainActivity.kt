package com.jev.simple.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.jev.simple.R
import com.jev.simple.core.JevStore

/** 主 App：开始 / 模型 / 话术 / 试一试。三页都直接读写 JevStore，输入法那边看到的是同一份。 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: TabLayout
    private lateinit var host: FrameLayout
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JevStore.init(this)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tabs)
        host = findViewById(R.id.host)

        for (title in listOf("开始", "模型", "话术", "试一试")) {
            tabs.addTab(tabs.newTab().setText(title))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            // 再点一次当前页 = 刷新（配置被输入法改过时用得上）
            override fun onTabReselected(tab: TabLayout.Tab) = show(tab.position)
        })
        show(0)
    }

    private fun show(index: Int) {
        current = index
        host.removeAllViews()
        val page = when (index) {
            0 -> SetupPage.build(this)
            1 -> ProvidersPage.build(this)
            2 -> TonesPage.build(this)
            else -> PlaygroundPage.build(this)
        }
        host.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** 页面内部改完配置后调它把当前页重画一遍。 */
    fun refresh() = show(current)
}
