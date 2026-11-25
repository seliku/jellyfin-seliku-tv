// Wohin ein Klick führt (wie `seiteFuer` am TV): Folgen, Lieder und Sender spielen direkt.
import * as D from './daten.js';
import { spielen } from './spieler.js';
import { musikSpielen } from './musik.js';

export const geh = (h) => { location.hash = h; };
export function oeffnen(i) {
	switch (i.Type) {
		case 'Series': case 'Movie': case 'Video': case 'MusicVideo': case 'Trailer': return geh(`#/detail/${i.Id}`);
		case 'Season': return geh(`#/detail/${i.SeriesId || i.Id}`);
		case 'MusicAlbum': case 'Playlist': return geh(`#/album/${i.Id}`);
		case 'MusicArtist': return geh(`#/interpret/${i.Id}`);
		case 'Folder': case 'CollectionFolder': case 'BoxSet': return geh(`#/ordner/${i.Id}`);
		case 'Audio': case 'AudioBook': return musikSpielen([i], 0);
		default: return abspielen(i);
	}
}
/** Wie `Abspielen.video`: Ausblendung aufheben, Gerät merken, 5 s vor der gemerkten Stelle. */
export function abspielen(i, vonVorn = false) {
	D.wiederAufnehmen(i).catch(() => {});
	const g = (localStorage.getItem('seliku-geraet-verlauf') || '').split(',').filter(x => x && x !== (i.SeriesId || i.Id));
	localStorage.setItem('seliku-geraet-verlauf', [i.SeriesId || i.Id, ...g].slice(0, 400).join(','));
	const pos = vonVorn ? 0 : Math.max(0, (i.UserData?.PlaybackPositionTicks || 0) / 1e4 - 5000);
	spielen(i.Id, pos);
}
export async function serieAbspielen(serie, zufall = false) {
	const f = zufall ? D.mischen(await D.alleFolgen(serie))[0] : (await D.naechsteFolge(serie)) || (await D.alleFolgen(serie))[0];
	if (f) abspielen(f, zufall);
}
export const musik = async (i) => { const { titelVon } = await import('./musik.js'); musikSpielen(await titelVon(i), 0); };
export const geraetVerlauf = () => new Set((localStorage.getItem('seliku-geraet-verlauf') || '').split(','));
