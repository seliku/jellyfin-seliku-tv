// Seerr über den Vermittler des Servers (/seerr, gleiche Domain): keine eigene Anmeldung (S 04.11.).
// Zustände wie am TV: wenig Status, keine Restzeit.
import { sitzung } from './api.js';
export const TMDB = 'https://image.tmdb.org/t/p/';
export const seerr = { verbunden: false, ich: null };

export class SeerrFehler extends Error { constructor(code, text) { super(text); this.code = code; } }

async function anfrage(methode, pfad, body) {
	const r = await fetch(`${sitzung.server}/seerr${pfad}`, { method: methode, headers: {
		'Accept': 'application/json', 'Authorization': `MediaBrowser Token="${sitzung.token}"`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
		body: body ? JSON.stringify(body) : undefined });
	const t = await r.text();
	if (r.status >= 400) throw new SeerrFehler(r.status, t.slice(0, 200));
	return { status: r.status, daten: t ? JSON.parse(t) : null };
}
const json = async (p) => (await anfrage('GET', '/api/v1' + p)).daten;

export async function pruefen() {
	try { const me = await json('/auth/me'); seerr.ich = me?.id || null; seerr.verbunden = true; } catch { seerr.verbunden = false; }
	return seerr.verbunden;
}

export function zustand(t) {
	if (t.status === 4 || t.status === 5) return 'ANSEHEN';
	if (t.status === 3 && t.laedt) return 'GLEICH';
	if (t.status === 3 && t.anfrageSeit && Date.now() - t.anfrageSeit >= 6 * 3600e3) return 'NOCH_NICHT';
	if (t.status === 3) return 'WIRD_GESUCHT';
	if (t.status === 2) return 'ANGEFRAGT';
	return 'ANFRAGEN';
}
export const ABZEICHEN = { ANGEFRAGT: 'Angefragt', WIRD_GESUCHT: 'Angefragt', NOCH_NICHT: 'Angefragt', GLEICH: 'Gleich verfügbar', ANSEHEN: 'Jetzt ansehen' };

export function titelAus(o, typVorgabe) {
	const typ = o.mediaType || typVorgabe;
	if (typ !== 'movie' && typ !== 'tv') return null;
	const info = o.mediaInfo; const anf = info?.requests || [];
	const meine = anf.find(r => seerr.ich && r.requestedBy?.id === seerr.ich);
	const letzte = [...anf].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0];
	const a = meine || letzte;
	const t = {
		tmdb: o.id, typ, titel: (typ === 'movie' ? o.title : o.name) || o.originalTitle || o.originalName || '',
		jahr: ((typ === 'movie' ? o.releaseDate : o.firstAirDate) || '').slice(0, 4),
		poster: o.posterPath?.startsWith('/') ? TMDB + 'w342' + o.posterPath : null,
		hintergrund: o.backdropPath?.startsWith('/') ? TMDB + 'w1280' + o.backdropPath : null,
		text: o.overview || '', status: info?.status || 1,
		laedt: (info?.downloadStatus?.length || 0) > 0 || (info?.downloadStatus4k?.length || 0) > 0,
		anfrageId: a?.id, anfrageSeit: a?.createdAt ? Date.parse(a.createdAt) : null, anfrageVonMir: !!meine, genreIds: o.genreIds || [],
	};
	t.schluessel = `${typ}-${t.tmdb}`; t.zustand = zustand(t);
	return t;
}

export async function blatt(pfad, typ, seite = 1) {
	const o = await json(pfad + (pfad.includes('?') ? '&' : '?') + `page=${seite}&language=de`);
	return { liste: (o.results || []).map(x => titelAus(x, typ)).filter(Boolean), seiten: o.totalPages || seite };
}
export const suche = async (t) => (await blatt(`/search?query=${encodeURIComponent(t)}`, null, 1)).liste;
export async function detail(typ, tmdb) {
	const o = await json(`/${typ}/${tmdb}?language=de`);
	const film = typ === 'movie';
	const namen = (a, n) => (a || []).map(x => x.name).filter(Boolean).slice(0, n).join(', ') || null;
	const mehr = {
		genres: (o.genres || []).map(g => g.name), laufzeit: film ? o.runtime : o.episodeRunTime?.[0], staffeln: film ? null : o.numberOfSeasons,
		folgen: film ? null : o.numberOfEpisodes, bewertung: o.voteAverage || null,
		macher: film ? [...new Set((o.credits?.crew || []).filter(c => c.job === 'Director').map(c => c.name))].slice(0, 2).join(', ') || null : namen(o.createdBy, 2),
		firmen: film ? namen(o.productionCompanies, 2) : namen(o.networks, 2),
		besetzung: (o.credits?.cast || []).slice(0, 20).map(c => ({ id: c.id, name: c.name, rolle: c.character, bild: c.profilePath ? TMDB + 'w185' + c.profilePath : null })),
		youtube: (o.relatedVideos || []).filter(v => v.site === 'YouTube' && v.key).sort((a, b) => (a.type === 'Trailer' ? 0 : 1) - (b.type === 'Trailer' ? 0 : 1))[0]?.key,
		ohneTvdb: !film && !(o.externalIds?.tvdbId > 0),
	};
	return { titel: titelAus(o, typ), mehr };
}
export const tomaten = async (typ, tmdb) => { try { const s = (await json(`/${typ}/${tmdb}/ratings`)).criticsScore; return s >= 0 && s <= 100 ? s : null; } catch { return null; } };
export async function anfragen(t) {
	const r = await anfrage('POST', '/api/v1/request', { mediaType: t.typ, mediaId: t.tmdb, ...(t.typ === 'tv' ? { seasons: 'all' } : {}) });
	if (r.status === 202) throw new SeerrFehler(202, 'nichts angelegt');
}
export const zuruecknehmen = (t) => t.anfrageId && anfrage('DELETE', `/api/v1/request/${t.anfrageId}`);
export async function meineAnfragen() {
	if (!seerr.ich) return [];
	const r = await json(`/request?take=20&skip=0&sort=added&requestedBy=${seerr.ich}`);
	return (await Promise.all((r.results || []).map(async x => { try { return titelAus(await json(`/${x.type}/${x.media?.tmdbId}?language=de`), x.type); } catch { return null; } }))).filter(Boolean);
}
export async function genreKacheln(typ) {
	const a = await json(`/discover/genreslider/${typ}?language=de`);
	return (a || []).map(g => ({ art: typ === 'movie' ? 'film-genre' : 'serie-genre', id: g.id, name: g.name, bild: g.backdrops?.[0] ? TMDB + 'w780' + g.backdrops[0] : null }));
}
const logos = {};
export async function logo(r) {
	if (r.schluessel in logos) return logos[r.schluessel];
	const sender = r.art === 'sender';
	try {
		const o = await json(sender ? `/discover/tv/network/${r.id}?page=1&language=de` : `/discover/movies/studio/${r.id}?page=1&language=de`);
		const f = o[sender ? 'network' : 'studio'];
		return logos[r.schluessel] = f?.logoPath ? TMDB + 'w500_filter(duotone,ffffff,bababa)' + f.logoPath : null;
	} catch { return logos[r.schluessel] = null; }
}
export async function filmografie(person) {
	const a = (await json(`/person/${person}/combined_credits?language=de`)).cast || [];
	const auftritte = [10763, 10764, 10767];
	const l = a.filter(o => !(o.genreIds || []).some(g => auftritte.includes(g))).sort((x, y) => (y.voteCount || 0) - (x.voteCount || 0)).map(o => titelAus(o)).filter(Boolean);
	l.sort((x, y) => (x.poster ? 0 : 1) - (y.poster ? 0 : 1));
	const s = new Set(), s2 = new Set();
	return l.filter(t => !s.has(t.schluessel) && s.add(t.schluessel)).filter(t => { const k = `${t.typ}|${t.titel}|${t.jahr}`; return !s2.has(k) && s2.add(k); });
}
const r = (art, id, name) => ({ art, id, name, schluessel: `${art}-${id}` });
export const STUDIOS = [r('studio', 2, 'Disney'), r('studio', 127928, '20th Century Studios'), r('studio', 34, 'Sony Pictures'), r('studio', 174, 'Warner Bros. Pictures'),
	r('studio', 33, 'Universal'), r('studio', 4, 'Paramount'), r('studio', 3, 'Pixar'), r('studio', 521, 'DreamWorks'), r('studio', 420, 'Marvel Studios'), r('studio', 9993, 'DC'),
	r('studio', 21, 'MGM'), r('studio', 1632, 'Lionsgate'), r('studio', 5, 'Columbia Pictures'), r('studio', 12, 'New Line Cinema'), r('studio', 1, 'Lucasfilm'),
	r('studio', 923, 'Legendary'), r('studio', 41077, 'A24'), r('studio', 3172, 'Blumhouse'), r('studio', 10342, 'Studio Ghibli')];
export const SENDER = [r('sender', 213, 'Netflix'), r('sender', 2739, 'Disney+'), r('sender', 1024, 'Prime Video'), r('sender', 2552, 'Apple TV+'), r('sender', 49, 'HBO'),
	r('sender', 3186, 'HBO Max'), r('sender', 4330, 'Paramount+'), r('sender', 453, 'Hulu'), r('sender', 31, 'ZDF'), r('sender', 308, 'Das Erste')];
export function quelle(art, id) {
	return ({ 'film-genre': [`/discover/movies/genre/${id}`, 'movie'], 'serie-genre': [`/discover/tv/genre/${id}`, 'tv'], studio: [`/discover/movies/studio/${id}`, 'movie'], sender: [`/discover/tv/network/${id}`, 'tv'] })[art];
}
export function deutsch(text) {
	const w = (text || '').toLowerCase().split(/[^a-zäöüß]+/); if (w.length < 5) return false;
	const de = new Set('der die das und ist ein eine nicht mit sich auf zu den von'.split(' ')), en = new Set('the and is of to a with his her their in who'.split(' '));
	return w.filter(x => de.has(x)).length > w.filter(x => en.has(x)).length;
}
