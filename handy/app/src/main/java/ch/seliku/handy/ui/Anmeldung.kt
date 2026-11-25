package ch.seliku.handy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.seliku.handy.R
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Anmeldung: Server, dann Benutzer/Passwort, Quick Connect oder SSO (wenn der Server es anbietet). */
@Composable
fun Anmeldung() {
	val scope = rememberCoroutineScope()
	var server by remember { mutableStateOf(Sitzung.server.removePrefix("https://")) }
	var name by remember { mutableStateOf("") }
	var passwort by remember { mutableStateOf("") }
	var laeuft by remember { mutableStateOf(false) }
	var fehler by remember { mutableStateOf<String?>(null) }
	var code by remember { mutableStateOf<String?>(null) }
	var qcJob by remember { mutableStateOf<Job?>(null) }
	var sso by remember { mutableStateOf<Pair<String, String>?>(null) }   // Anbieter + Knopftext des Servers
	var ssoServer by remember { mutableStateOf<String?>(null) }
	var ssoOffen by remember { mutableStateOf(false) }

	// SSO-Knopf nur zeigen, wenn der Server einen anbietet (SSO-Plugin, Knopf im Anmeldehinweis).
	LaunchedEffect(server) {
		sso = null
		if (server.isBlank()) return@LaunchedEffect
		kotlinx.coroutines.delay(600)
		val s = runCatching { Sitzung.serverPruefen(server) }.getOrNull() ?: return@LaunchedEffect
		ssoServer = s; sso = Sitzung.ssoAnbieter(s)
	}
	if (ssoOffen && sso != null && ssoServer != null) {
		SsoFenster(ssoServer!!, sso!!.first) { token ->
			ssoOffen = false
			if (token != null) { laeuft = true; scope.launch {
				runCatching { Sitzung.ssoUebernehmen(ssoServer!!, token) }.onFailure { fehler = "SSO-Anmeldung fehlgeschlagen" }
				laeuft = false
			} }
		}
		return
	}

	Box(Modifier.fillMaxSize().selikuHintergrund().imePadding(), contentAlignment = Alignment.Center) {
		Column(
			Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
		) {
			androidx.compose.foundation.Image(painterResource(R.drawable.app_icon_foreground), null, Modifier.size(120.dp))
			Text("Jellyfin Seliku", style = MaterialTheme.typography.headlineMedium)
			Spacer(Modifier.height(8.dp))
			OutlinedTextField(server, { server = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
			OutlinedTextField(name, { name = it }, label = { Text("Benutzer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
			OutlinedTextField(passwort, { passwort = it }, label = { Text("Passwort") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
				visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
			fehler?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
			code?.let {
				Text("In einer angemeldeten Jellyfin-App unter «Quick Connect» diesen Code eingeben:", color = Farben.TextLeise, textAlign = TextAlign.Center)
				Text(it, fontSize = 36.sp, color = Farben.Akzent, style = MaterialTheme.typography.headlineLarge)
			}
			if (laeuft) CircularProgressIndicator(color = Farben.Akzent)
			Button(
				onClick = {
					laeuft = true; fehler = null
					scope.launch {
						runCatching { Sitzung.anmelden(Sitzung.serverPruefen(server), name.trim(), passwort) }
							.onFailure { fehler = if (it.message?.contains("401") == true) "Benutzer oder Passwort falsch" else (it.message ?: "Anmeldung fehlgeschlagen") }
						laeuft = false
					}
				},
				enabled = !laeuft && name.isNotBlank() && server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp),
			) { Text("Anmelden") }
			OutlinedButton(
				onClick = {
					qcJob?.cancel(); fehler = null; laeuft = true
					qcJob = scope.launch {
						runCatching { Sitzung.quickConnect(Sitzung.serverPruefen(server)) { code = it } }
							.onFailure { fehler = "Quick Connect nicht möglich (${it.message ?: "Fehler"})" }
						laeuft = false; code = null
					}
				},
				enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp),
			) { Text("Mit Quick Connect anmelden") }
			sso?.let { (_, text) ->
				Button(
					onClick = { fehler = null; ssoOffen = true }, enabled = !laeuft,
					colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Farben.Akzent),
					modifier = Modifier.fillMaxWidth().height(50.dp),
				) { Text(text) }
			}
		}
	}
}
