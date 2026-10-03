# Aegis promo video

Tooling for a 56-second promo video of Aegis. It is not part of the app: nothing here is built, tested or deployed
with Aegis, and the app code is unchanged.

The video shows the real frontend (`frontend/dist`) running against a mocked API with a fictional household
(Mara and Jonas Berger, Lindenau). No real household data is used; all letters are rendered from templates in
`record/render-docs.mjs`.

## Pipeline

```sh
cd frontend && npm ci && npm run build && cd ../promo
npm install
node record/render-docs.mjs      # fictional letters -> out/pages/*.png and the upload photo
./restart-mock.sh                # mocked API + built frontend on http://localhost:4173
node record/record.mjs           # scenes -> recordings/<scene>.mp4 (takes a few minutes)
cp recordings/*.mp4 public/
python3 music/synth.py           # soundtrack -> public/soundtrack.wav (needs numpy and scipy)
npm run render                   # -> out/aegis-promo.mp4
```

`npm run studio` opens the Remotion editor for timing work. Cut points live in `video/timeline.ts`; `npm run still --
out/x.png --frame=N` renders a single frame for checking.

## Scenes

`record/record.mjs` records these scenes; `video/Promo.tsx` cuts them to the beat of the soundtrack:

- `phone-upload`: photo upload, storage, OCR and automatic classification on the phone
- `phone-pages`: the page editor splitting, rotating and reordering a scanned letter
- `desk-today`, `phone-today`: the Today view with contract deadlines and tasks
- `desk-chat`, `phone-chat`: a question to the assistant answered with cited sources
- `desk-search`, `desk-theme`, `desk-documents`: full-text search, color themes, folders and labels

`desk-status` is recorded but not used in the cut.

## How the recordings stay smooth

Full-resolution screenshots take longer than one video frame, so `record/record.mjs` runs each page in slow motion
(timers, clocks, CSS animations and the mock API slowed by `SLOW`, default 5) while it captures, then plays the
frames back at normal speed. Cursor and tap ripples are injected into the page because captures do not include the
pointer.

## Mock API

`mock/server.mjs` answers the endpoints the recorded pages use. An upload moves through stored, processing,
classifying and classified on a timer; a chat question shows its progress steps and then an answer with verified
sources. `POST /__promo/reset?slow=N` resets state between scenes. Unmocked requests are logged.

## Licenses

See [CREDITS.md](CREDITS.md).
