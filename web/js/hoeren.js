// Hören wie am TV und auf dem Handy: eigene Stellen im Konto («seliku-hoeren»), damit jedes Gerät weiss, wo man war.
// b:<Buch> = Datei|ms|zeit · f:<Buch> = fertig · p:<Folge> = ms|zeit · t:<Buch/Podcast> = Tempo. Nur Geänderte werden geschrieben.
import { api, sitzung, gid } from './api.js';
import { praef } from './daten.js';

let werte = null; const geaendert = {};
export async function laden(neu = false) { if (werte && !neu) return; werte = { ...((await praef('seliku-hoeren').catch(() => ({}))).CustomPrefs || {}) }; }
export const wert = (k) => werte?.[k];
export function setzen(k, v) { if (!werte) werte = {}; if (v == null) delete werte[k]; else werte[k] = v; geaendert[k] = v ?? null; }
export async function hochladen() {
	if (!Object.keys(geaendert).length) return;
	const alt = await praef('seliku-hoeren'); alt.CustomPrefs = alt.CustomPrefs || {};
	for (const [k, v] of Object.entries(geaendert)) { if (v == null) delete alt.CustomPrefs[k]; else alt.CustomPrefs[k] = v; delete geaendert[k]; }
	await api('/DisplayPreferences/seliku-hoeren', { methode: 'POST', params: { client: 'seliku', userId: sitzung.benutzer.Id }, body: alt }).catch(() => {});
}
export const rueckspulen = (seit) => { const p = Date.now() - seit; return p < 1e4 ? 0 : p < 3e5 ? 3000 : p < 36e5 ? 10000 : 30000; };
const teile = /^(cd|disc|disk|teil|part|tape)\s*\d+/i;
export async function buchDateien(buch) {
	const p = { userId: sitzung.benutzer.Id, sortBy: 'SortName', enableUserData: true };
	const inhalt = (await api('/Items', { params: { ...p, parentId: buch } })).Items;
	const d = inhalt.filter(i => i.Type === 'AudioBook' || i.Type === 'Audio');
	if (d.length) return d;
	const t = inhalt.filter(i => i.IsFolder && teile.test(i.Name));
	return (await Promise.all(t.map(o => api('/Items', { params: { ...p, parentId: o.Id } }).then(r => r.Items.filter(i => i.Type === 'AudioBook' || i.Type === 'Audio'))))).flat();
}
export async function weiterhoeren() {
	await laden();
	const e = Object.entries(werte).filter(([k]) => k.startsWith('b:') || k.startsWith('p:')).map(([k, v]) => [k.slice(2), +v.split('|').pop()]).sort((a, b) => b[1] - a[1]).slice(0, 20);
	if (!e.length) return [];
	const r = await api('/Items', { params: { ids: e.map(x => x[0]).join(','), userId: sitzung.benutzer.Id, enableUserData: true } });
	const m = Object.fromEntries(r.Items.map(i => [gid(i.Id), i])); return e.map(x => m[gid(x[0])]).filter(Boolean);
}
export const gehoert = (f) => f.UserData?.Played && !wert('p:' + gid(f.Id));
