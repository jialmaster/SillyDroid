package com.jm.sillydroid.feature.settings.ui.form

import android.content.res.ColorStateList
import android.transition.ChangeBounds
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.jm.sillydroid.core.model.settings.TavernConfigFieldKind
import com.jm.sillydroid.core.model.settings.TavernConfigFieldSpec
import com.jm.sillydroid.core.model.settings.TavernConfigSectionSpec
import com.jm.sillydroid.domain.settings.SettingsConfigRepository
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.BootstrapSettingsValidationIssue
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR
import java.net.ServerSocket
import java.util.ArrayList
import java.util.LinkedHashMap

class BootstrapSettingsFormController(
    private val activity: AppCompatActivity,
    private val configRepository: SettingsConfigRepository,
    private val quickFieldContainer: LinearLayout,
    private val sectionContainer: LinearLayout,
    private val scrollView: NestedScrollView,
    private val defaultServicePort: Int,
    private val onFieldEdited: (String?) -> Unit,
    private val onFormChanged: () -> Unit
) {
    private data class SearchBinding(
        val path: String,
        val searchText: String,
        val targetView: View,
        val applyMatchedState: (Boolean) -> Unit
    )

    private data class SectionBinding(
        val cardView: MaterialCardView,
        val dividerView: View,
        val contentView: LinearLayout,
        val toggleView: TextView,
        val fieldPaths: List<String>,
        var expanded: Boolean
    )

    private sealed interface FieldBinding {
        val path: String
        val containerView: View
        fun currentValue(): Any?
        fun applyProgrammaticValue(field: TavernConfigFieldSpec, rawValue: Any?)
        fun clearError()
        fun setError(message: String)
        fun requestFocus()
    }

    private class SwitchBinding(
        override val path: String,
        override val containerView: View,
        private val switch: MaterialSwitch
    ) : FieldBinding {
        override fun currentValue(): Any = switch.isChecked

        override fun applyProgrammaticValue(field: TavernConfigFieldSpec, rawValue: Any?) {
            val nextChecked = when (rawValue) {
                is Boolean -> rawValue
                is String -> rawValue.equals("true", ignoreCase = true)
                else -> field.defaultValue as? Boolean ?: false
            }
            if (switch.isChecked != nextChecked) {
                switch.isChecked = nextChecked
            }
        }

        override fun clearError() {
        }

        override fun setError(message: String) {
        }

        override fun requestFocus() {
            switch.requestFocus()
        }
    }

    private class TextBinding(
        override val path: String,
        override val containerView: View,
        private val inputLayout: TextInputLayout,
        private val input: TextInputEditText,
        private val blankFallbackText: String? = null
    ) : FieldBinding {
        override fun currentValue(): Any {
            val value = input.text?.toString().orEmpty()
            return if (blankFallbackText != null && value.isBlank()) {
                blankFallbackText
            } else {
                value
            }
        }

        override fun applyProgrammaticValue(field: TavernConfigFieldSpec, rawValue: Any?) {
            val nextText = when (field.kind) {
                TavernConfigFieldKind.STRING_LIST -> {
                    val listValue = rawValue as? Iterable<*>
                    listValue?.mapNotNull { item -> item?.toString()?.trim() }
                        ?.filter { item -> item.isNotBlank() }
                        ?.joinToString(separator = "\n")
                        .orEmpty()
                }

                else -> rawValue?.toString().orEmpty()
            }
            if (input.text?.toString().orEmpty() != nextText) {
                input.setText(nextText)
                input.setSelection(nextText.length)
            }
        }

        override fun clearError() {
            inputLayout.error = null
        }

        override fun setError(message: String) {
            inputLayout.error = message
        }

        override fun requestFocus() {
            input.requestFocus()
        }
    }

    private val fieldBindings = linkedMapOf<String, FieldBinding>()
    private val fieldSearchBindings = linkedMapOf<String, SearchBinding>()
    private val sectionBindings = mutableListOf<SectionBinding>()
    private val quickFieldPaths = linkedSetOf<String>()
    private var currentRoot = linkedMapOf<String, Any?>()
    private var currentSearchQuery = ""

    fun renderStartupPortPlaceholder() {
        val portField = configRepository.fieldsByPath["port"] ?: return
        currentRoot = linkedMapOf()
        quickFieldContainer.removeAllViews()
        sectionContainer.removeAllViews()
        fieldBindings.clear()
        fieldSearchBindings.clear()
        sectionBindings.clear()
        quickFieldPaths.clear()

        // 异步加载真实配置前，先用默认端口占位，避免“酒馆设置”页签首屏出现空白跳动。
        quickFieldContainer.addView(
            createPortFieldView(
                field = portField,
                currentValue = defaultServicePort,
                registerBinding = false,
                enabled = false
            )
        )
    }

    fun render(root: LinkedHashMap<String, Any?>) {
        currentRoot = configRepository.copyRoot(root)
        quickFieldContainer.removeAllViews()
        sectionContainer.removeAllViews()
        fieldBindings.clear()
        fieldSearchBindings.clear()
        sectionBindings.clear()
        quickFieldPaths.clear()

        configRepository.fieldsByPath["port"]?.takeIf { shouldRenderField(it.path) }?.let { portField ->
            quickFieldPaths += portField.path
            quickFieldContainer.addView(createFieldView(portField, currentRoot))
        }

        for ((index, section) in configRepository.sections.withIndex()) {
            val filteredFields = section.fields.filter { spec ->
                spec.path != "port" && shouldRenderField(spec.path)
            }
            if (filteredFields.isEmpty()) {
                continue
            }

            val sectionBinding = createSectionCard(
                section.copy(fields = filteredFields),
                currentRoot,
                expandedByDefault = index == 0
            )
            sectionBindings += sectionBinding
            sectionContainer.addView(sectionBinding.cardView)
        }
        updateFieldVisibility()
        applySearchQuery(currentSearchQuery, scrollToFirstMatch = false)
    }

    fun applySearchQuery(query: String, scrollToFirstMatch: Boolean = true): Boolean {
        currentSearchQuery = query.trim()
        val normalizedQuery = currentSearchQuery.lowercase()
        if (normalizedQuery.isBlank()) {
            fieldSearchBindings.values.forEach { binding ->
                binding.applyMatchedState(false)
            }
            return true
        }

        val matchedPaths = linkedSetOf<String>()
        for ((path, binding) in fieldSearchBindings) {
            val isVisible = fieldBindings[path]?.containerView?.isVisible == true
            val matched = isVisible && binding.searchText.contains(normalizedQuery)
            binding.applyMatchedState(matched)
            if (matched) {
                matchedPaths += path
            }
        }

        for (sectionBinding in sectionBindings) {
            if (sectionBinding.fieldPaths.any(matchedPaths::contains)) {
                sectionBinding.expanded = true
            }
            val hasVisibleFields = sectionBinding.fieldPaths.any { path ->
                fieldBindings[path]?.containerView?.isVisible == true
            }
            syncSectionExpansion(sectionBinding, hasVisibleFields)
        }

        if (scrollToFirstMatch) {
            matchedPaths.firstOrNull()?.let { firstMatchPath ->
                fieldSearchBindings[firstMatchPath]?.targetView?.let(::ensureViewVisible)
            }
        }

        return matchedPaths.isNotEmpty()
    }

    fun collectTypedValues(resolveTypedFieldValue: (TavernConfigFieldSpec, Any?) -> Any): LinkedHashMap<String, Any?> {
        val typedValues = linkedMapOf<String, Any?>()
        for (field in configRepository.allFields) {
            val binding = fieldBindings[field.path] ?: continue
            typedValues[field.path] = resolveTypedFieldValue(field, binding.currentValue())
        }
        return typedValues
    }

    fun currentTypedValues(resolveTypedFieldValue: (TavernConfigFieldSpec, Any?) -> Any): LinkedHashMap<String, Any?> {
        return collectTypedValues(resolveTypedFieldValue)
    }

    fun applyProgrammaticValues(valuesByPath: Map<String, Any?>) {
        if (valuesByPath.isEmpty()) {
            return
        }

        for ((path, rawValue) in valuesByPath) {
            val field = configRepository.fieldsByPath[path] ?: continue
            val binding = fieldBindings[path] ?: continue
            // 快捷功能直接改当前表单控件，保证 dirty 状态、显隐联动和后续保存仍然走设置页现有链路。
            binding.applyProgrammaticValue(field, rawValue)
        }

        updateFieldVisibility()
        onFieldEdited(null)
        onFormChanged()
    }

    fun captureSnapshot(): String {
        return buildString {
            for ((path, binding) in fieldBindings) {
                append(path)
                append('=')
                when (val value = binding.currentValue()) {
                    is Iterable<*> -> append(value.joinToString("|") { item -> item?.toString().orEmpty() })
                    else -> append(value?.toString().orEmpty())
                }
                append('\n')
            }
        }
    }

    fun isQuickField(path: String?): Boolean {
        return path != null && path in quickFieldPaths
    }

    fun clearFieldErrors(changedFieldPath: String? = null) {
        if (changedFieldPath == null) {
            fieldBindings.values.forEach(FieldBinding::clearError)
        } else {
            fieldBindings[changedFieldPath]?.clearError()
        }
    }

    fun showValidationIssue(issue: BootstrapSettingsValidationIssue): Boolean {
        val fieldBinding = issue.fieldPath?.let(fieldBindings::get)
        fieldBinding?.setError(issue.message)
        fieldBinding?.requestFocus()
        return fieldBinding != null
    }

    private fun createSectionCard(
        section: TavernConfigSectionSpec,
        root: LinkedHashMap<String, Any?>,
        expandedByDefault: Boolean
    ): SectionBinding {
        val cardView = MaterialCardView(activity).apply {
            radius = dimenFloat(R.dimen.sillydroid_card_radius)
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = resolveThemeColor(MaterialR.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveThemeColor(MaterialR.attr.colorSurface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dimen(R.dimen.sillydroid_section_card_spacing)
            }
        }

        val wrapperLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveThemeResource(android.R.attr.selectableItemBackground))
            setPadding(dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_space_lg))
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(TextView(activity).apply {
            text = section.title
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
            setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val toggleView = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBadge)
            setPadding(dimen(R.dimen.sillydroid_space_lg), dimen(R.dimen.sillydroid_space_xs), dimen(R.dimen.sillydroid_space_lg), dimen(R.dimen.sillydroid_space_xs))
            setTextColor(resolveThemeColor(MaterialR.attr.colorOnPrimaryContainer))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpFloat(999)
                setColor(resolveThemeColor(MaterialR.attr.colorPrimaryContainer))
            }
        }
        titleRow.addView(toggleView)
        headerLayout.addView(titleRow)
        headerLayout.addView(TextView(activity).apply {
            text = section.summary
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
            setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
        })

        val dividerView = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(resolveThemeColor(MaterialR.attr.colorOutlineVariant))
            alpha = 0.35f
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_space_xs), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_section_padding))
        }
        val fieldPaths = mutableListOf<String>()

        for (field in section.fields) {
            contentLayout.addView(createFieldView(field, root))
            fieldPaths += field.path
        }

        wrapperLayout.addView(headerLayout)
        wrapperLayout.addView(dividerView)
        wrapperLayout.addView(contentLayout)
        cardView.addView(wrapperLayout)

        val binding = SectionBinding(cardView, dividerView, contentLayout, toggleView, fieldPaths, expandedByDefault)
        headerLayout.setOnClickListener {
            binding.expanded = !binding.expanded
            syncSectionExpansion(binding, hasVisibleFields = true, animate = true)
        }
        syncSectionExpansion(binding, hasVisibleFields = true)
        return binding
    }

    private fun createFieldView(field: TavernConfigFieldSpec, root: LinkedHashMap<String, Any?>): View {
        val currentValue = configRepository.readValue(root, field.path) ?: field.defaultValue
        if (field.path == "port") {
            return createPortFieldView(field, currentValue)
        }

        return when (field.kind) {
            TavernConfigFieldKind.BOOLEAN -> {
                val card = MaterialCardView(activity).apply {
                    radius = dimenFloat(R.dimen.sillydroid_nested_card_radius)
                    cardElevation = 0f
                    strokeWidth = 0
                    setCardBackgroundColor(resolveThemeColor(MaterialR.attr.colorSurfaceContainerLow))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dimen(R.dimen.sillydroid_field_vertical_spacing)
                    }
                }
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_space_lg), dimen(R.dimen.sillydroid_section_padding), dimen(R.dimen.sillydroid_space_lg))
                }
                val textContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textContainer.addView(TextView(activity).apply {
                    text = field.title
                    TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsCardTitle)
                    setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurface))
                })
                textContainer.addView(TextView(activity).apply {
                    text = field.summary
                    TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SillyDroid_SettingsBody)
                    setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant))
                    setPadding(0, dimen(R.dimen.sillydroid_space_xs), dimen(R.dimen.sillydroid_space_lg), 0)
                })
                val switch = MaterialSwitch(activity).apply {
                    showText = false
                    scaleX = 0.68f
                    scaleY = 0.68f
                    minimumHeight = 0
                    minHeight = 0
                    isChecked = when (currentValue) {
                        is Boolean -> currentValue
                        is String -> currentValue.equals("true", ignoreCase = true)
                        else -> field.defaultValue as? Boolean ?: false
                    }
                }
                fieldBindings[field.path] = SwitchBinding(field.path, card, switch)
                registerSearchBinding(
                    field = field,
                    targetView = card,
                    applyMatchedState = { matched ->
                        card.strokeWidth = if (matched) dp(2) else 0
                        card.strokeColor = if (matched) {
                            resolveThemeColor(MaterialR.attr.colorPrimary)
                        } else {
                            resolveThemeColor(MaterialR.attr.colorOutlineVariant)
                        }
                    }
                )
                switch.setOnCheckedChangeListener { _, _ ->
                    onFieldEdited(null)
                    updateFieldVisibility()
                    onFormChanged()
                }
                container.addView(textContainer)
                container.addView(switch)
                card.addView(container)
                card
            }

            else -> {
                val fieldContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dimen(R.dimen.sillydroid_field_vertical_spacing)
                    }
                }
                val inputLayout = TextInputLayout(
                    ContextThemeWrapper(activity, R.style.Widget_SillyDroid_SettingsTextInputLayout_OutlinedBox)
                ).apply {
                    hint = field.title
                    helperText = field.summary
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    setBoxCornerRadii(dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius))
                    setPadding(0, 0, 0, 0)
                    if (field.kind == TavernConfigFieldKind.PASSWORD) {
                        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val editText = TextInputEditText(
                    ContextThemeWrapper(inputLayout.context, R.style.Widget_SillyDroid_SettingsTextInputEditText)
                ).apply {
                    setText(formatFieldValue(field, currentValue))
                    inputType = resolveInputType(field.kind)
                    setHorizontallyScrolling(false)
                    when (field.kind) {
                        TavernConfigFieldKind.STRING_LIST,
                        TavernConfigFieldKind.MULTILINE_TEXT -> {
                            minLines = 2
                            maxLines = 4
                            gravity = Gravity.TOP or Gravity.START
                        }

                        TavernConfigFieldKind.INTEGER,
                        TavernConfigFieldKind.PASSWORD -> {
                            isSingleLine = true
                        }

                        else -> {
                            minLines = 1
                            maxLines = 3
                            gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        }
                    }
                    setOnFocusChangeListener { focusedView, hasFocus ->
                        if (hasFocus) {
                            ensureViewVisible(focusedView)
                        }
                    }
                }
                inputLayout.addView(editText)
                fieldContainer.addView(inputLayout)
                fieldBindings[field.path] = TextBinding(field.path, fieldContainer, inputLayout, editText)
                registerSearchBinding(
                    field = field,
                    targetView = fieldContainer,
                    applyMatchedState = { matched ->
                        fieldContainer.background = if (matched) {
                            GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dimenFloat(R.dimen.sillydroid_nested_card_radius)
                                setColor(resolveThemeColor(MaterialR.attr.colorSurface))
                                setStroke(dp(2), resolveThemeColor(MaterialR.attr.colorPrimary))
                            }
                        } else {
                            null
                        }
                    }
                )
                editText.doAfterTextChanged {
                    onFieldEdited(field.path)
                    onFormChanged()
                }
                fieldContainer
            }
        }
    }

    private fun createPortFieldView(
        field: TavernConfigFieldSpec,
        currentValue: Any?,
        registerBinding: Boolean = true,
        enabled: Boolean = true
    ): View {
        val defaultPortText = defaultServicePort.toString()

        val fieldContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_field_vertical_spacing)
            }
        }

        val rowLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val inputLayout = TextInputLayout(
            ContextThemeWrapper(activity, R.style.Widget_SillyDroid_SettingsTextInputLayout_OutlinedBox)
        ).apply {
            hint = field.title
            helperText = field.summary
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius), dimenFloat(R.dimen.sillydroid_nested_card_radius))
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val editText = TextInputEditText(
            ContextThemeWrapper(inputLayout.context, R.style.Widget_SillyDroid_SettingsTextInputEditText)
        ).apply {
            setText(formatFieldValue(field, currentValue))
            inputType = resolveInputType(field.kind)
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isEnabled = enabled
            setOnFocusChangeListener { focusedView, hasFocus ->
                if (hasFocus) {
                    ensureViewVisible(focusedView)
                } else if (text.isNullOrBlank()) {
                    setText(defaultPortText)
                    setSelection(defaultPortText.length)
                }
            }
        }
        inputLayout.addView(editText)

        val randomButton = ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.sillydroid_icon_button_size), dimen(R.dimen.sillydroid_icon_button_size)).apply {
                marginStart = dimen(R.dimen.sillydroid_space_md)
                topMargin = dimen(R.dimen.sillydroid_field_vertical_spacing)
            }
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dimen(R.dimen.sillydroid_icon_button_padding), dimen(R.dimen.sillydroid_icon_button_padding), dimen(R.dimen.sillydroid_icon_button_padding), dimen(R.dimen.sillydroid_icon_button_padding))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dimenFloat(R.dimen.sillydroid_nested_card_radius)
                setColor(resolveThemeColor(MaterialR.attr.colorSurfaceContainerHigh))
                setStroke(dp(1), resolveThemeColor(MaterialR.attr.colorOutlineVariant))
            }
            setImageResource(R.drawable.ic_port_random)
            imageTintList = ColorStateList.valueOf(resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant))
            contentDescription = activity.getString(R.string.bootstrap_settings_port_randomize)
            isEnabled = enabled
        }
        randomButton.setOnClickListener {
            val currentPort = editText.text?.toString()?.trim()?.toIntOrNull()
            val randomPort = findAvailableRandomPort(currentPort)
            editText.setText(randomPort.toString())
            editText.setSelection(editText.text?.length ?: 0)
            editText.requestFocus()
            ensureViewVisible(editText)
        }

        rowLayout.addView(inputLayout)
        rowLayout.addView(randomButton)
        fieldContainer.addView(rowLayout)
        if (registerBinding) {
            fieldBindings[field.path] = TextBinding(
                path = field.path,
                containerView = fieldContainer,
                inputLayout = inputLayout,
                input = editText,
                blankFallbackText = defaultPortText
            )
            editText.doAfterTextChanged {
                onFieldEdited(field.path)
                onFormChanged()
            }
        }
        return fieldContainer
    }

    private fun formatFieldValue(field: TavernConfigFieldSpec, rawValue: Any?): String {
        return when (field.kind) {
            TavernConfigFieldKind.STRING_LIST -> {
                val listValue = rawValue as? Iterable<*>
                listValue?.mapNotNull { item -> item?.toString()?.trim() }
                    ?.filter { item -> item.isNotBlank() }
                    ?.joinToString(separator = "\n")
                    .orEmpty()
            }

            else -> rawValue?.toString().orEmpty()
        }
    }

    private fun resolveInputType(kind: TavernConfigFieldKind): Int {
        return when (kind) {
            TavernConfigFieldKind.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            TavernConfigFieldKind.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            TavernConfigFieldKind.MULTILINE_TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            TavernConfigFieldKind.STRING_LIST -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
    }

    private fun updateFieldVisibility() {
        for (sectionBinding in sectionBindings) {
            var sectionHasVisibleField = false
            for (fieldPath in sectionBinding.fieldPaths) {
                val spec = configRepository.fieldsByPath[fieldPath] ?: continue
                val visible = shouldFieldBeVisible(spec)
                fieldBindings[fieldPath]?.containerView?.isVisible = visible
                if (visible) {
                    sectionHasVisibleField = true
                }
            }
            syncSectionExpansion(sectionBinding, sectionHasVisibleField)
        }
        if (currentSearchQuery.isNotBlank()) {
            applySearchQuery(currentSearchQuery, scrollToFirstMatch = false)
        }
    }

    private fun syncSectionExpansion(sectionBinding: SectionBinding, hasVisibleFields: Boolean, animate: Boolean = false) {
        if (animate && hasVisibleFields) {
            TransitionManager.beginDelayedTransition(
                sectionBinding.cardView.parent as? ViewGroup ?: sectionContainer,
                ChangeBounds().apply {
                    duration = 220
                    interpolator = AccelerateDecelerateInterpolator()
                }
            )
        }
        sectionBinding.cardView.isVisible = hasVisibleFields
        sectionBinding.dividerView.isVisible = hasVisibleFields && sectionBinding.expanded
        sectionBinding.contentView.isVisible = hasVisibleFields && sectionBinding.expanded
        sectionBinding.toggleView.text = activity.getString(
            if (sectionBinding.expanded) {
                R.string.bootstrap_settings_section_collapse
            } else {
                R.string.bootstrap_settings_section_expand
            }
        )
    }

    private fun shouldFieldBeVisible(spec: TavernConfigFieldSpec): Boolean {
        return spec.visibleWhenAllEnabled.all(::isDependencyEnabled) &&
            (spec.visibleWhenAnyEnabled.isEmpty() || spec.visibleWhenAnyEnabled.any(::isDependencyEnabled))
    }

    private fun isDependencyEnabled(path: String): Boolean {
        val value = fieldBindings[path]?.currentValue() ?: configRepository.readValue(currentRoot, path)
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun ensureViewVisible(targetView: View) {
        scrollView.post {
            val targetRect = Rect()
            targetView.getDrawingRect(targetRect)
            scrollView.offsetDescendantRectToMyCoords(targetView, targetRect)

            val topSpacing = dimen(R.dimen.sillydroid_scroll_focus_spacing_top)
            val bottomSpacing = dimen(R.dimen.sillydroid_scroll_focus_spacing_bottom)
            val viewportTop = scrollView.scrollY
            val viewportBottom = scrollView.scrollY + scrollView.height - scrollView.paddingBottom

            val desiredScrollY = when {
                targetRect.bottom + bottomSpacing > viewportBottom -> {
                    targetRect.bottom + bottomSpacing - (scrollView.height - scrollView.paddingBottom)
                }

                targetRect.top - topSpacing < viewportTop -> {
                    targetRect.top - topSpacing
                }

                else -> scrollView.scrollY
            }.coerceAtLeast(0)

            if (desiredScrollY != scrollView.scrollY) {
                scrollView.smoothScrollTo(0, desiredScrollY)
            }
        }
    }

    private fun findAvailableRandomPort(excludedPort: Int?): Int {
        repeat(24) {
            ServerSocket(0).use { socket ->
                socket.reuseAddress = true
                val candidate = socket.localPort
                if (candidate in 1024..65535 && candidate != excludedPort) {
                    return candidate
                }
            }
        }

        return excludedPort?.takeIf { it in 1024..65535 } ?: defaultServicePort
    }

    private fun shouldRenderField(path: String): Boolean {
        return !path.startsWith("browserLaunch.")
    }

    private fun registerSearchBinding(
        field: TavernConfigFieldSpec,
        targetView: View,
        applyMatchedState: (Boolean) -> Unit
    ) {
        fieldSearchBindings[field.path] = SearchBinding(
            path = field.path,
            searchText = listOf(field.path, field.title, field.summary)
                .joinToString(separator = "\n")
                .lowercase(),
            targetView = targetView,
            applyMatchedState = applyMatchedState
        )
    }

    private fun resolveThemeColor(@AttrRes attrRes: Int, fallback: Int = 0): Int {
        return MaterialColors.getColor(activity, attrRes, fallback)
    }

    private fun resolveThemeResource(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        activity.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.resourceId
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private fun dimenFloat(resId: Int): Float {
        return activity.resources.getDimension(resId)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }

    private fun dpFloat(value: Int): Float {
        return dp(value).toFloat()
    }
}
