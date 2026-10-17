package app.folio.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.folio.core.data.modules.InstallState
import app.folio.core.data.modules.PdfModule
import app.folio.core.designsystem.Folio
import app.folio.core.designsystem.FolioType
import app.folio.core.designsystem.components.GroupHeader
import app.folio.core.designsystem.components.GroupedCard
import app.folio.core.designsystem.components.GroupedRow
import app.folio.core.designsystem.components.IosSwitch

/**
 * «Модули»: book viewers from the modules repository (Folio Документы) with install/update, and whether PDFs
 * open there. Speech engines stay in the «Голос» sheet. Hidden until the index lists a viewer.
 */
@Composable
internal fun ModulesGroup(vm: SettingsViewModel) {
    val viewers by vm.viewers.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val pdfInModule by vm.pdfInModule.collectAsStateWithLifecycle()
    if (viewers.isEmpty()) return
    val c = Folio.colors
    GroupHeader(stringResource(R.string.settings_modules))
    GroupedCard {
        val pdfInstalled = PdfModule.PACKAGE in installed
        viewers.forEachIndexed { i, m ->
            val have = installed[m.packageName]
            val state = states[m.packageName] ?: InstallState.Idle
            val busy = state is InstallState.Downloading || state == InstallState.Installing
            val action = when {
                state != InstallState.Idle -> installAction(state)
                have == null -> stringResource(R.string.settings_module_install)
                have < m.versionCode -> stringResource(R.string.settings_module_update, m.versionName)
                else -> null
            }
            GroupedRow(
                m.title,
                subtitle = m.description.takeIf { it.isNotBlank() },
                value = if (action == null) m.versionName else null,
                valueStyle = FolioType.subheadline,
                showDivider = i < viewers.lastIndex || pdfInstalled,
                trailing = action?.let { { Text(it, style = FolioType.body, color = c.accent, modifier = Modifier.padding(start = 12.dp)) } },
                onClick = if (action != null && !busy) ({ vm.install(m) }) else null,
            )
        }
        if (pdfInstalled) {
            GroupedRow(
                stringResource(R.string.settings_pdf_in_module),
                showDivider = false,
                trailing = { IosSwitch(pdfInModule, vm::setPdfInModule) },
            )
        }
    }
}
