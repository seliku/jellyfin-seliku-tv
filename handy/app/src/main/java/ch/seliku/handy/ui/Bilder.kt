package ch.seliku.handy.ui

import ch.seliku.handy.Sitzung
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Bildadressen wie in der TV-App: Poster in Reihen, Hintergrund/Thumb für Banner und Folgen. */
object Bilder {
	private fun url(id: UUID, typ: String, tag: String?, breite: Int, index: Int? = null): String =
		"${Sitzung.server}/Items/$id/Images/$typ${index?.let { "/$it" } ?: ""}?maxWidth=$breite&quality=90" + (tag?.let { "&tag=$it" } ?: "")

	/** Poster (hochkant). Folgen zeigen das Serienposter, ausser [eigenesBild]. */
	fun poster(i: BaseItemDto, breite: Int = 360): String? {
		if (i.type == BaseItemKind.EPISODE) {
			val sid = i.seriesId ?: return null
			return url(sid, "Primary", i.seriesPrimaryImageTag, breite)
		}
		val tag = i.imageTags?.get(ImageType.PRIMARY)
		if (tag != null) return url(i.id, "Primary", tag, breite)
		if (i.albumId != null && i.albumPrimaryImageTag != null) return url(i.albumId!!, "Primary", i.albumPrimaryImageTag, breite)
		return null
	}

	/** Querformat: Thumb, sonst Hintergrund, sonst Bild der Folge. */
	fun quer(i: BaseItemDto, breite: Int = 720): String? {
		if (i.type == BaseItemKind.EPISODE) {
			i.imageTags?.get(ImageType.PRIMARY)?.let { return url(i.id, "Primary", it, breite) }
			i.parentThumbItemId?.let { return url(it, "Thumb", i.parentThumbImageTag, breite) }
			i.parentBackdropItemId?.let { return url(it, "Backdrop", i.parentBackdropImageTags?.firstOrNull(), breite, 0) }
		}
		i.imageTags?.get(ImageType.THUMB)?.let { return url(i.id, "Thumb", it, breite) }
		i.backdropImageTags?.firstOrNull()?.let { return url(i.id, "Backdrop", it, breite, 0) }
		i.parentBackdropItemId?.let { return url(it, "Backdrop", i.parentBackdropImageTags?.firstOrNull(), breite, 0) }
		return poster(i, breite)
	}

	fun hintergrund(i: BaseItemDto, breite: Int = 1280): String? {
		i.backdropImageTags?.firstOrNull()?.let { return url(i.id, "Backdrop", it, breite, 0) }
		i.parentBackdropItemId?.let { return url(it, "Backdrop", i.parentBackdropImageTags?.firstOrNull(), breite, 0) }
		if (i.type == BaseItemKind.EPISODE) i.seriesId?.let { return url(it, "Backdrop", null, breite, 0) }
		return null
	}

	fun logo(i: BaseItemDto, breite: Int = 600): String? {
		i.imageTags?.get(ImageType.LOGO)?.let { return url(i.id, "Logo", it, breite) }
		i.parentLogoItemId?.let { return url(it, "Logo", i.parentLogoImageTag, breite) }
		return null
	}

	fun item(id: UUID, typ: String = "Primary", breite: Int = 360) = url(id, typ, null, breite)
}
