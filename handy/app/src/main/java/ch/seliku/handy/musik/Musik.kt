package ch.seliku.handy.musik

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.seliku.handy.Sitzung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy

/** Steuert den Musikdienst (Warteschlange, Radio). Zustand für Mini-Player und «Läuft gerade». */
object Musik {
	private var controller: MediaController? = null
	private var verbindet = false
	private val warten = mutableListOf<(MediaController) -> Unit>()

	var laeuft by mutableStateOf(false); private set
	var spielt by mutableStateOf(false); private set
	var titel by mutableStateOf<MediaMetadata?>(null); private set
	var istRadio by mutableStateOf(false); private set
	var endlosAktiv by mutableStateOf(false)
	var position by mutableLongStateOf(0L)
	var dauer by mutableLongStateOf(0L)
	var zufall by mutableStateOf(false); private set
	var wiederholen by mutableStateOf(false); private set
	var warteschlange by mutableStateOf<List<MediaMetadata>>(emptyList()); private set
	var index by mutableStateOf(0); private set
	var aktuelleIdState by mutableStateOf<String?>(null); private set

	private fun mit(c: Context, f: (MediaController) -> Unit) {
		controller?.let { f(it); return }
		warten += f
		if (verbindet) return
		verbindet = true
		val token = SessionToken(c, ComponentName(c, MusikDienst::class.java))
		val fut = MediaController.Builder(c.applicationContext, token).buildAsync()
		fut.addListener({
			val m = fut.get(); controller = m; verbindet = false
			m.addListener(object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { lesen(player) } })
			lesen(m)
			warten.toList().forEach { it(m) }; warten.clear()
		}, ContextCompat.getMainExecutor(c))
	}

	private fun lesen(p: Player) {
		laeuft = p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE
		spielt = p.isPlaying
		titel = p.mediaMetadata.takeIf { p.mediaItemCount > 0 }
		istRadio = p.currentMediaItem?.mediaId?.startsWith("radio:") == true
		zufall = p.shuffleModeEnabled; wiederholen = p.repeatMode == Player.REPEAT_MODE_ONE
		index = p.currentMediaItemIndex
		aktuelleIdState = p.currentMediaItem?.mediaId
		warteschlange = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaMetadata }
		position = p.currentPosition; dauer = p.duration.coerceAtLeast(0)
		if (!laeuft) endlosAktiv = false
	}

	/** Für den Takt der Anzeige (Position). */
	fun auffrischen() { controller?.let { position = it.currentPosition; dauer = it.duration.coerceAtLeast(0) } }

	fun mediaItem(i: BaseItemDto, hoer: String? = null): MediaItem {
		val bild = (i.albumId?.takeIf { i.albumPrimaryImageTag != null } ?: i.id).let { "${Sitzung.server}/Items/$it/Images/Primary?maxWidth=600" }
		return MediaItem.Builder().setMediaId(i.id.toString())
			.setUri("${Sitzung.server}/Audio/${i.id}/stream?static=true")
			.setMediaMetadata(MediaMetadata.Builder().setTitle(i.name).setArtist(i.artists?.joinToString(", ") ?: i.albumArtist)
				.setAlbumTitle(i.album).setArtworkUri(Uri.parse(bild))
				.setExtras(android.os.Bundle().apply { putString("album", i.albumId?.toString()); putString("hoer", hoer); putString("interpret", (i.albumArtists?.firstOrNull()?.id ?: i.artistItems?.firstOrNull()?.id)?.toString()) })
				.build()).build()
	}

	/** Titel abspielen ab [ab] (Album ab diesem Titel, wie am TV). */
	fun spielen(c: Context, titel: List<BaseItemDto>, ab: Int, zufall: Boolean = false) = mit(c) { m ->
		endlosAktiv = false
		m.shuffleModeEnabled = zufall; m.repeatMode = Player.REPEAT_MODE_OFF
		m.setMediaItems(titel.map { mediaItem(it) }, ab.coerceIn(0, (titel.size - 1).coerceAtLeast(0)), 0)
		m.prepare(); m.play()
	}

	/** Album, Playlist, Interpret oder Titel abspielen. */
	suspend fun spielenItem(c: Context, i: BaseItemDto, zufall: Boolean = false) {
		val liste = titelVon(i)
		if (liste.isNotEmpty()) withContext(Dispatchers.Main) { spielen(c, liste, 0, zufall) }
	}

	suspend fun titelVon(i: BaseItemDto): List<BaseItemDto> = withContext(Dispatchers.IO) {
		val d = Sitzung.daten
		when (i.type) {
			BaseItemKind.AUDIO -> listOf(i)
			BaseItemKind.MUSIC_ALBUM -> d.titel(i.id)
			BaseItemKind.PLAYLIST -> d.playlistTitel(i.id)
			BaseItemKind.MUSIC_ARTIST -> d.titelVon(i.id, beliebteste = false)
			else -> emptyList()
		}
	}

	/** «Als Nächstes» (gleich danach) oder «Hinten anstellen» (vor dem Endlos-Mix). */
	suspend fun einreihen(c: Context, i: BaseItemDto, gleich: Boolean) {
		val neu = titelVon(i).map { mediaItem(it) }
		withContext(Dispatchers.Main) {
			mit(c) { m ->
				if (m.mediaItemCount == 0) { m.setMediaItems(neu); m.prepare(); m.play() }
				else m.addMediaItems(if (gleich) m.currentMediaItemIndex + 1 else m.mediaItemCount, neu)
			}
		}
	}

	fun radio(c: Context, s: RadioSender) = mit(c) { m ->
		m.shuffleModeEnabled = false; m.repeatMode = Player.REPEAT_MODE_OFF
		m.setMediaItem(MediaItem.Builder().setMediaId("radio:${s.id}").setUri(s.url)
			.setMediaMetadata(MediaMetadata.Builder().setTitle(s.name).setArtist("Radio · ${s.land}").setArtworkUri(s.logo?.let { Uri.parse(it) }).build()).build())
		m.prepare(); m.play()
	}

	/** Hörbuch ab gemerkter Datei und Stelle (minus Zurückspulen nach Pausenlänge); [ab] = von diesem Kapitel an. */
	suspend fun buchSpielen(c: Context, buch: BaseItemDto, ab: Int? = null) {
		Hoeren.laden()
		val dateien = Hoeren.buchDateien(buch.id); if (dateien.isEmpty()) return
		val stand = Hoeren.wert("b:${buch.id}")?.split("|")
		var index = 0; var pos = 0L
		if (ab != null) index = ab
		else if (stand != null && stand.size >= 3) {
			index = dateien.indexOfFirst { it.id.toString() == stand[0] }.coerceAtLeast(0)
			pos = ((stand[1].toLongOrNull() ?: 0) - Hoeren.rueckspulen(stand[2].toLongOrNull() ?: 0)).coerceAtLeast(0)
		}
		Hoeren.setzen("f:${buch.id}", null)
		val tempo = Hoeren.wert("t:${buch.id}")?.toFloatOrNull() ?: 1f
		withContext(Dispatchers.Main) { mit(c) { m ->
			endlosAktiv = false; m.shuffleModeEnabled = false; m.repeatMode = Player.REPEAT_MODE_OFF
			m.setMediaItems(dateien.map { mediaItem(it, "b:${buch.id}") }, index, pos); m.setPlaybackSpeed(tempo); m.prepare(); m.play()
		} }
	}

	/** Podcast-Folge an der gemerkten Stelle, danach die nächsten ungehörten desselben Podcasts in Erscheinungsreihenfolge. */
	suspend fun folgeSpielen(c: Context, folge: BaseItemDto) {
		Hoeren.laden()
		val alle = folge.albumId?.let { runCatching { Hoeren.folgen(it) }.getOrNull() }.orEmpty().reversed()
		val danach = alle.dropWhile { it.id != folge.id }.drop(1).filter { !Hoeren.gehoert(it) }
		val stand = Hoeren.wert("p:${folge.id}")?.split("|")
		val pos = stand?.let { ((it[0].toLongOrNull() ?: 0) - Hoeren.rueckspulen(it.getOrNull(1)?.toLongOrNull() ?: 0)).coerceAtLeast(0) } ?: 0L
		val tempo = folge.albumId?.let { Hoeren.wert("t:$it")?.toFloatOrNull() } ?: 1f
		withContext(Dispatchers.Main) { mit(c) { m ->
			endlosAktiv = false; m.shuffleModeEnabled = false; m.repeatMode = Player.REPEAT_MODE_OFF
			m.setMediaItems((listOf(folge) + danach).map { mediaItem(it, "p") }, 0, pos); m.setPlaybackSpeed(tempo); m.prepare(); m.play()
		} }
	}

	/** Hörart des laufenden Titels: «b:<Buch>», «p» oder null (Musik). */
	val hoerArt: String? get() = titel?.extras?.getString("hoer")
	val tempo: Float get() = controller?.playbackParameters?.speed ?: 1f
	/** Tempo-Stufen wie am TV; gilt je Buch bzw. Podcast. */
	fun tempoWeiter() {
		val m = controller ?: return
		val stufen = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
		val neu = stufen[(stufen.indexOfFirst { it >= m.playbackParameters.speed - 0.01f } + 1).mod(stufen.size)]
		m.setPlaybackSpeed(neu)
		val k = hoerArt?.let { if (it.startsWith("b:")) it.drop(2) else titel?.extras?.getString("album") } ?: return
		Hoeren.setzen("t:$k", if (neu == 1f) null else neu.toString())
	}
	fun springenUm(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) } }
	/** Schlaf-Timer: Minuten (0 = aus); pausiert, nicht stoppt. */
	var schlafBis by mutableLongStateOf(0L)

	fun radioStoppen() { controller?.let { if (it.currentMediaItem?.mediaId?.startsWith("radio:") == true) it.stop() } }
	/** Vor einem Video: Musik pausieren (nicht verlieren). */
	fun pausieren() { controller?.pause() }

	fun pause() { controller?.pause() }
	fun spielenPause() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
	fun weiter() { controller?.seekToNextMediaItem() }
	/** Nach mehr als 3 s an den Anfang, sonst zum vorigen Titel (wie am TV). */
	fun zurueck() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
	fun springen(ms: Long) { controller?.seekTo(ms) }
	fun zu(index: Int) { controller?.seekTo(index, 0) }
	fun zufallWechseln() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
	fun wiederholenWechseln() { controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE } }
	fun stoppen() { controller?.let { it.stop(); it.clearMediaItems() } }
	fun aktuelleId(): String? = controller?.currentMediaItem?.mediaId
}
