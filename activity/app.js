const DEFAULT_PICK_REASON = "Chosen from your local library based on freshness, artist spacing, and station flow.";
const config = window.RADIO_CONFIG || {};
const stateUrls = config.stateUrls || [config.stateUrl || "/api/state"];
let activeStateUrl = stateUrls[0];
const bars = document.getElementById("bars");
const viewToggle = document.getElementById("viewToggle");

if (isEmbedded()) {
  document.body.classList.add("embedded-activity");
}

for (let i = 0; i < 44; i++) {
  const bar = document.createElement("div");
  bar.className = "bar";
  const maxHeight = window.innerHeight < 720 || window.innerWidth < 1100 ? 130 : 300;
  bar.style.setProperty("--h", `${36 + Math.round(Math.random() * maxHeight)}px`);
  bar.style.animationDelay = `${Math.random() * -1.4}s`;
  bars.appendChild(bar);
}

let state = null;

viewToggle?.addEventListener("click", () => {
  document.body.classList.toggle("full-dashboard");
  viewToggle.textContent = document.body.classList.contains("full-dashboard") ? "Compact View" : "Full View";
  updateFullDashboardScale();
});

window.addEventListener("resize", updateFullDashboardScale);

async function refresh() {
  for (const url of stateUrls) {
    try {
      const response = await fetch(url, { cache: "no-store" });
      if (!response.ok) continue;
      state = await response.json();
      activeStateUrl = url;
      render();
      return;
    } catch (error) {
      // Try the next configured state URL.
    }
  }
  document.getElementById("status").textContent = "Offline";
}

function render() {
  const track = state.nowPlaying;
  document.getElementById("station").textContent = state.stationName;
  document.getElementById("status").textContent = state.status;
  document.getElementById("vibe").textContent = state.vibe;
  document.getElementById("dj").textContent = state.djLine || "Waiting for the signal.";
  document.getElementById("pickReason").textContent = track && track.pickReason ? track.pickReason : DEFAULT_PICK_REASON;
  document.getElementById("title").textContent = track ? track.title : "Waiting for signal";
  document.getElementById("artist").textContent = track ? track.artist : "Start the bot show";

  const cover = document.getElementById("cover");
  const coverImage = document.getElementById("coverImage");
  const coverText = document.getElementById("coverText");
  coverText.textContent = track ? initials(track.artist) : "RADIO SKITTLES";

  const coverUrl = track && track.coverUrl ? resolveFromStateUrl(track.coverUrl) : null;
  if (coverUrl) {
    coverImage.onload = () => cover.classList.add("has-art");
    coverImage.onerror = () => {
      cover.classList.remove("has-art");
      coverImage.removeAttribute("src");
    };
    if (coverImage.getAttribute("src") !== coverUrl) {
      cover.classList.remove("has-art");
      coverImage.src = coverUrl;
    }
  } else {
    cover.classList.remove("has-art");
    coverImage.removeAttribute("src");
  }

  document.getElementById("queue").innerHTML = state.upNext.length
    ? state.upNext.map(item => `<div class="queue-item"><div>${escapeHtml(item.title)}<div class="small">${escapeHtml(item.artist)}</div></div><div class="small">${formatTime(item.durationMs)}</div></div>`).join("")
    : '<p class="meta">No tracks queued.</p>';
  updateProgress();
}

function updateProgress() {
  if (!state || !state.progressStartedAtEpochMs || !state.progressDurationMs) {
    document.getElementById("progress").style.width = "0%";
    document.getElementById("elapsed").textContent = "0:00";
    document.getElementById("duration").textContent = state && state.nowPlaying ? formatTime(state.nowPlaying.durationMs) : "0:00";
    return;
  }
  const elapsed = Date.now() - state.progressStartedAtEpochMs;
  const boundedElapsed = Math.max(0, Math.min(elapsed, state.progressDurationMs));
  const pct = Math.max(0, Math.min(100, elapsed / state.progressDurationMs * 100));
  document.getElementById("progress").style.width = `${pct}%`;
  document.getElementById("elapsed").textContent = formatTime(boundedElapsed);
  document.getElementById("duration").textContent = formatTime(state.progressDurationMs);
}

function resolveFromStateUrl(value) {
  try {
    return new URL(value, activeStateUrl).toString();
  } catch (error) {
    return value;
  }
}

function initials(value) {
  return value.split(/\s+/).filter(Boolean).slice(0, 2).map(word => word[0]).join("").toUpperCase();
}

function formatTime(ms) {
  const seconds = Math.round((ms || 180000) / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[char]));
}

function isEmbedded() {
  try {
    return window.self !== window.top;
  } catch (error) {
    return true;
  }
}

refresh();
updateFullDashboardScale();
setInterval(refresh, 1500);
setInterval(updateProgress, 500);

function updateFullDashboardScale() {
  const designWidth = 1440;
  const padding = 36;
  const scale = Math.min(1, Math.max(0.58, (window.innerWidth - padding) / designWidth));
  document.documentElement.style.setProperty("--full-dashboard-scale", String(scale));
}
