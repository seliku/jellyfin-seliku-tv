// Seiten: Start, Bibliothek, Detail – gleiche Reihen und Regeln wie am Fernseher.
import { api, bild, sitzung } from './api.js';
import * as D from './daten.js';
import * as S from './seerr.js';
import * as E from './einstellungen.js';
import { h, img, kachel, seerrKachel, reihe, laden, metazeile, unterzeile, hauptzeile, zeit, laufzeit, titelMenue, sterne, datenstand, meldung, geraetWahl } from './ui.js';
import { oeffnen, abspielen, serieAbspielen, geh, geraetVerlauf } from './navigation.js';

const merk = {}; // Zwischenspeicher je Seite (wie vm.wahl am TV)

// ── Banner ──
async function bannerLaden(bib, seerrTyp) {
	const eigen = (await D.sicher(D.bannerEmpfehlungen(bib, 6, D.bannerGedaechtnis.gezeigt()))).map(i => ({ eigen: i }));
	D.bannerGedaechtnis.merken(eigen.map(e => e.eigen.Id));
	if (S.seerr.verbunden && E.an('banner_seerr') && (!bib || seerrTyp)) {
		try {
			const gemocht = D.mischen(await D.gemochteTitel()).map(i => [i.Type === 'Series' ? 'tv' : 'movie', +(i.ProviderIds?.Tmdb || 0)]).filter(([t, id]) => id && (!seerrTyp || t === seerrTyp)).slice(0, 4);
			const nicht = new Set(await D.sicher(D.magNicht())); const gez = D.bannerGedaechtnis.gezeigt();
			const empf = (await Promise.all(gemocht.map(([t, id]) => D.sicher(S.blatt(`/${t}/${id}/recommendations`, t, 1).then(r => r.liste))))).flat();
			const passend = D.eindeutig(D.mischen(empf), t => t.schluessel).filter(t => t.status < 4 && t.hintergrund && !nicht.has('seerr-' + t.schluessel) && S.deutsch(t.text));
			const frisch = passend.filter(t => !gez.has('seerr-' + t.schluessel));
			const wahl = (frisch.length ? frisch : passend).slice(0, Math.random() < 0.5 ? 1 : 2);
			D.bannerGedaechtnis.merken(wahl.map(t => 'seerr-' + t.schluessel));
			wahl.forEach((t, n) => eigen.splice(Math.min(1 + n * 3, eigen.length), 0, { fehlt: t }));
		} catch {}
	}
	return eigen;
}
function banner(liste) {
	if (!liste.length) return h('div', { style: 'height:24px' });
	let n = 0, trailerZeit, video;
	const folien = liste.map(e => {
		const i = e.eigen, t = e.fehlt;
		const logo = i && bild.logo(i);
		return h('div', { class: 'folie' }, img(i ? (bild.hintergrund(i) || bild.poster(i, 1200)) : t.hintergrund, 'hg'),
			h('div', { class: 'inhalt' },
				t ? h('span', { class: 'marke-fehlt' }, 'Nicht auf dem Server') : null,
				logo ? h('img', { class: 'logo', src: logo, alt: i.Name }) : h('h1', {}, i ? i.Name : t.titel),
				h('div', { class: 'meta' }, i ? metazeile(i) : [t.jahr, t.typ === 'movie' ? 'Film' : 'Serie'].filter(Boolean).join('   ·   ')),
				h('p', {}, i ? (i.Overview || '') : t.text),
				h('div', { class: 'knoepfe' },
					i ? h('button', { class: 'pille haupt', onclick: () => i.Type === 'Series' ? serieAbspielen(i.Id) : abspielen(i) }, '▶  Abspielen')
						: h('button', { class: 'pille haupt', onclick: () => geh(`#/seerr/${t.typ}/${t.tmdb}`) }, '+  Anfragen'),
					h('button', { class: 'pille', onclick: () => i ? oeffnen(i) : geh(`#/seerr/${t.typ}/${t.tmdb}`) }, 'ⓘ  Mehr Infos'))));
	});
	const punkte = h('div', { class: 'punkte' }, liste.map((_, k) => h('i', { onclick: () => zeige(k) })));
	const b = h('div', { class: 'banner' }, folien, punkte);
	function zeige(k) {
		video?.remove(); video = null; clearTimeout(trailerZeit);
		n = (k + liste.length) % liste.length;
		folien.forEach((f, m) => f.classList.toggle('an', m === n)); [...punkte.children].forEach((p, m) => p.classList.toggle('an', m === n));
	}
	zeige(0);
	const takt = setInterval(() => { if (!b.isConnected) return clearInterval(takt); if (!b.matches(':hover') && !video) zeige(n + 1); }, 9000);
	// Trailer stumm nach 2 s Verweilen (Browser lassen Ton erst nach einem Klick zu)
	b.addEventListener('mouseenter', () => {
		if (!E.an('trailer_banner')) return;
		trailerZeit = setTimeout(async () => {
			const i = liste[n].eigen; if (!i || video) return;
			const t = (await D.sicher(api(`/Items/${i.Id}/LocalTrailers`, { params: { userId: sitzung.benutzer.Id } }), []))[0]; if (!t || !b.matches(':hover')) return;
			video = h('video', { src: `${sitzung.server}/Videos/${t.Id}/stream?static=true&ApiKey=${sitzung.token}`, autoplay: true, muted: true, playsinline: true, style: 'opacity:0;transition:opacity .9s' });
			video.muted = true; video.onplaying = () => setTimeout(() => video && (video.style.opacity = 1), 1200); video.onended = () => zeige(n + 1);
			const ton = h('button', { class: 'ton', title: 'Ton', onclick: () => { video.muted = !video.muted; ton.textContent = video.muted ? '🔇' : '🔊'; } }, '🔇');
			folien[n].insertBefore(video, folien[n].children[1]); folien[n].append(ton);
			video.addEventListener('emptied', () => ton.remove());
		}, 2000);
	});
	b.addEventListener('mouseleave', () => { clearTimeout(trailerZeit); if (video) { video.remove(); video = null; folien[n].querySelector('.ton')?.remove(); } });
	return b;
}

// ── Startseite ──
async function startReihen() {
	const bibs = await D.bibliotheken(); const aufn = await D.aufnahmeOrdner();
	const s = (p) => D.sicher(p, null);
	const geraet = geraetVerlauf();
	const hoerenL = E.an('hoeren_start') !== false ? s(import('./hoeren.js').then(m => m.weiterhoeren())) : null;
	const [w, fav, tv, wahr, samml, ...neu] = await Promise.all([
		s(D.weiterschauen().then(l => E.an('weiterschauen_geraet') ? l.filter(i => geraet.has(i.SeriesId || i.Id)) : l)),
		E.an('favoriten_reihe') ? s(D.favoriten()) : null,
		bibs.some(b => b.CollectionType === 'livetv') ? s(D.sender().then(l => l.filter(x => x.CurrentProgram).slice(0, 20))) : null,
		E.an('wahre_geschichten') ? s(D.wahreGeschichten()) : null,
		s(D.startseitenSammlungen()),
		...bibs.filter(b => !['livetv', 'playlists'].includes(b.CollectionType)).map(b => s(D.neuIn(b)).then(l => ({ b, l }))),
	]);
	const r = [];
	if (w) r.push({ titel: 'Weiterschauen', l: w, art: 'weiter', g: -2, rang: 0 });
	if (fav) r.push({ titel: 'Deine Favoriten', l: fav, g: -1, rang: 0 });
	if (tv) r.push({ titel: 'Jetzt im TV', l: tv, art: 'tv', g: 2, rang: 0 });
	if (wahr) r.push({ titel: 'Wahre Geschichten', l: wahr, g: 1, rang: 3 });
	const hl = await hoerenL; if (hl?.length) r.push({ titel: 'Weiterhören', l: hl, art: 'hoeren', g: 3, rang: 0 });
	for (const x of samml || []) r.push({ titel: x.titel, l: x.items, art: 'samml', loesch: x.loeschdatum, g: x.items.filter(i => i.Type === 'Movie').length * 2 >= x.items.length ? 0 : 1, rang: 2 });
	for (const x of neu) if (x?.l) r.push({ titel: `Neu in ${x.b.Name}`, l: x.l, g: D.gruppeFuer(x.b, aufn), rang: 1, musik: x.b.CollectionType === 'music' });
	return r.filter(x => x.l?.length).sort((a, b) => a.g - b.g || a.rang - b.rang);
}
function reiheZeigen(x) {
	if (x.art === 'weiter') return reihe(x.titel, x.l, i => kachel(i, 'quer', abspielen));
	if (x.art === 'tv') return reihe(x.titel, x.l, i => kachel(i, 'quer', abspielen, { z1: i.CurrentProgram?.Name || i.Name, z2: `${i.ChannelNumber || ''}  ${i.Name}`, bildUrl: sendungsBild(i) }));
	if (x.art === 'hoeren') return reihe(x.titel, x.l, i => kachel(i, 'quadrat', (j) => import('./seiten2.js').then(m => m.hoerenStarten(j))));
	if (x.art === 'samml') return reihe(x.titel, x.l, i => kachel(i, 'quer', oeffnen, { z2: x.loesch[i.Id] ? `Wird am ${datum(x.loesch[i.Id])} gelöscht` : unterzeile(i) }));
	return reihe(x.titel, x.l, i => kachel(i, x.musik ? 'quadrat' : 'poster', i.Type === 'Episode' ? abspielen : oeffnen));
}
const datum = (d) => { const [j, m, t] = d.split('-'); return `${t}.${m}.`; };
export const sendungsBild = (s) => { const p = s.CurrentProgram; if (!p) return bild.poster(s); if (p.ImageTags?.Thumb) return bild.url(p.Id, 'Thumb'); if (p.ImageTags?.Primary) return bild.url(p.Id, 'Primary'); return bild.poster(s); };

export async function start(el) {
	const inhalt = h('div'); el.append(inhalt);
	const zeichnen = () => inhalt.replaceChildren(merk.banner ? banner(merk.banner) : h('div', { style: 'height:24px' }), ...(merk.start ? merk.start.map(reiheZeigen) : [laden()]));
	zeichnen();
	// Reihen sofort zeigen, Banner (rechenintensiv) nachreichen
	const b = merk.banner ? null : bannerLaden(null).then(x => { merk.banner = x; if (el.isConnected) zeichnen(); });
	merk.start = await startReihen(); if (el.isConnected) zeichnen();
	await b;
}

// ── Bibliothek: «Empfohlen» und «Bibliothek» ──
export async function bibliothek(el, id, reiterWahl) {
	const bib = (await D.bibliotheken()).find(b => b.Id === id) || await D.item(id);
	const r = reiterWahl || localStorage.getItem('seliku-reiter-' + id) || 'empfohlen';
	el.append(h('div', { class: 'kopf' }, h('h1', {}, bib.Name)),
		h('div', { class: 'reiter' }, h('button', { class: 'pille' + (r === 'empfohlen' ? ' aktiv' : ''), onclick: () => { localStorage.setItem('seliku-reiter-' + id, 'empfohlen'); geh(`#/bib/${id}/empfohlen`); } }, 'Empfohlen'),
			h('button', { class: 'pille' + (r === 'bibliothek' ? ' aktiv' : ''), onclick: () => { localStorage.setItem('seliku-reiter-' + id, 'bibliothek'); geh(`#/bib/${id}/bibliothek`); } }, 'Bibliothek')));
	const inhalt = h('div'); el.append(inhalt);
	if (r === 'bibliothek') return raster(inhalt, bib);
	const serien = bib.CollectionType === 'tvshows', typen = D.typenFuer(bib), seerrTyp = { movies: 'movie', tvshows: 'tv' }[bib.CollectionType];
	const k = 'bib-' + id;
	const zeichnen = () => inhalt.replaceChildren(merk[k + 'b'] ? banner(merk[k + 'b']) : '', ...(merk[k] || [laden()]));
	zeichnen();
	const s = (p) => D.sicher(p, null);
	const gw = D.mischen(await D.sicher(D.genres(bib, typen))).slice(0, 4);
	const [w, neu, weil, top, zul, wied, fehlt, ban, ...gr] = await Promise.all([s(D.weiterschauen(bib)), s(D.neuIn(bib)), s(D.weilDuGeschaut(bib, serien)), s(D.topUngesehen(bib, typen)),
		s(D.zuletztGesehen(bib, serien)), s(D.wiederentdecken(bib, typen)),
		seerrTyp && S.seerr.verbunden ? s(Promise.all([1, 2].map(p => S.blatt(seerrTyp === 'movie' ? '/discover/movies' : '/discover/tv', seerrTyp, p).then(x => x.liste))).then(l => D.eindeutig(l.flat(), t => t.schluessel).filter(t => t.zustand !== 'ANSEHEN').slice(0, 30))) : null,
		merk[k + 'b'] ? null : bannerLaden(bib, seerrTyp),
		...gw.map(g => s(D.genreReihe(bib, typen, g)).then(l => ({ g, l })))]);
	const geraet = geraetVerlauf();
	merk[k] = [
		reihe('Weiterschauen', E.an('weiterschauen_geraet') ? (w || []).filter(i => geraet.has(i.SeriesId || i.Id)) : w, i => kachel(i, 'quer', abspielen)),
		reihe('Neu hinzugefügt', neu, i => kachel(i, 'poster', i.Type === 'Episode' ? abspielen : oeffnen)),
		weil ? reihe(`Weil du «${weil.name}» geschaut hast`, weil.items, i => kachel(i, 'poster', oeffnen)) : null,
		reihe(serien ? 'Top-Serien, noch nicht gesehen' : 'Top-Filme, noch nicht gesehen', top, i => kachel(i, 'poster', oeffnen)),
		...gr.map(x => x && reihe(x.g, x.l, i => kachel(i, 'poster', oeffnen), { alle: `#/raster/${id}/${encodeURIComponent(x.g)}` })),
		reihe('Zuletzt gesehen', zul, i => kachel(i, 'poster', oeffnen)),
		reihe('Wiederentdecken', wied, i => kachel(i, 'poster', oeffnen)),
		fehlt ? reihe(seerrTyp === 'movie' ? 'Beliebte Filme – noch nicht da' : 'Beliebte Serien – noch nicht da', fehlt, t => seerrKachel(t, t => geh(`#/seerr/${t.typ}/${t.tmdb}`))) : null,
	].filter(Boolean);
	if (ban) merk[k + 'b'] = ban;
	if (el.isConnected) zeichnen();
}

export async function raster(el, bib, startGenre) {
	const typen = D.typenFuer(bib);
	let sort = D.SORTIERUNGEN.find(s => s.k === localStorage.getItem('seliku-sort')) || D.SORTIERUNGEN[0], ungesehen = false, nurFav = false, genre = startGenre || null, liste = [], gesamt = -1, laeuft = false;
	const knoepfe = h('div', { class: 'reiter' }), genreLeiste = h('div', { class: 'reiter' }), gitter = h('div', { class: 'raster' }), fuss = h('div');
	el.append(knoepfe, genreLeiste, gitter, fuss);
	const neu = () => { liste = []; gesamt = -1; gitter.replaceChildren(); zeichneKnoepfe(); mehr(); };
	function zeichneKnoepfe() {
		knoepfe.replaceChildren(...D.SORTIERUNGEN.map(s => h('button', { class: 'pille klein' + (s === sort ? ' aktiv' : ''), onclick: () => { sort = s; localStorage.setItem('seliku-sort', s.k); neu(); } }, s.titel)),
			h('button', { class: 'pille klein' + (ungesehen ? ' aktiv' : ''), onclick: () => { ungesehen = !ungesehen; neu(); } }, ungesehen ? '◐  Nur ungesehene' : '◐  Alle'),
			h('button', { class: 'pille klein' + (nurFav ? ' aktiv' : ''), onclick: () => { nurFav = !nurFav; neu(); } }, nurFav ? '♥  Favoriten' : '♡  Favoriten'),
			h('button', { class: 'pille klein', onclick: () => { const i = liste[Math.floor(Math.random() * liste.length)]; if (i) i.Type === 'Series' ? serieAbspielen(i.Id, true) : abspielen(i, true); } }, '⤨  Zufällig'));
	}
	D.genres(bib, typen).then(gs => genreLeiste.replaceChildren(...gs.map(g => h('button', { class: 'pille klein' + (g === genre ? ' aktiv' : ''), onclick: (e) => { genre = genre === g ? null : g; [...genreLeiste.children].forEach(b => b.classList.toggle('aktiv', b.textContent === genre)); neu(); } }, g)))).catch(() => {});
	async function mehr() {
		if (laeuft || (gesamt >= 0 && liste.length >= gesamt)) return; laeuft = true; fuss.replaceChildren(laden());
		try {
			const r = await D.inhalt(bib, typen, sort, ungesehen, liste.length, 60, genre, nurFav);
			gesamt = r.TotalRecordCount; const da = new Set(liste.map(i => i.Id));
			const n = r.Items.filter(i => !da.has(i.Id)); liste.push(...n); n.forEach(i => gitter.append(kachel(i, 'poster', oeffnen)));
		} catch { gesamt = liste.length; }
		fuss.replaceChildren(gesamt === 0 ? h('div', { class: 'hinweis' }, 'Nichts gefunden.') : ''); laeuft = false;
	}
	const beob = new IntersectionObserver(e => { if (e[0].isIntersecting) mehr(); }, { rootMargin: '800px' }); beob.observe(fuss);
	zeichneKnoepfe(); mehr();
}
export async function rasterSeite(el, id, genre) {
	const bib = await D.item(id);
	el.append(h('div', { class: 'kopf' }, h('button', { class: 'pille', onclick: () => history.back() }, '←'), h('h1', {}, genre || bib.Name)));
	raster(el, bib, genre);
}

// ── Detailseite (Film, Serie) ──
let titelMusik = null;
export function titelMusikStopp() { titelMusik?.pause(); titelMusik = null; }
export async function detail(el, id) {
	let i = await D.item(id);
	if (i.Type === 'Season' || i.Type === 'Episode') return geh(`#/detail/${i.SeriesId}`);
	const serie = i.Type === 'Series';
	const [naechste, staffelnL, trailer] = await Promise.all([serie ? D.sicher(D.naechsteFolge(id), null) : null, serie ? D.sicher(D.staffeln(id)) : [], D.sicher(api(`/Items/${id}/LocalTrailers`, { params: { userId: sitzung.benutzer.Id } }), [])]);
	const pos = i.UserData?.PlaybackPositionTicks || 0;
	const logo = bild.logo(i);
	const regie = (i.People || []).filter(p => p.Type === 'Director').slice(0, 2).map(p => p.Name);
	const text = h('div', { class: 'text', onclick: () => text.classList.toggle('offen') }, (i.Overview || '').startsWith('http') ? '' : i.Overview || '');
	const neuLaden = () => { el.replaceChildren(); detail(el, id); };
	const kn = [];
	if (serie) {
		const n = naechste, np = n?.UserData?.PlaybackPositionTicks || 0;
		kn.push(h('button', { class: 'pille haupt', onclick: () => n ? abspielen(n) : serieAbspielen(id, true) }, np > 0 ? `▶  Fortsetzen ab ${zeit(np / 1e4)}` : n ? `▶  Abspielen S${n.ParentIndexNumber} F${n.IndexNumber}` : '▶  Abspielen'));
		if (E.an('zufall_knopf')) kn.push(h('button', { class: 'pille', onclick: () => serieAbspielen(id, true) }, '⤨  Zufällig'));
	} else {
		kn.push(h('button', { class: 'pille haupt', onclick: () => abspielen(i) }, pos > 0 && !i.UserData?.Played ? `▶  Fortsetzen ab ${zeit(pos / 1e4)}` : '▶  Abspielen'));
		if (pos > 0) kn.push(h('button', { class: 'pille', onclick: () => abspielen(i, true) }, '↺  Von vorn'));
	}
	kn.push(h('button', { class: 'pille', title: 'Auf anderem Gerät abspielen', onclick: async () => geraetWahl(serie ? (naechste || (await D.alleFolgen(id))[0]) : i) }, '📺'));
	if (trailer[0]) kn.push(h('button', { class: 'pille', onclick: () => import('./spieler.js').then(m => m.spielen(trailer[0].Id, 0)) }, '▸  Trailer'));
	const g = i.UserData?.Played, f = i.UserData?.IsFavorite;
	kn.push(h('button', { class: 'pille' + (g ? ' aktiv' : ''), onclick: async () => { await D.gesehen(id, !g); neuLaden(); } }, g ? '✓  Gesehen' : '○  Ungesehen'));
	kn.push(h('button', { class: 'pille' + (f ? ' aktiv' : ''), title: 'Meine Liste', onclick: async () => { await D.favorit(id, !f); neuLaden(); } }, f ? '♥' : '♡'));
	if (E.an('eigene_wertung')) { const s = i.UserData?.Rating ? Math.round(i.UserData.Rating / 2) : 0; kn.push(h('button', { class: 'pille' + (s ? ' aktiv' : ''), onclick: () => sterne(i) }, s ? `★ ${s}` : '☆  Bewerten')); }
	kn.push(h('button', { class: 'pille', onclick: () => titelMenue(i) }, '⋮'));
	el.append(h('div', { class: 'detail' },
		h('div', { class: 'kulisse' }, img(bild.hintergrund(i) || bild.poster(i, 1200))),
		h('div', { class: 'kopfzeile' }, h('div', { class: 'poster' }, img(bild.poster(i, 400))),
			h('div', { class: 'titel' }, logo ? h('img', { class: 'logo', src: logo, alt: i.Name }) : h('h1', {}, i.Name),
				h('div', { class: 'meta' }, metazeile(i)),
				serie && naechste ? h('div', {}, `Als Nächstes: S${naechste.ParentIndexNumber} · F${naechste.IndexNumber} ${naechste.Name}`) : null,
				!serie && regie.length ? h('div', { class: 'meta' }, 'Regie: ' + regie.join(', ')) : null,
				i.Taglines?.[0] ? h('div', { class: 'tagline' }, i.Taglines[0]) : null,
				h('div', { class: 'knoepfe' }, kn), text))));
	if (serie) {
		const folgenEl = h('div', { class: 'folgen' });
		let st = staffelnL.find(s => s.Id === naechste?.SeasonId) || staffelnL[0];
		const tabs = h('div', { class: 'reiter', style: 'margin-top:24px' });
		const zeigeStaffel = async (s) => {
			st = s; [...tabs.children].forEach(b => b.classList.toggle('aktiv', b.dataset.id === s.Id));
			folgenEl.replaceChildren(laden());
			const fl = await D.folgen(id, s.Id);
			folgenEl.replaceChildren(...fl.map(e => h('div', { class: 'folge', onclick: () => abspielen(e), oncontextmenu: (ev) => { ev.preventDefault(); titelMenue(e); } },
				h('div', { class: 'bild' }, img(bild.quer(e, 400)), e.UserData?.Played ? h('span', { class: 'abz haken' }, '✓') : null,
					(e.UserData?.PlaybackPositionTicks > 0 && !e.UserData?.Played) ? h('div', { class: 'fortschritt' }, h('i', { style: `width:${e.UserData.PlaybackPositionTicks / e.RunTimeTicks * 100}%` })) : null),
				h('div', {}, h('b', {}, `${e.IndexNumber ?? ''}. ${e.Name}`), h('small', { style: 'display:block;-webkit-line-clamp:1' }, [laufzeit(e.RunTimeTicks), e.UserData?.Played ? 'gesehen' : null].filter(Boolean).join(' · ')), h('small', {}, e.Overview || '')))));
		};
		if (staffelnL.length > 1) tabs.append(...staffelnL.map(s => { const b = h('button', { class: 'pille klein', onclick: () => zeigeStaffel(s) }, (s.Name || '').replace('Season Unknown', 'Folgen').replace(/^Season (\d+)$/, 'Staffel $1')); b.dataset.id = s.Id; return b; }));
		el.append(tabs, folgenEl); if (st) zeigeStaffel(st);
		if (E.an('titelmusik')) {
			const { laeuft } = await import('./musik.js');
			const lied = !laeuft() && (await D.sicher(api(`/Items/${id}/ThemeSongs`, { params: { inheritFromParent: true, userId: sitzung.benutzer.Id } }), {})).Items?.[0];
			if (lied && el.isConnected) { titelMusikStopp(); titelMusik = new Audio(`${sitzung.server}/Audio/${lied.Id}/stream?static=true&ApiKey=${sitzung.token}`); titelMusik.volume = 0.35; titelMusik.play().catch(() => {}); }
		}
	}
	const besetzung = (i.People || []).filter(p => p.Type === 'Actor').slice(0, 30);
	el.append(reihe('Besetzung', besetzung, p => h('div', { class: 'person', onclick: () => personOeffnen(p) }, h('div', { class: 'bild' }, p.PrimaryImageTag ? img(bild.url(p.Id, 'Primary', { breite: 200 })) : null), h('div', {}, p.Name), h('div', { class: 'rolle' }, p.Role || ''))) || '');
	const aeh = await D.sicher(D.aehnlich(id));
	let fehlt = [];
	const tmdb = +(i.ProviderIds?.Tmdb || 0);
	if (tmdb && S.seerr.verbunden) { const t = serie ? 'tv' : 'movie'; fehlt = (await Promise.all([1, 2].map(p => D.sicher(S.blatt(`/${t}/${tmdb}/recommendations`, t, p).then(r => r.liste))))).flat().filter(x => x.zustand !== 'ANSEHEN'); }
	const gemischt = []; for (let k = 0; k < Math.max(aeh.length, fehlt.length); k++) { if (aeh[k]) gemischt.push(aeh[k]); if (fehlt[k]) gemischt.push(fehlt[k]); }
	if (el.isConnected) el.append(reihe(serie ? 'Ähnliche Serien' : 'Ähnliche Titel', gemischt, x => x.schluessel ? seerrKachel(x, t => geh(`#/seerr/${t.typ}/${t.tmdb}`), true) : kachel(x, 'poster', oeffnen)) || '');
}
async function personOeffnen(p) {
	const tmdb = +((await D.sicher(D.item(p.Id), {})).ProviderIds?.Tmdb || 0);
	if (tmdb && S.seerr.verbunden) geh(`#/seerrliste/person/${tmdb}/${encodeURIComponent(p.Name)}`); else meldung('Keine Filmografie verfügbar');
}
