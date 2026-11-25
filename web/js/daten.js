// Datenlogik wie in der TV-App (SelikuDaten.kt): gleiche Abfragen, gleiche Regeln, gleiche Speicher im Konto,
// damit Fernseher, Handy und Web dasselbe zeigen.
import { api, sitzung, gid } from './api.js';

const FELDER = 'Overview,PrimaryImageAspectRatio,Genres,ChildCount,DateCreated,MediaSourceCount,Taglines,ProviderIds';
const items = (params) => api('/Items', { params: { fields: FELDER, enableUserData: true, ...params } }).then(r => r.Items || []);
const uid = () => sitzung.benutzer?.Id;
const mischen = (a) => { const b = [...a]; for (let i = b.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [b[i], b[j]] = [b[j], b[i]]; } return b; };
const eindeutig = (a, k) => { const s = new Set(); return a.filter(x => { const v = k(x); if (s.has(v)) return false; s.add(v); return true; }); };
const sicher = async (p, ersatz = []) => { try { return await p; } catch { return ersatz; } };
export { mischen, eindeutig, sicher };

// ── Konto-Speicher (DisplayPreferences, Client «seliku») ──
export async function praef(id) { return api(`/DisplayPreferences/${id}`, { params: { client: 'seliku', userId: uid() } }); }
export async function praefSetzen(id, schluessel, wert) {
	const alt = await praef(id);
	alt.CustomPrefs = { ...(alt.CustomPrefs || {}), [schluessel]: wert };
	await api(`/DisplayPreferences/${id}`, { methode: 'POST', params: { client: 'seliku', userId: uid() }, body: alt });
}
const liste = async (id, k) => ((await sicher(praef(id), {})).CustomPrefs?.[k] || '').split(',').filter(Boolean);

// ── Bibliotheken ──
let bibZwischen = null;
export async function bibliotheken() {
	if (bibZwischen && Date.now() - bibZwischen.zeit < 60000) return bibZwischen.liste;
	const [alle, ich] = await Promise.all([api(`/UserViews`, { params: { userId: uid() } }), api('/Users/Me')]);
	const aus = new Set(ich.Configuration?.MyMediaExcludes || []);
	const l = (alle.Items || []).filter(b => !aus.has(b.Id));
	bibZwischen = { zeit: Date.now(), liste: l };
	return l;
}
export const typenFuer = (b) => ({ movies: 'Movie', tvshows: 'Series', boxsets: 'BoxSet' }[b.CollectionType] || 'Movie,Series,Video');
export const gruppeFuer = (b, aufnahmen = new Set()) => aufnahmen.has(b.Id) ? 2 : ({ movies: 0, tvshows: 1, livetv: 2, music: 3, books: 3 }[b.CollectionType] ?? 4);
export const item = (id) => api(`/Items/${id}`, { params: { userId: uid() } });
export const aufnahmeOrdner = () => sicher(api('/LiveTv/Recordings/Folders').then(r => new Set((r.Items || []).map(i => i.Id))), new Set());

// ── Weiterschauen wie bei Plex (S 24.11.): je Serie ein Eintrag, zuletzt Geschautes zuerst ──
async function ausgeblendet() { return new Set((await liste('seliku-weiterschauen', 'ausgeblendet')).map(gid)); }
export async function ausWeiterschauen(i) {
	const s = await ausgeblendet(); s.add(gid(i.SeriesId || i.Id));
	await praefSetzen('seliku-weiterschauen', 'ausgeblendet', [...s].join(','));
	if (i.UserData?.PlaybackPositionTicks > 0) await api(`/UserItems/${i.Id}/UserData`, { methode: 'POST', params: { userId: uid() }, body: { PlaybackPositionTicks: 0 } });
}
export async function wiederAufnehmen(i) {
	const s = await ausgeblendet(); const k = gid(i.SeriesId || i.Id);
	if (s.delete(k)) await praefSetzen('seliku-weiterschauen', 'ausgeblendet', [...s].join(','));
}
export async function alleFolgen(serie) {
	const alle = (await api(`/Shows/${serie}/Episodes`, { params: { userId: uid(), isMissing: false, fields: FELDER, enableUserData: true } })).Items || [];
	const ohne = alle.filter(f => f.ParentIndexNumber !== 0);
	return ohne.length ? ohne : alle;
}
/** NextUp nimmt die höchste gesehene Nummer; hier zählt die zuletzt geschaute Folge (S 08.11., «Hubert und Staller»). */
export async function folgeNachZuletztGeschauter(serie) {
	const f = await alleFolgen(serie);
	const gs = f.filter(x => x.UserData?.LastPlayedDate);
	if (!gs.length) return f.find(x => !x.UserData?.Played) || null;
	const z = gs.reduce((a, b) => (a.UserData.LastPlayedDate > b.UserData.LastPlayedDate ? a : b));
	if (!z.UserData.Played) return z;
	const n = f.indexOf(z);
	return [...f.slice(n + 1), ...f.slice(0, n)].find(x => !x.UserData?.Played) || null;
}
export async function naechsteFolge(serie) {
	const n = await api('/Shows/NextUp', { params: { userId: uid(), seriesId: serie, limit: 1, fields: FELDER, enableUserData: true } });
	return n.Items?.[0] || folgeNachZuletztGeschauter(serie);
}
export async function weiterschauen(bib) {
	const p = bib ? { parentId: bib.Id } : {};
	const [folgen, angefangen, naechsteL, weg] = await Promise.all([
		items({ ...p, recursive: true, includeItemTypes: 'Episode', sortBy: 'DatePlayed', sortOrder: 'Descending', limit: 300 }),
		api('/UserItems/Resume', { params: { ...p, userId: uid(), limit: 60, mediaTypes: 'Video', fields: FELDER, enableUserData: true } }).then(r => r.Items || []),
		api('/Shows/NextUp', { params: { ...p, userId: uid(), limit: 100, fields: FELDER, enableUserData: true, enableResumable: false } }).then(r => r.Items || []),
		ausgeblendet(),
	]);
	const naechste = new Map(naechsteL.filter(n => n.SeriesId).map(n => [n.SeriesId, n]));
	const nr = (i) => (i.ParentIndexNumber || 0) * 100000 + (i.IndexNumber || 0);
	const grenze = new Date(Date.now() - 30 * 864e5).toISOString();
	const letzte = eindeutig(folgen.filter(f => f.SeriesId && f.UserData?.LastPlayedDate && (f.UserData.Played || f.UserData.PlaybackPositionTicks > 0)), f => f.SeriesId).slice(0, 40);
	const serien = (await Promise.all(letzte.map(async f => {
		const wann = f.UserData.LastPlayedDate;
		let e;
		if (!f.UserData.Played) e = f;
		else {
			const n = naechste.get(f.SeriesId);
			if (n && f.ParentIndexNumber !== 0 && nr(n) > nr(f)) e = n;
			else if (n || wann > grenze) e = await sicher(folgeNachZuletztGeschauter(f.SeriesId), null);
		}
		return e ? [e, wann] : null;
	}))).filter(Boolean);
	const bekannt = new Set(letzte.map(f => f.SeriesId));
	const filme = angefangen.filter(i => !i.SeriesId);
	const datiert = [...serien, ...filme.filter(m => m.UserData?.LastPlayedDate).map(m => [m, m.UserData.LastPlayedDate])]
		.sort((a, b) => (a[1] < b[1] ? 1 : -1)).map(x => x[0]);
	const rest = [...[...naechste.values()].filter(n => !bekannt.has(n.SeriesId)), ...angefangen.filter(i => i.SeriesId && !bekannt.has(i.SeriesId)), ...filme.filter(m => !m.UserData?.LastPlayedDate)];
	return eindeutig([...datiert, ...rest], i => i.SeriesId || i.Id).filter(i => !weg.has(gid(i.SeriesId || i.Id))).slice(0, 40);
}

// ── Reihen ──
export const favoriten = () => items({ recursive: true, includeItemTypes: 'Movie,Series', filters: 'IsFavorite', sortBy: 'SortName', limit: 60 });
export const neuIn = (b) => api('/Items/Latest', { params: { userId: uid(), parentId: b.Id, limit: 24, fields: FELDER, enableUserData: true, groupItems: true } });
export const wahreGeschichten = () => items({ recursive: true, includeItemTypes: 'Movie,Series', sortBy: 'Random', limit: 40,
	tags: 'based on true story|inspired by true events|based on memoir or autobiography|biography|biographical drama|biographical comedy|true crime' });
export async function startseitenSammlungen() {
	const s = await items({ includeItemTypes: 'BoxSet', recursive: true, tags: 'seliku-startseite', fields: 'Overview' });
	return (await Promise.all(s.map(async x => {
		const daten = {};
		(x.Overview || '').split('\n').forEach(z => { const t = z.trim().split(' '); if (t.length >= 2) daten[t[0].replace(/-/g, '')] = t[1]; });
		return { titel: x.Name, items: await items({ parentId: x.Id }), loeschdatum: daten };
	}))).filter(r => r.items.length);
}
const bibListe = (b, typen, sortBy, absteigend = true, filters, genre, anzahl = 24) =>
	items({ parentId: b.Id, includeItemTypes: typen, recursive: true, sortBy, sortOrder: absteigend ? 'Descending' : 'Ascending', filters, genres: genre, limit: anzahl });
export const topUngesehen = (b, t) => bibListe(b, t, 'CommunityRating', true, 'IsUnplayed');
export const wiederentdecken = (b, t) => bibListe(b, t, 'Random');
export const genreReihe = (b, t, g) => bibListe(b, t, 'Random', true, undefined, g);
export async function zuletztGesehen(b, serien) {
	if (serien) return eindeutig(await bibListe(b, 'Episode', 'DatePlayed', true, 'IsPlayed', undefined, 60), i => i.SeriesId).slice(0, 24);
	return bibListe(b, 'Movie', 'DatePlayed', true, 'IsPlayed');
}
export async function weilDuGeschaut(b, serien) {
	const z = (await zuletztGesehen(b, serien))[0]; if (!z) return null;
	const basis = serien ? z.SeriesId : z.Id; if (!basis) return null;
	const t = (await api(`/Items/${basis}/Similar`, { params: { userId: uid(), limit: 20, fields: FELDER } })).Items || [];
	return t.length ? { name: serien ? z.SeriesName : z.Name, items: t } : null;
}
export const genres = (b, typen) => api('/Genres', { params: { parentId: b.Id, includeItemTypes: typen, sortBy: 'SortName', userId: uid() } }).then(r => (r.Items || []).map(g => g.Name));
export const aehnlich = (id) => api(`/Items/${id}/Similar`, { params: { userId: uid(), limit: 16, fields: FELDER } }).then(r => r.Items || []);
export const staffeln = (s) => api(`/Shows/${s}/Seasons`, { params: { userId: uid(), fields: FELDER, enableUserData: true } }).then(r => r.Items || []);
export const folgen = (s, st) => api(`/Shows/${s}/Episodes`, { params: { userId: uid(), seasonId: st, fields: FELDER + ',MediaStreams', enableUserData: true } }).then(r => r.Items || []);
export const inhalt = (b, typen, sort, ungesehen, start, anzahl, genre, nurFav) => api('/Items', { params: {
	parentId: b.Id, includeItemTypes: typen, recursive: true, sortBy: sort.nach, sortOrder: sort.reihenfolge,
	filters: [ungesehen && 'IsUnplayed', nurFav && 'IsFavorite'].filter(Boolean).join(',') || undefined, genres: genre,
	fields: FELDER, enableUserData: true, startIndex: start, limit: anzahl, userId: uid() } });
export const SORTIERUNGEN = [
	{ k: 'NEU', titel: 'Neu hinzugefügt', nach: 'DateCreated,SortName', reihenfolge: 'Descending' },
	{ k: 'NAME', titel: 'A–Z', nach: 'SortName', reihenfolge: 'Ascending' },
	{ k: 'JAHR', titel: 'Erscheinungsjahr', nach: 'ProductionYear,SortName', reihenfolge: 'Descending' },
	{ k: 'BEWERTUNG', titel: 'Bewertung', nach: 'CommunityRating,SortName', reihenfolge: 'Descending' },
	{ k: 'ZULETZT', titel: 'Zuletzt gesehen', nach: 'DatePlayed,SortName', reihenfolge: 'Descending' },
];
export const suche = (t) => items({ searchTerm: t, recursive: true, limit: 80, includeItemTypes: 'Movie,Series,Episode,MusicArtist,MusicAlbum,Audio,TvChannel' });
export const ordnerInhalt = (id) => items({ parentId: id, sortBy: 'IsFolder,SortName', sortOrder: 'Descending,Ascending' });

// ── Aktionen ──
export const gesehen = (id, ja) => api(`/UserPlayedItems/${id}`, { methode: ja ? 'POST' : 'DELETE', params: { userId: uid() } });
export const favorit = (id, ja) => api(`/UserFavoriteItems/${id}`, { methode: ja ? 'POST' : 'DELETE', params: { userId: uid() } });
export const wertung = (id, sterne) => api(`/UserItems/${id}/UserData`, { methode: 'POST', params: { userId: uid() }, body: { Rating: sterne * 2 } });
export const loeschen = (id) => api(`/Items/${id}`, { methode: 'DELETE' });

// ── Live-TV ──
export const senderBlock = (nr) => { const n = parseFloat(nr); return isNaN(n) ? Infinity : Math.floor(n / 100); };
export async function sender() {
	const r = await api('/LiveTv/Channels', { params: { userId: uid(), addCurrentProgram: true, enableUserData: true, sortBy: 'SortName', fields: 'PrimaryImageAspectRatio' } });
	const n = (s) => parseFloat(s.ChannelNumber ?? s.Number); 
	return (r.Items || []).sort((a, b) => (isNaN(n(a)) ? 1e9 : n(a)) - (isNaN(n(b)) ? 1e9 : n(b)));
}
export async function naechsteSendungen(sender) {
	if (!sender.length) return {};
	const jetzt = new Date();
	const r = await api('/LiveTv/Programs', { params: { userId: uid(), channelIds: sender.map(s => s.Id).join(','), minStartDate: jetzt.toISOString(),
		maxStartDate: new Date(jetzt.getTime() + 8 * 3600e3).toISOString(), sortBy: 'StartDate', enableImages: false } });
	const m = {}; for (const p of r.Items || []) if (!m[p.ChannelId]) m[p.ChannelId] = p; return m;
}
export async function senderGruppenNamen() {
	const g = (await sicher(praef('seliku-livetv'), {})).CustomPrefs?.gruppen || '';
	const m = {}; g.split('\n').forEach(z => { const [nr, ...name] = z.split('='); if (name.length && !isNaN(parseInt(nr))) m[senderBlock(nr)] = name.join('=').trim(); }); return m;
}

// ── Geschmack, Banner (wie bannerEmpfehlungen am TV) ──
export const magNicht = () => liste('seliku-geschmack', 'mag_nicht').then(l => l.map(x => x.startsWith('seerr-') ? x : gid(x)));
async function genreAnteile() {
	const gesehen = await items({ includeItemTypes: 'Movie,Episode', recursive: true, filters: 'IsPlayed', sortBy: 'DatePlayed', sortOrder: 'Descending', fields: 'Genres', limit: 300 });
	const serien = [...new Set(gesehen.map(i => i.SeriesId).filter(Boolean))].slice(0, 40);
	const sg = (await Promise.all(serien.map(id => sicher(item(id), {})))).flatMap(s => s.Genres || []);
	const z = {}; [...gesehen.flatMap(i => i.Genres || []), ...sg].forEach(g => z[g] = (z[g] || 0) + 1);
	const summe = Math.max(1, Object.values(z).reduce((a, b) => a + b, 0));
	for (const k in z) z[k] /= summe; return z;
}
export async function lieblingsGenres() { const a = await genreAnteile(); return Object.entries(a).sort((x, y) => y[1] - x[1]).map(x => x[0]); }
export async function bannerEmpfehlungen(bib, anzahl = 6, ausser = new Set()) {
	const typen = bib ? typenFuer(bib) : 'Movie,Series';
	const anteile = await sicher(genreAnteile(), {});
	const abfrage = (s, n) => items({ parentId: bib?.Id, includeItemTypes: typen, recursive: true, filters: 'IsUnplayed', imageTypes: 'Backdrop', sortBy: s, sortOrder: 'Descending', fields: FELDER + ',Genres', limit: n });
	const basis = (await Promise.all([abfrage('CommunityRating', 300), abfrage('DateCreated', 80), abfrage('Random', 80)])).flat();
	const bewertet = await sicher(items({ includeItemTypes: 'Movie,Series', recursive: true, sortBy: 'DatePlayed,Random', sortOrder: 'Descending', limit: 400 }));
	const favs = await sicher(items({ includeItemTypes: 'Movie,Series', recursive: true, filters: 'IsFavorite', limit: 100 }));
	const gemocht = eindeutig([...favs, ...bewertet.filter(i => (i.UserData?.Rating || 0) >= 8)], i => i.Id);
	const nichtIds = new Set([...(await sicher(magNicht())), ...bewertet.filter(i => { const r = i.UserData?.Rating || 0; return r >= 1 && r <= 4; }).map(i => gid(i.Id))]);
	const aehnl = async (ids, je) => (await Promise.all(ids.map(id => sicher(api(`/Items/${id}/Similar`, { params: { userId: uid(), limit: je, fields: FELDER + ',Genres' } }).then(r => r.Items || []))))).flat();
	const aG = await aehnl(mischen(gemocht).slice(0, 6).map(i => i.Id), 15); const aGIds = new Set(aG.map(i => i.Id));
	const aNIds = new Set((await aehnl(mischen([...nichtIds].filter(x => !x.startsWith('seerr-'))).slice(0, 4), 10)).map(i => i.Id).filter(id => !aGIds.has(id)));
	const dazu = bib ? [] : aG.filter(i => typen.includes(i.Type) && !i.UserData?.Played && i.BackdropImageTags?.length);
	const kand = eindeutig([...basis, ...dazu], i => i.Id).filter(i => !nichtIds.has(gid(i.Id)) && !aNIds.has(i.Id));
	const vor30 = new Date(Date.now() - 30 * 864e5).toISOString();
	const spezial = ['animation', 'anime', 'horror', 'kinder', 'familie', 'family', 'dokumentar', 'documentary', 'dokumentation', 'reality', 'musik', 'music'];
	const a = (g) => anteile[g] || 0; const leer = !Object.keys(anteile).length;
	const gw = kand.map(k => {
		const g = k.Genres || [];
		const bonus = (aGIds.has(k.Id) ? 0.25 : 0) + ((k.DateCreated || '') > vor30 ? 0.05 : 0);
		if (leer) return [k, bonus + (k.CommunityRating || 6) / 100];
		if (!g.length) return bonus > 0.2 ? [k, bonus] : null;
		if (bonus < 0.2 && g.some(x => spezial.some(s => x.toLowerCase().includes(s)) && a(x) < 0.03)) return null;
		const treffer = g.reduce((s, x) => s + a(x), 0), fremd = g.filter(x => a(x) < 0.02).length;
		return (treffer <= 0 && bonus < 0.2) ? null : [k, treffer - 0.08 * fremd + (k.CommunityRating || 6) / 100 + bonus];
	}).filter(Boolean).sort((x, y) => y[1] - x[1]).map(x => x[0]);
	const frisch = gw.filter(i => !ausser.has(i.Id)); const w = frisch.length >= anzahl ? frisch : gw;
	return mischen(w.slice(0, 40)).slice(0, anzahl);
}
export async function gemochteTitel() {
	const f = await items({ includeItemTypes: 'Movie,Series', recursive: true, filters: 'IsFavorite', limit: 100 });
	const b = (await items({ includeItemTypes: 'Movie,Series', recursive: true, sortBy: 'DatePlayed', sortOrder: 'Descending', limit: 400 })).filter(i => (i.UserData?.Rating || 0) >= 8);
	return eindeutig([...f, ...b], i => i.Id);
}
export async function verlauf() {
	const g = await items({ recursive: true, includeItemTypes: 'Movie,Episode,Video', filters: 'IsPlayed', sortBy: 'DatePlayed', sortOrder: 'Descending', limit: 250 });
	const a = (await api('/UserItems/Resume', { params: { userId: uid(), limit: 60, mediaTypes: 'Video', fields: FELDER, enableUserData: true } })).Items || [];
	return eindeutig([...g, ...a].filter(i => i.UserData?.LastPlayedDate), i => i.Id).sort((x, y) => (x.UserData.LastPlayedDate < y.UserData.LastPlayedDate ? 1 : -1));
}
export async function nichtGemocht() {
	const b = (await items({ includeItemTypes: 'Movie,Series', recursive: true, sortBy: 'DatePlayed', sortOrder: 'Descending', limit: 400 })).filter(i => { const r = i.UserData?.Rating || 0; return r >= 1 && r <= 4; });
	const ids = (await sicher(magNicht())).filter(x => !x.startsWith('seerr-'));
	const alt = ids.length ? await items({ ids: ids.slice(0, 80).join(',') }) : [];
	return eindeutig([...b, ...alt], i => i.Id);
}

// ── Banner-Gedächtnis (nur dieser Browser, 3 Tage) ──
export const bannerGedaechtnis = {
	gezeigt() { const g = Date.now() - 3 * 864e5; return new Set((localStorage.getItem('seliku-banner') || '').split(',').map(e => e.split(':')).filter(([, t]) => +t > g).map(([id]) => id)); },
	merken(ids) {
		const g = Date.now() - 3 * 864e5, j = Date.now();
		const alt = (localStorage.getItem('seliku-banner') || '').split(',').filter(e => +(e.split(':')[1]) > g && !ids.some(id => e.startsWith(id + ':')));
		localStorage.setItem('seliku-banner', [...alt, ...ids.map(id => `${id}:${j}`)].slice(-400).join(','));
	},
};
