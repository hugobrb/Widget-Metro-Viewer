// Genere les references uniquement a partir des quais publies dans le perimetre officiel.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/metro_catalog.json'), 'utf8'));
const perimeter = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, ''));
const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '');
const groups = new Map();
for (const row of catalog) {
    const key = row.id + '|' + normalize(row.stop_name);
    if (!groups.has(key)) groups.set(key, {name: row.stop_name, line: row.shortname,
        lineRef: 'STIF:Line::' + row.id.slice(5) + ':', stopIds: []});
    groups.get(key).stopIds.push('STIF:StopPoint:Q:' + row.stop_id.slice(5) + ':');
}
const stations = [...groups.values()].map(group => {
    const refs = perimeter.filter(row => row.line === group.lineRef && (
        group.stopIds.includes(row.ns2_stoppointref) || normalize(row.ns2_stopname) === normalize(group.name) ||
        // Certains exports du miroir regional contiennent des noms UTF-8 decodes en Latin-1.
        normalize(Buffer.from(row.ns2_stopname, 'latin1').toString('utf8')) === normalize(group.name)))
        .map(row => row.ns2_stoppointref);
    if (!refs.length || refs.some(ref => !/^STIF:StopPoint:Q:[0-9]+:$/.test(ref)))
        throw Error('Reference absente ou invalide : ligne ' + group.line + ', ' + group.name);
    return {name: group.name, line: group.line, lineRef: group.lineRef, monitoringRefs: [...new Set(refs)].sort()};
});
if (!stations.length) throw Error('Catalogue vide');
fs.writeFileSync(process.argv[3], JSON.stringify({
    source: 'https://data.iledefrance.fr/explore/dataset/perimetre-des-donnees-temps-reel-disponibles-sur-la-plateforme-dechanges-stif/',
    verifiedAt: new Date().toISOString().slice(0, 10), stations
}, null, 2) + '\n');
console.log(stations.length + ' couples ligne-station verifies.');
