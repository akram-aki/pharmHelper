// Regenerates the app's drug assets from a CHIFA_OFFICINE CSV export.
//
//   node tools/build_drug_assets.mjs "C:/path/to/CSV_EXPORT"
//
// Reads medicament.csv (catalogue) + forme.csv (galenic forms) and writes:
//   app/src/main/assets/drug_names.txt  one normalized nom_com per line
//   app/src/main/assets/drug_db.tsv     nom_com \t dosage \t conditionnement \t forme
//
// Both assets must always be generated together: DrugDb.variantsFor() looks up
// variants by exact name string, so the two files have to share one source and
// one normalization.

import { readFileSync, writeFileSync } from "fs";
import { join } from "path";

const exportDir = process.argv[2];
if (!exportDir) {
  console.error("usage: node tools/build_drug_assets.mjs <CSV_EXPORT dir>");
  process.exit(1);
}
const assetsDir = join(import.meta.dirname, "..", "app", "src", "main", "assets");

/** Minimal RFC4180 parser: handles quoted fields containing commas/newlines. */
function parseCsv(text) {
  const rows = [];
  let row = [], field = "", inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; }
        else inQuotes = false;
      } else field += c;
    } else if (c === '"') inQuotes = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\n") { row.push(field); rows.push(row); row = []; field = ""; }
    else if (c !== "\r") field += c;
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  return rows;
}

/** Same shape VignetteParser.normalize() produces for OCR lines. */
const norm = (v) =>
  String(v ?? "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toUpperCase()
    // The export writes decimals with commas ("0,05G%"); OCR reads dots, and
    // the comma would otherwise be stripped away entirely ("005G%").
    .replace(/(?<=\d),(?=\d)/g, ".")
    .replace(/[^A-Z0-9 .\-\/+'%()]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

function table(file) {
  const rows = parseCsv(readFileSync(join(exportDir, file), "utf8"));
  const header = rows[0];
  const idx = Object.fromEntries(header.map((h, i) => [h.trim(), i]));
  return { idx, rows: rows.slice(1).filter((r) => r.some((v) => v !== "")) };
}

// code_forme -> short galenic label (COMP., GLES., SUPPO. ...)
const forme = table("forme.csv");
const formeShort = new Map();
for (const r of forme.rows) {
  const code = String(r[forme.idx.code_forme] ?? "").trim();
  if (!code) continue;
  formeShort.set(code, norm(r[forme.idx.libelle_court] || r[forme.idx.libelle]));
}

const med = table("medicament.csv");
const names = new Set();
const tuples = new Set();

for (const r of med.rows) {
  const nom = norm(r[med.idx.nom_com]);
  if (nom.length < 3) continue;
  names.add(nom);

  // dosage and its unit live in separate columns ("40" + "MG"); the app
  // compares against compact OCR tokens, so join them without spaces.
  const dose = norm(r[med.idx.dosage]);
  const unit = norm(r[med.idx.unite]);
  const dosage = (dose && unit && !dose.includes(unit) ? dose + unit : dose).replace(/\s+/g, "");

  const cond = norm(r[med.idx.conditionnement]);
  const fm = formeShort.get(String(r[med.idx.code_forme] ?? "").trim()) ?? "";
  tuples.add([nom, dosage, cond, fm].join("\t"));
}

const sortedNames = [...names].sort();
const sortedTuples = [...tuples].sort();
writeFileSync(join(assetsDir, "drug_names.txt"), sortedNames.join("\n") + "\n", "utf8");
writeFileSync(join(assetsDir, "drug_db.tsv"), sortedTuples.join("\n") + "\n", "utf8");

console.log(`medicament rows : ${med.rows.length}`);
console.log(`drug_names.txt  : ${sortedNames.length} names`);
console.log(`drug_db.tsv     : ${sortedTuples.length} variants`);
