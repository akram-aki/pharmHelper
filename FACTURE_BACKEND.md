# Facture backend (Google Apps Script + Drive)

The "Scan factures" feature uploads each scanned invoice as a **base64 PDF + metadata**
to the _same_ Google Apps Script Web App the app already uses for expired-medicine
records (`assets/sheets_config.json` → `script_url` + `secret`). No new endpoint,
account, secret, or CI change is needed — you only extend the script.

The script (running as the pharmacy owner's account, so it may create Drive files)
does two things per upload:

1. writes the PDF into a **Drive folder** (`FACTURES_FOLDER_ID`);
2. appends one **index row** to a "Factures" spreadsheet so the files are browsable.

The Android app never talks to Drive directly and needs no Google credentials.

---

## 1. One-time Drive setup

1. In the owner's Google Drive, create a folder, e.g. **`Factures pharmacie`**.
2. Open it and copy the folder id from the URL
   (`https://drive.google.com/drive/folders/`**`<THIS_PART>`**).
3. Paste it into `FACTURES_FOLDER_ID` below.

The index spreadsheet is created automatically on first upload (named `factures index`,
in the same Drive). To keep everything in the invoice folder, the snippet creates it
inside `FACTURES_FOLDER_ID`.

## 2. Extend the Apps Script

Add the constant and the two functions below to the existing script project, then wire
the `action` branch into your current `doPost(e)`.

```javascript
// ---- Facture support ---------------------------------------------------------
var FACTURES_FOLDER_ID = "1famdlXgJQjcMLvCqJidVXzpF_5X6frWK";
var FACTURES_SHEET_NAME = "Factures";

// In your existing doPost(e): after parsing the body and checking the secret,
// branch on action BEFORE the existing records-append logic:
//
//   var body = JSON.parse(e.postData.contents);
//   if (body.secret !== SECRET) return json({ ok: false, error: 'bad secret' });
//   if (body.action === 'uploadFacture') return handleFactureUpload(body);
//   ... existing appendRecords logic ...

function handleFactureUpload(body) {
  try {
    var meta = body.meta || {};
    var bytes = Utilities.base64Decode(body.pdfBase64);
    var name = factureFileName(meta);
    var blob = Utilities.newBlob(bytes, "application/pdf", name);

    var folder = DriveApp.getFolderById(FACTURES_FOLDER_ID);
    var file = folder.createFile(blob);

    facturesSheet().appendRow([
      meta.scanTimestamp || new Date().toISOString(),
      meta.supplier || "",
      meta.invoiceDate || "",
      meta.pageCount || "",
      meta.sizeBytes || "",
      file.getId(),
      file.getUrl(),
    ]);

    return json({ ok: true, fileId: file.getId(), url: file.getUrl() });
  } catch (err) {
    return json({ ok: false, error: String(err) });
  }
}

// GET ?action=listFactures&secret=... → the index rows as JSON (for the future
// desktop app; unused by Android). Add to your existing doGet(e), or add a doGet.
function handleListFactures() {
  var sheet = facturesSheet();
  var values = sheet.getDataRange().getValues();
  var rows = values.slice(1).map(function (r) {
    return {
      scanTimestamp: r[0],
      supplier: r[1],
      invoiceDate: r[2],
      pageCount: r[3],
      sizeBytes: r[4],
      fileId: r[5],
      url: r[6],
    };
  });
  return json({ ok: true, factures: rows });
}

function facturesSheet() {
  var folder = DriveApp.getFolderById(FACTURES_FOLDER_ID);
  var it = folder.getFilesByName("factures index");
  var ss = it.hasNext()
    ? SpreadsheetApp.open(it.next())
    : (function () {
        var created = SpreadsheetApp.create("factures index");
        // move the new spreadsheet into the invoice folder
        var f = DriveApp.getFileById(created.getId());
        folder.addFile(f);
        DriveApp.getRootFolder().removeFile(f);
        return created;
      })();
  var sheet = ss.getSheetByName(FACTURES_SHEET_NAME);
  if (!sheet) {
    sheet = ss.getSheets()[0];
    sheet.setName(FACTURES_SHEET_NAME);
    sheet.appendRow([
      "scanTimestamp",
      "supplier",
      "invoiceDate",
      "pageCount",
      "sizeBytes",
      "fileId",
      "url",
    ]);
  }
  return sheet;
}

function factureFileName(meta) {
  var date = (meta.invoiceDate || meta.scanTimestamp || "facture").replace(
    /[/:\\]/g,
    "-",
  );
  var supplier = (meta.supplier || "").replace(/[^\w\-À-ÿ ]/g, "").trim();
  var base = supplier ? date + "_" + supplier : date;
  return base.substring(0, 80) + ".pdf";
}

// Small helper if the script doesn't already have one:
function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(
    ContentService.MimeType.JSON,
  );
}
```

## 3. Redeploy

Deploy → **Manage deployments** → edit the existing Web App deployment → **New version**
→ Deploy. Keep the same deployment URL so `sheets_config.json` stays valid.
"Execute as: **Me** (owner)", "Who has access: **Anyone**" (matching the current setup —
the shared `secret` is the gate).

## 4. Request/response contract (what the app sends)

`POST <script_url>` with JSON:

```json
{
  "secret": "…",
  "action": "uploadFacture",
  "meta": {
    "id": "facture_…",
    "supplier": "…",
    "invoiceDate": "23/07/2026",
    "scanTimestamp": "2026-07-23T10:15:00",
    "pageCount": 2,
    "sizeBytes": 481203
  },
  "pdfBase64": "JVBERi0xLj…"
}
```

Success → `{ "ok": true, "fileId": "…", "url": "…" }`.
Anything else (or `ok:false`) makes the app keep the facture queued and retry later.

---

## Desktop app — the easy path for later (not built now)

The index sheet + Drive files are the stable contract:

1. **Zero-code today:** the pharmacist opens the shared `Factures pharmacie` folder on any
   desktop (Drive web/app) and browses/prints the PDFs directly.
2. **Light desktop app:** `GET <script_url>?action=listFactures&secret=…` returns the index
   rows; download each PDF by `fileId` (`https://drive.google.com/uc?export=download&id=<fileId>`)
   or open `url`, then print.
3. **Full integration:** use the Google Drive API with the pharmacy account for
   search/sync/print.

No schema change is required for any of these — the Android upload side already writes
everything the desktop needs.
