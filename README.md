# Gas Log for Android

Snap the pump, Trip A and the odometer → Gemini reads the numbers → you check them → one tap adds a row to
your Google Sheet. Each car is a tab. Your own formula columns (calc cost, mpg, $/mile…) are never
overwritten; if a new row has no formulas yet, the app copies them down from the row above.

People you share it with use **their own** Google account, **their own** sheet (or let the app create one),
and **their own** free Gemini key.

---

## What it does
- **Log a fill-up from photos** — snap the pump and the dash in any order; Gemini reads the numbers, the app
  checks the pump math, the odometer and Trip A, then adds a row to the car's tab.
- **Partial fills** — turn off "Filled to full" and that tank's miles and gallons roll into the next full
  tank's mpg (stored as "partial fill" in the notes column).
- **Stats** — usual mpg, cost per mile, monthly spending, an mpg-per-tank chart and a station price ranking.
- **MPG drop alert** — warns when a tank comes in 15%+ below your usual mpg.
- **Maintenance reminders** — reads the OIL CHANGES / Tire Rotation columns, shows when each is due
  (intervals in Settings) and logs new ones.
- **Remembers stations** — with location allowed, the place is filled in when you're back at a station
  you've used (kept on the phone only).
- **Works offline** — fill-ups saved without signal are queued and sent when you're back online.
- **Edit or undo** — undo the entry you just saved, or edit the last fill-up.
- **Quick launch** — a Quick Settings tile, a home-screen widget and a long-press shortcut all open straight
  to the camera.
- **Update notice** — checks this repo's releases and offers the new APK.

## Install

**[Download Gas Log for Android](https://github.com/R0x7/GasLog/releases/latest/download/GasLog.apk)**
— open that link on the phone, then allow installs from your browser when Android asks.
It always points at the newest build.

## How builds work
Every push to `main` runs [`.github/workflows/build.yml`](.github/workflows/build.yml): GitHub builds the
APK, signs it with the Gas Log key (stored encrypted in `signing/`, unlocked by the
`GASLOG_KEY_PASSPHRASE` repository secret), and publishes it as the latest release. Each build gets a
higher version number, so installing a new one updates the app in place and keeps your settings.

## One-time setup

### 1. Add the signing secret to GitHub
Repo **Settings → Secrets and variables → Actions → New repository secret**:
name `GASLOG_KEY_PASSPHRASE`, value = the passphrase you were given. Keep a copy in a password manager —
it's the only way to sign future updates (details in [`signing/README.md`](signing/README.md)).

### 2. Let the app use Google Sheets (Google Cloud)
1. Go to [console.cloud.google.com](https://console.cloud.google.com) → create a project named **Gas Log**.
2. **APIs & Services → Library** → search **Google Sheets API** → **Enable**.
3. **Google Auth Platform** (APIs & Services → OAuth consent screen) → **Get started**:
   app name *Gas Log*, your email, audience **External**, contact email → **Create**.
4. **Data Access → Add or remove scopes** → paste `https://www.googleapis.com/auth/spreadsheets` →
   **Update** → **Save**.
5. **Clients → Create client** → type **Android** → package name `com.gaslog.app` →
   SHA-1 `54:04:38:50:F4:87:ED:71:33:6F:74:86:DA:81:0C:FB:9E:FC:FB:39` → **Create**.
6. **Audience → Publish app**. Everyone will see *"Google hasn't verified this app"* the first time —
   they tap **Advanced → Go to Gas Log**. That's normal for a private app (up to 100 people).
   *Alternative:* leave it in Testing and add each person's Gmail under **Test users** — but Google then
   makes them re-approve every 7 days.

### 3. First launch
1. Paste the link to your sheet (for you: *The great gas doc*) → pick your Google account → **Allow**.
2. Tap **Get a free key**, create a Gemini API key in Google AI Studio, paste it → **Save key**.

---

## Sharing it
Send people the download link above. Each person:
- installs it and allows Google access,
- pastes a link to *their* sheet (their account must be able to edit it) **or** taps
  **Create a new gas log**, which makes a sheet in their Drive with your column layout and mpg formulas,
- pastes their own Gemini key.

Want someone to log into *your* sheet (e.g. a shared family car)? Share the sheet with them as an Editor
and send them its link.

> Heads-up: Google is rolling out developer verification for sideloaded apps; it reaches the US in 2027.
> After that you'll need to register as an Android developer for others to install your APK normally.

## Working on it locally (optional)
Open the folder in [Android Studio](https://developer.android.com/studio) and run it on a phone. Builds from
Android Studio use your computer's debug key, so for Google sign-in to work in those builds, add a second
Android client in Google Cloud with your debug SHA-1
(`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`).

## How the sheet is read
- A tab counts as a car when row 1 starts with `date | trip (or odometer) | trip a`. Tabs named
  "Template" are skipped.
- New rows go right after the last row with anything in columns A–H. Total/Average/Maximum/Minimum rows
  at the top are ignored.
- Columns written: A date · B odometer · C trip a · D place · E octane · F $/G · G gallons · H cost · I notes.

## Troubleshooting
| Problem | Fix |
|---|---|
| Sign-in fails instantly, "Error 10" or DEVELOPER_ERROR | The SHA-1 or package name in Google Cloud doesn't match (setup step 2.5). |
| "Google Sheets API has not been used in project…" | Enable the Sheets API (setup step 2.2). |
| "The caller does not have permission" | That Google account can't edit the sheet — share it as Editor. |
| "Gemini rejected the API key" | Paste the key again in **Settings**. |
| Quota / 429 errors from Gemini | Wait a minute; check limits at aistudio.google.com. |
| A build fails on GitHub | Open the failed run under the repo's **Actions** tab for the error. Built for AGP 9.4, Kotlin 2.4.10, compileSdk 37, Compose BOM 2026.09.00. |
