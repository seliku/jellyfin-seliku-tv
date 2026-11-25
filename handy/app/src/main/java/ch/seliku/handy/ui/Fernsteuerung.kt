package ch.seliku.handy.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.SessionInfoDto

/** Titel zum Abspielen an ein anderes Gerät schicken (z. B. die Seliku-App am Fernseher), über Jellyfins Fernsteuerung. */
object Fernsteuerung { var titel by mutableStateOf<BaseItemDto?>(null) }

@Composable
fun GeraetWahl() {
	val i = Fernsteuerung.titel ?: return
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var geraete by remember { mutableStateOf<List<SessionInfoDto>?>(null) }
	LaunchedEffect(i.id) {
		geraete = runCatching {
			Sitzung.api!!.sessionApi.getSessions(controllableByUserId = Sitzung.benutzerId, activeWithinSeconds = 960).content
				.filter { it.supportsRemoteControl == true && it.deviceId != Sitzung.geraeteId }
		}.getOrDefault(emptyList())
	}
	AlertDialog(onDismissRequest = { Fernsteuerung.titel = null }, confirmButton = {}, dismissButton = { TextButton({ Fernsteuerung.titel = null }) { Text("Abbrechen") } },
		title = { Text("Abspielen auf …") }, text = {
			Column {
				val g = geraete
				if (g == null) CircularProgressIndicator(color = Farben.Akzent)
				else if (g.isEmpty()) Text("Kein Gerät gefunden. Die Seliku-App am Fernseher muss geöffnet sein.", color = Farben.TextLeise)
				else g.forEach { s ->
					Column(Modifier.fillMaxWidth().clickable {
						scope.launch {
							val pos = (i.userData?.playbackPositionTicks ?: 0L).let { if (i.userData?.played == true) 0L else (it - 50_000_000L).coerceAtLeast(0) }
							runCatching { Sitzung.api!!.sessionApi.play(sessionId = s.id!!, playCommand = PlayCommand.PLAY_NOW, itemIds = listOf(i.id), startPositionTicks = pos) }
								.onSuccess { Toast.makeText(c, "Läuft auf ${s.deviceName}", Toast.LENGTH_SHORT).show() }
								.onFailure { Toast.makeText(c, "Gerät reagiert nicht", Toast.LENGTH_SHORT).show() }
							Fernsteuerung.titel = null
						}
					}.padding(vertical = 10.dp)) {
						Text(s.deviceName.orEmpty())
						Text(listOfNotNull(s.client, s.nowPlayingItem?.name?.let { "spielt: $it" }).joinToString(" · "), color = Farben.TextLeise, style = MaterialTheme.typography.bodyMedium)
					}
				}
			}
		})
}
