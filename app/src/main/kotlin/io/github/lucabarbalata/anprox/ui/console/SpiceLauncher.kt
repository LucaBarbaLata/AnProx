package io.github.lucabarbalata.anprox.ui.console

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.console.ConsoleRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.pve.SpiceFile
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.resolve
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * Opens a VM in a SPICE viewer app (questionnaire Q15): asks Proxmox for a one-time `.vv` file,
 * writes it to the cache and hands it to whatever app opens `application/x-virt-viewer`
 * (aSPICE, for example). Problems are shown in [snackbar].
 */
@Composable
fun rememberSpiceLauncher(snackbar: SnackbarHostState): (GuestRef) -> Unit {
    val context = LocalContext.current
    val consoles: ConsoleRepository = koinInject()
    val scope = rememberCoroutineScope()
    val noViewer = stringResource(R.string.spice_no_viewer)
    return remember(context, consoles, scope, noViewer) {
        { ref ->
            scope.launch {
                val text = try {
                    consoles.spiceFile(ref.serverId, ref.node, ref.vmid)
                } catch (e: ApiException) {
                    snackbar.showSnackbar(e.toUiText().resolve(context))
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "spice").apply { mkdirs() }.resolve("vm-${ref.vmid}.vv").apply { writeText(text) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, SpiceFile.MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(view)
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar(noViewer)
                }
            }
        }
    }
}
