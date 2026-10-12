package io.legado.app.ui.code.config

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setLayout

class ChangeThemeDialog : BaseComposeDialogFragment() {
    private var callBack: CallBack? = null
    private var editTemeAuto by mutableStateOf(AppConfig.editTemeAuto)
    private val isDark
        get() = editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex by mutableIntStateOf(-1)

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is CallBack) {
            callBack = context
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        themeIndex = if (isDark) {
            AppConfig.editThemeDark
        } else {
            AppConfig.editTheme
        }
        callBack?.upTheme(themeIndex)
    }

    private fun selectTheme(index: Int) {
        themeIndex = index
        if (isDark) {
            putPrefInt(PreferKey.editThemeDark, index)
        } else {
            putPrefInt(PreferKey.editTheme, index)
        }
        callBack?.upTheme(index)
    }

    @Composable
    override fun Content() {
        val leftThemes = listOf(
            0 to stringResource(R.string.edit_theme_monokai_dimmed),
            2 to stringResource(R.string.edit_theme_modern_dark),
            4 to stringResource(R.string.edit_theme_solarized_dark),
            6 to stringResource(R.string.edit_theme_abyss)
        )
        val rightThemes = listOf(
            1 to stringResource(R.string.edit_theme_monokai),
            3 to stringResource(R.string.edit_theme_modern_light),
            5 to stringResource(R.string.edit_theme_solarized_light),
            7 to stringResource(R.string.edit_theme_quiet_light)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.change_theme),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.system_auto))
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = editTemeAuto,
                            onCheckedChange = { isChecked ->
                                putPrefBoolean(PreferKey.editTemeAuto, isChecked)
                                editTemeAuto = isChecked
                                initData()
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        leftThemes.forEach { (idx, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectTheme(idx) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = themeIndex == idx,
                                    onClick = { selectTheme(idx) }
                                )
                                Text(text = label, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        rightThemes.forEach { (idx, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectTheme(idx) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = themeIndex == idx,
                                    onClick = { selectTheme(idx) }
                                )
                                Text(text = label, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    interface CallBack {
        fun upTheme(index: Int)
    }

}