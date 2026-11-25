// Jellyfin-Anbindung der Seliku-Weboberfläche. Gleicher Ursprung wie der Server (liegt unter /web/seliku/),
// Anmeldung immer mit «Authorization: MediaBrowser …» (der Server lässt keine alten Kopfzeilen zu).
const CLIENT = 'Jellyfin Seliku Web';
const VERSION = '1.0.0';

function geraeteId() {
	let id = localStorage.getItem('seliku-geraet');
	if (!id) { id = crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2); localStorage.setItem('seliku-geraet', id); }
	return id;
}

export const sitzung = {
	server: location.origin,
	token: null,
	benutzer: null, // {Id, Name, PrimaryImageTag, Policy}
	geraet: geraeteId(),
};

function kopf() {
	let a = `MediaBrowser Client="${CLIENT}", Device="${navigator.platform || 'Browser'}", DeviceId="${sitzung.geraet}", Version="${VERSION}"`;
	if (sitzung.token) a += `, Token="${sitzung.token}"`;
	return { 'Authorization': a };
}

export function q(params) {
	const p = new URLSearchParams();
	for (const [k, v] of Object.entries(params || {})) {
		if (v === undefined || v === null || v === '') continue;
		p.set(k, Array.isArray(v) ? v.join(',') : String(v));
	}
	const s = p.toString();
	return s ? '?' + s : '';
}

export async function api(pfad, { methode = 'GET', params, body, roh = false } = {}) {
	const h = kopf();
	if (body !== undefined) h['Content-Type'] = 'application/json';
	const r = await fetch(sitzung.server + pfad + q(params), { method: methode, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
	if (r.status === 401) { abmelden(); throw new Error('401'); }
	if (!r.ok) throw new Error(`${r.status} ${pfad}`);
	if (roh) return r;
	const t = await r.text();
	return t ? JSON.parse(t) : null;
}

export async function anmelden(name, passwort) {
	const r = await api('/Users/AuthenticateByName', { methode: 'POST', body: { Username: name, Pw: passwort } });
	speichern(r);
}

export async function quickConnect(code) {
	const s = await api('/QuickConnect/Initiate', { methode: 'POST' });
	code(s.Code);
	for (;;) {
		await new Promise(r => setTimeout(r, 3000));
		const z = await api('/QuickConnect/Connect', { params: { secret: s.Secret } });
		if (z.Authenticated) break;
	}
	const r = await api('/Users/AuthenticateWithQuickConnect', { methode: 'POST', body: { Secret: s.Secret } });
	speichern(r);
}

function speichern(r) {
	sitzung.token = r.AccessToken; sitzung.benutzer = r.User;
	localStorage.setItem('seliku-sitzung', JSON.stringify({ token: r.AccessToken, benutzer: r.User }));
}

/** Anmeldung der normalen Jellyfin-Weboberfläche (gleicher Ursprung), z.B. nach SSO. */
function jellyfinToken() {
	try { return JSON.parse(localStorage.getItem('jellyfin_credentials') || 'null')?.Servers?.find(x => x.AccessToken && x.UserId)?.AccessToken || null; }
	catch { return null; }
}

/** Gespeicherte Anmeldung: eigene, sonst die der normalen Jellyfin-Weboberfläche (SSO landet dort). */
export async function wiederherstellen() {
	let eigene = null;
	try { eigene = JSON.parse(localStorage.getItem('seliku-sitzung') || 'null')?.token || null; } catch {}
	for (const token of [...new Set([eigene, jellyfinToken()].filter(Boolean))]) {
		try {
			const r = await fetch(sitzung.server + '/Users/Me', { headers: { 'Authorization': kopf()['Authorization'] + `, Token="${token}"` } });
			if (!r.ok) continue;
			sitzung.token = token; sitzung.benutzer = await r.json();
			localStorage.setItem('seliku-sitzung', JSON.stringify({ token, benutzer: sitzung.benutzer }));
			return true;
		} catch {}
	}
	localStorage.removeItem('seliku-sitzung');
	return false;
}

/** SSO-Knopf des Servers (SSO-Plugin) aus dem Anmeldehinweis: {pfad, text} oder null. */
export async function ssoAnbieter() {
	try {
		const b = await (await fetch(sitzung.server + '/Branding/Configuration')).json();
		const m = (b.LoginDisclaimer || '').match(/\/sso\/OID\/(?:start|p)\/([\w.-]+)/);
		if (!m) return null;
		const t = (b.LoginDisclaimer.match(/<button[^>]*>([^<]+)<\/button>/) || [])[1];
		return { pfad: `/sso/OID/start/${m[1]}`, text: t ? t.trim() : 'Mit SSO anmelden' };
	} catch { return null; }
}
export function ssoStarten(pfad) {
	// Die Plugin-Seite legt das neue Token in jellyfin_credentials und springt zu /web/ – von dort geht es hierher zurück.
	localStorage.removeItem('seliku-sitzung');
	location.href = sitzung.server + pfad;
}

/** Zur klassischen Jellyfin-Oberfläche (Verwaltung) – mit derselben Anmeldung, ohne neu anmelden. */
export async function klassischOeffnen(ziel = '#/home') {
	try {
		const info = await (await fetch(sitzung.server + '/System/Info/Public')).json();
		const j = JSON.parse(localStorage.getItem('jellyfin_credentials') || 'null') || { Servers: [] };
		let srv = j.Servers.find(x => x.Id === info.Id);
		if (!srv) { srv = { Id: info.Id }; j.Servers.unshift(srv); }
		Object.assign(srv, { Name: info.ServerName, ManualAddress: sitzung.server, LastConnectionMode: 2, DateLastAccessed: Date.now(), AccessToken: sitzung.token, UserId: sitzung.benutzer.Id });
		localStorage.setItem('jellyfin_credentials', JSON.stringify(j));
	} catch {}
	location.href = '/web/index.html?klassisch' + ziel;
}

export async function abmelden() {
	try { if (sitzung.token) await fetch(sitzung.server + '/Sessions/Logout', { method: 'POST', headers: kopf() }); } catch {}
	localStorage.removeItem('seliku-sitzung');
	// Auch die Anmeldung der normalen Jellyfin-Oberfläche vergessen, sonst meldet sie uns gleich wieder an.
	try {
		const j = JSON.parse(localStorage.getItem('jellyfin_credentials') || 'null');
		if (j?.Servers) { j.Servers.forEach(x => { delete x.AccessToken; delete x.UserId; }); localStorage.setItem('jellyfin_credentials', JSON.stringify(j)); }
	} catch {}
	sitzung.token = null; sitzung.benutzer = null;
	location.hash = '#/';
	location.reload();
}

/** Bildadressen (Bilder brauchen keine Anmeldung). */
export const bild = {
	url(id, typ, { breite = 400, tag, index } = {}) {
		return `${sitzung.server}/Items/${id}/Images/${typ}${index !== undefined ? '/' + index : ''}${q({ maxWidth: breite, quality: 90, tag })}`;
	},
	poster(i, breite = 300) {
		if (i.Type === 'Episode') return i.SeriesId ? this.url(i.SeriesId, 'Primary', { breite, tag: i.SeriesPrimaryImageTag }) : null;
		if (i.ImageTags?.Primary) return this.url(i.Id, 'Primary', { breite, tag: i.ImageTags.Primary });
		if (i.AlbumId && i.AlbumPrimaryImageTag) return this.url(i.AlbumId, 'Primary', { breite, tag: i.AlbumPrimaryImageTag });
		if (i.Type === 'Season' && i.SeriesId) return this.url(i.SeriesId, 'Primary', { breite });
		return null;
	},
	quer(i, breite = 480) {
		if (i.Type === 'Episode') {
			if (i.ImageTags?.Primary) return this.url(i.Id, 'Primary', { breite, tag: i.ImageTags.Primary });
			if (i.ParentThumbItemId) return this.url(i.ParentThumbItemId, 'Thumb', { breite, tag: i.ParentThumbImageTag });
			if (i.ParentBackdropItemId) return this.url(i.ParentBackdropItemId, 'Backdrop', { breite, index: 0, tag: i.ParentBackdropImageTags?.[0] });
		}
		if (i.ImageTags?.Thumb) return this.url(i.Id, 'Thumb', { breite, tag: i.ImageTags.Thumb });
		if (i.BackdropImageTags?.length) return this.url(i.Id, 'Backdrop', { breite, index: 0, tag: i.BackdropImageTags[0] });
		if (i.ParentBackdropItemId) return this.url(i.ParentBackdropItemId, 'Backdrop', { breite, index: 0, tag: i.ParentBackdropImageTags?.[0] });
		return this.poster(i, breite);
	},
	hintergrund(i, breite = 1920) {
		if (i.BackdropImageTags?.length) return this.url(i.Id, 'Backdrop', { breite, index: 0, tag: i.BackdropImageTags[0] });
		if (i.ParentBackdropItemId) return this.url(i.ParentBackdropItemId, 'Backdrop', { breite, index: 0, tag: i.ParentBackdropImageTags?.[0] });
		if (i.Type === 'Episode' && i.SeriesId) return this.url(i.SeriesId, 'Backdrop', { breite, index: 0 });
		return null;
	},
	logo(i, breite = 600) {
		if (i.ImageTags?.Logo) return this.url(i.Id, 'Logo', { breite, tag: i.ImageTags.Logo });
		if (i.ParentLogoItemId) return this.url(i.ParentLogoItemId, 'Logo', { breite, tag: i.ParentLogoImageTag });
		return null;
	},
};

/** IDs wie die TV-App sie im Konto speichert (UUID mit Bindestrichen, klein); die Web-API liefert sie ohne. */
export function gid(id) {
	const x = String(id || '').replace(/-/g, '').toLowerCase();
	return x.length === 32 ? `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}` : String(id || '');
}
