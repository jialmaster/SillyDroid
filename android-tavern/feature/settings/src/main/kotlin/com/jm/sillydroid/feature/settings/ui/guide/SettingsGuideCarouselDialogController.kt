package com.jm.sillydroid.feature.settings.ui.guide

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.jm.sillydroid.feature.settings.R

/**
 * 设置页通用全屏图文引导。整屏可左右滑动，右上角跳过，最后一页显示主动作按钮。
 * 调用方负责保存“是否已展示”状态，避免把具体业务状态塞进通用 UI 组件。
 */
class SettingsGuideCarouselDialogController(
    private val activity: AppCompatActivity
) {
    fun show(
        title: CharSequence,
        summary: CharSequence,
        pages: List<SettingsGuidePage>,
        onDismissedByUser: () -> Unit
    ) {
        if (pages.isEmpty()) {
            onDismissedByUser()
            return
        }

        var dialog: Dialog? = null
        val pager = createPager(pages)
        val indicatorRow = createIndicatorRow(pages.size)
        val actionButton = createActionButton()

        fun render(position: Int) {
            renderIndicators(indicatorRow, position)
            actionButton.visibility = if (position == pages.lastIndex) View.VISIBLE else View.INVISIBLE
        }

        pager.adapter = GuidePageAdapter(activity, pages)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                render(position)
            }
        })

        actionButton.setOnClickListener {
            dialog?.dismiss()
            onDismissedByUser()
        }

        val content = createRoot()
        content.addView(createHeader(title = title, summary = summary, onSkip = {
            dialog?.dismiss()
            onDismissedByUser()
        }))
        content.addView(pager)
        content.addView(createFooter(indicatorRow = indicatorRow, actionButton = actionButton))
        render(0)

        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            setOnShowListener {
                window?.let { targetWindow ->
                    targetWindow.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    targetWindow.setBackgroundDrawableResource(android.R.color.transparent)
                    WindowCompat.setDecorFitsSystemWindows(targetWindow, false)
                }
            }
            show()
        }
    }

    private fun createRoot(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveColor(MaterialR.attr.colorSurfaceContainerLowest))
            setPadding(
                dimen(R.dimen.sillydroid_space_xl),
                dimen(R.dimen.sillydroid_space_xl),
                dimen(R.dimen.sillydroid_space_xl),
                dimen(R.dimen.sillydroid_space_xl)
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun createHeader(
        title: CharSequence,
        summary: CharSequence,
        onSkip: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dimen(R.dimen.sillydroid_space_lg), 0, dimen(R.dimen.sillydroid_space_md))

            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(activity).apply {
                        setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsToolbarTitle)
                        text = title
                        setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                addView(
                    MaterialButton(activity, null, MaterialR.attr.borderlessButtonStyle).apply {
                        text = activity.getString(R.string.bootstrap_settings_guide_skip)
                        minHeight = dimen(R.dimen.sillydroid_control_min_height)
                        minimumHeight = 0
                        insetTop = 0
                        insetBottom = 0
                        setOnClickListener { onSkip() }
                    }
                )
            }

            addView(topRow)
            addView(
                TextView(activity).apply {
                    setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
                    text = summary
                    setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
                    setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
                }
            )
        }
    }

    private fun createPager(pages: List<SettingsGuidePage>): ViewPager2 {
        return ViewPager2(activity).apply {
            clipToPadding = false
            clipChildren = false
            (getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            offscreenPageLimit = pages.size.coerceAtMost(2)
        }
    }

    private fun createFooter(
        indicatorRow: LinearLayout,
        actionButton: MaterialButton
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dimen(R.dimen.sillydroid_space_md), 0, dimen(R.dimen.sillydroid_space_lg))
            addView(indicatorRow)
            addView(actionButton)
        }
    }

    private fun createActionButton(): MaterialButton {
        return MaterialButton(activity).apply {
            text = activity.getString(R.string.bootstrap_settings_guide_open_now)
            minHeight = dimen(R.dimen.sillydroid_control_min_height)
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_space_md)
            }
        }
    }

    private fun createIndicatorRow(pageCount: Int): LinearLayout {
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            repeat(pageCount) {
                addView(createIndicatorDot())
            }
        }
    }

    private fun createIndicatorDot(): View {
        val size = dimen(R.dimen.sillydroid_space_sm)
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_xs)
                rightMargin = dimen(R.dimen.sillydroid_space_xs)
            }
        }
    }

    private fun renderIndicators(container: LinearLayout, selectedIndex: Int) {
        val selectedColor = resolveColor(MaterialR.attr.colorPrimary)
        val defaultColor = resolveColor(MaterialR.attr.colorOutlineVariant)
        repeat(container.childCount) { index ->
            container.getChildAt(index).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (index == selectedIndex) selectedColor else defaultColor)
            }
        }
    }

    private fun resolveColor(attr: Int): Int = MaterialColors.getColor(activity, attr, 0)

    private fun dimen(id: Int): Int = activity.resources.getDimensionPixelSize(id)

    private class GuidePageAdapter(
        private val activity: AppCompatActivity,
        private val pages: List<SettingsGuidePage>
    ) : RecyclerView.Adapter<GuidePageAdapter.GuidePageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuidePageViewHolder {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val imageFrame = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val imageView = ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            imageFrame.addView(imageView)
            content.addView(imageFrame)

            val titleView = TextView(activity).apply {
                setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0))
                setPadding(0, activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_md), 0, 0)
            }
            val descriptionView = TextView(activity).apply {
                setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
                setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
                setPadding(0, activity.resources.getDimensionPixelSize(R.dimen.sillydroid_space_sm), 0, 0)
            }
            content.addView(titleView)
            content.addView(descriptionView)

            return GuidePageViewHolder(
                itemView = content,
                imageView = imageView,
                titleView = titleView,
                descriptionView = descriptionView
            )
        }

        override fun onBindViewHolder(holder: GuidePageViewHolder, position: Int) {
            val page = pages[position]
            holder.imageView.setImageResource(page.imageResId)
            holder.titleView.text = page.title
            holder.descriptionView.text = page.description
        }

        override fun getItemCount(): Int = pages.size

        private class GuidePageViewHolder(
            itemView: View,
            val imageView: ImageView,
            val titleView: TextView,
            val descriptionView: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }
}

data class SettingsGuidePage(
    @param:DrawableRes val imageResId: Int,
    val title: CharSequence,
    val description: CharSequence
)
