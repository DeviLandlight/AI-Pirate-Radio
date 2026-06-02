# Radio Skittles Activity

This folder contains the static visualizer page that can be deployed to Cloudflare Pages.

For local bot use, `config.js` points at `/api/state`, which works when the bot serves this folder at `http://localhost:8787`.

For Cloudflare Pages, update `config.js` so `stateUrls` includes the Discord Activity proxy path first, then the public HTTPS URL for browser testing:

```js
window.RADIO_CONFIG = {
  stateUrls: [
    "/api/state",
    "https://api.radio-skittles.com/api/state"
  ]
};
```

The page also resolves relative cover-art URLs from that same state API URL.
