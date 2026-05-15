---
layout: default
title: storyvox — neural-voice audiobook player for any text you have
description: Seventeen fiction backends, three in-process neural voice families, optional Azure HD cloud voices. Stream chapters from Royal Road, GitHub, RSS, EPUB, Outline, Memory Palace, Project Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource, Radio (30k stations), Notion, Hacker News, arXiv, PLOS, and Discord. Brass-on-warm-dark Library Nocturne aesthetic. Free, GPL-3.0, no telemetry.
image: /screenshots/03-reader.png
---

<section class="hero">
  <div class="hero-text">
    <h1>storyvox</h1>
    <p class="tagline">Audiobook everything. Stream chapters from anywhere, read aloud in a brass-warm neural voice.</p>
    <p>
      Seventeen fiction backends side by side — <a href="https://royalroad.com">Royal Road</a>,
      <a href="https://github.com">GitHub</a>, RSS feeds, EPUB files on your device,
      <a href="https://www.getoutline.com">Outline</a> wikis, your self-hosted
      <a href="https://github.com/techempower-org/mempalace">Memory Palace</a>,
      <a href="https://www.gutenberg.org/">Project Gutenberg</a>, AO3, Standard Ebooks, Wikipedia,
      Wikisource, Radio (30k+ stations), <a href="https://notion.so">Notion</a>, Hacker News,
      arXiv, PLOS, Discord — all read aloud by an <strong>in-process neural TTS engine</strong>
      that runs entirely on-device. A hybrid reader/audiobook view highlights the spoken sentence
      in brass as you listen.
    </p>
    <p class="cta-row">
      <a class="cta-primary" id="cta-download"
         href="https://github.com/techempower-org/storyvox/releases/latest"
         data-base-href="https://github.com/techempower-org/storyvox/releases">
        <span class="cta-label">Download latest APK</span>
        <span class="cta-version" aria-hidden="true"></span>
      </a>
      <a class="cta-secondary" href="https://github.com/techempower-org/storyvox">Source on GitHub</a>
      <a class="cta-tertiary" href="install/">Install guide →</a>
    </p>
    <p class="cta-fineprint muted">
      Sideload, Android 8.0+, ~140 MB APK. No Play Store. No tracking. No in-app purchases.
    </p>
  </div>
  <div class="hero-art">
    <dark-image
      src-dark="screenshots/03-reader.png"
      src-light="screenshots/03-reader-light.png"
      alt="storyvox reader playing The Archmage Coefficient with the spoken sentence highlighted in brass.">
      <img src="screenshots/03-reader.png" alt="storyvox reader playing The Archmage Coefficient with the spoken sentence highlighted in brass." />
    </dark-image>
  </div>
</section>

<section class="why">
  <h2>Why storyvox</h2>
  <div class="why-grid">
    <div class="card">
      <h3>On-device neural TTS</h3>
      <p>
        Three voice families ship — <a href="https://github.com/rhasspy/piper">Piper</a> (compact),
        <a href="https://github.com/hexgrad/kokoro">Kokoro</a> (multi-speaker), and
        <strong>KittenTTS</strong> (lightest tier, designed for slow devices). Voices download
        once, then live on-device. No cloud, no API keys, no per-character billing.
      </p>
    </div>
    <div class="card">
      <h3>Reader view, in sync</h3>
      <p>
        Swipe between audiobook view (cover + scrubber + transport) and reader view (chapter text).
        The current sentence glides along in brass, matching the read-aloud rhythm — so you can
        listen, read, or both at once.
      </p>
    </div>
    <div class="card">
      <h3>AI chat per fiction</h3>
      <p>
        Per-book chat across seven LLM providers, with grounding (current sentence / chapter /
        whole book), cross-fiction memory, function calling ("queue chapter 5", "open Voice
        Library"), and multi-modal image input. Brass-edged tool cards show in-flight state.
      </p>
    </div>
    <div class="card">
      <h3>Smooth on slow hardware</h3>
      <p>
        Tier 3 multi-engine parallel synthesis — 1–8 VoxSherpa instances × N threads each, twin
        sliders in Settings → Performance. A producer pinned to <code>URGENT_AUDIO</code> and PCM
        cache buffering keep playback gapless even when Piper-high struggles on a Helio P22T.
      </p>
    </div>
    <div class="card">
      <h3>Optional cloud voices (BYOK)</h3>
      <p>
        Bring your own Azure key for studio-grade
        <a href="https://learn.microsoft.com/azure/ai-services/speech-service/text-to-speech">Azure HD voices</a>.
        Offline fallback to your local voice if the network drops or your key expires. Opt-in,
        never required, never billed by storyvox.
      </p>
    </div>
    <div class="card">
      <h3>Brass on warm dark</h3>
      <p>
        Library Nocturne theme — brass accents, EB Garamond chapter body, Inter UI. Light mode
        is parchment cream. Wear OS gets the same theme with a circular brass scrubber. Adaptive
        grid: phones (2 col), tablets (5), foldables (more).
      </p>
    </div>
  </div>
</section>

<section class="sources">
  <h2>Seventeen fiction backends, side by side</h2>
  <p class="muted">
    A plugin-seam architecture means each backend is ~4 touchpoints. Adding a new one auto-surfaces
    in <strong>Settings → Plugins</strong>. Each has its own on/off toggle.
  </p>
  <div class="sources-grid">
    <a class="source-card" href="https://royalroad.com">
      <span class="source-glyph" aria-hidden="true">RR</span>
      <h3>Royal Road</h3>
      <p>The full filter set — tags include/exclude, status, type, length, rating, content warnings, sort. Follows tab syncs your bookmarks.</p>
    </a>
    <a class="source-card" href="https://github.com/techempower-org/storyvox-registry">
      <span class="source-glyph" aria-hidden="true">GH</span>
      <h3>GitHub</h3>
      <p>Curated <strong>storyvox-registry</strong> plus live <code>/search/repositories</code>. OAuth Device Flow lifts the 60→5000 req/hr cap.</p>
    </a>
    <a class="source-card" href="https://github.com/techempower-org/storyvox-feeds">
      <span class="source-glyph" aria-hidden="true">RSS</span>
      <h3>RSS / Atom feeds</h3>
      <p>Any RSS or Atom feed, plus a managed suggested-feeds list from <strong>storyvox-feeds</strong>.</p>
    </a>
    <a class="source-card" href="https://www.getoutline.com">
      <span class="source-glyph" aria-hidden="true">OL</span>
      <h3>Outline</h3>
      <p>Self-hosted Outline wiki as a fiction backend. Paste your URL + API token; collections become fictions, documents become chapters.</p>
    </a>
    <a class="source-card" href="https://github.com/techempower-org/mempalace">
      <span class="source-glyph" aria-hidden="true">MP</span>
      <h3>Memory Palace</h3>
      <p>Your own self-hosted Memory Palace. Drawers become chapters; the palace becomes a personal canon.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">EPUB</span>
      <h3>Local EPUB</h3>
      <p>Open any folder via the system file picker. OPF parser splits EPUBs into chapters; works fully offline.</p>
    </a>
    <a class="source-card" href="https://www.gutenberg.org/">
      <span class="source-glyph" aria-hidden="true">PG</span>
      <h3>Project Gutenberg</h3>
      <p>70,000+ public-domain books. Search by author, title, or subject; download EPUB or read inline.</p>
    </a>
    <a class="source-card" href="https://archiveofourown.org/">
      <span class="source-glyph" aria-hidden="true">AO3</span>
      <h3>Archive of Our Own</h3>
      <p>Per-tag feeds and official EPUBs. Browse by fandom, ship, or trope tag.</p>
    </a>
    <a class="source-card" href="https://standardebooks.org/">
      <span class="source-glyph" aria-hidden="true">SE</span>
      <h3>Standard Ebooks</h3>
      <p>Hand-curated, typographically polished public-domain classics. The good edition.</p>
    </a>
    <a class="source-card" href="https://en.wikipedia.org/">
      <span class="source-glyph" aria-hidden="true">W</span>
      <h3>Wikipedia</h3>
      <p>Any article, heading-split into chapters. Long-form articles become quick audiobooks.</p>
    </a>
    <a class="source-card" href="https://en.wikisource.org/">
      <span class="source-glyph" aria-hidden="true">WS</span>
      <h3>Wikisource</h3>
      <p>Walks multi-part works as <code>/Subpage</code> chapters. Free, primary-source texts.</p>
    </a>
    <a class="source-card" href="https://www.radio-browser.info/">
      <span class="source-glyph" aria-hidden="true">FM</span>
      <h3>Radio</h3>
      <p>Five curated stations (KVMR, Cap Public, KQED, KCSB, SomaFM) plus <strong>Radio Browser</strong> search across 30,000+ stations.</p>
    </a>
    <a class="source-card" href="https://notion.so">
      <span class="source-glyph" aria-hidden="true">N</span>
      <h3>Notion</h3>
      <p>Any Notion database — defaults to the techempower.org content DB. Paste an integration token and you're in.</p>
    </a>
    <a class="source-card" href="https://news.ycombinator.com/">
      <span class="source-glyph" aria-hidden="true">HN</span>
      <h3>Hacker News</h3>
      <p>Top stories + Ask HN / Show HN threads with comments narrated in order.</p>
    </a>
    <a class="source-card" href="https://arxiv.org/">
      <span class="source-glyph" aria-hidden="true">arX</span>
      <h3>arXiv</h3>
      <p>Abstracts in cs.AI and other categories — let the neural voice read the cutting edge while you commute.</p>
    </a>
    <a class="source-card" href="https://plos.org/">
      <span class="source-glyph" aria-hidden="true">PLOS</span>
      <h3>PLOS</h3>
      <p>Open-access, peer-reviewed science papers. Hear research instead of skimming it.</p>
    </a>
    <a class="source-card" href="https://discord.com/">
      <span class="source-glyph" aria-hidden="true">DC</span>
      <h3>Discord</h3>
      <p>Serialized fiction in Discord channels — channels are fictions, messages are chapters. Bot-token auth.</p>
    </a>
  </div>
</section>

<section class="voices">
  <h2>Three voice families, all on-device</h2>
  <p class="muted">
    Voices download on demand from the <code>voices-v2</code> release; nothing is bundled in the APK.
    The voice picker shows what's installed and what's available. <a href="voices/">Full voice catalog →</a>
  </p>
  <div class="voice-grid">
    <div class="voice-card">
      <div class="voice-tier">Compact</div>
      <h3>Piper</h3>
      <p class="voice-size">~14–30 MB per voice</p>
      <p>
        Single-speaker neural voices in dozens of languages. Quality / x-low / low / medium /
        high tiers per voice. Punches well above its weight on phones from 2018.
      </p>
      <p class="voice-meta muted">
        <a href="https://github.com/rhasspy/piper">rhasspy/piper</a>
      </p>
    </div>
    <div class="voice-card voice-card-flagship">
      <div class="voice-tier">Multi-speaker</div>
      <h3>Kokoro</h3>
      <p class="voice-size">~330 MB (shared across voices)</p>
      <p>
        One model, many speakers — male, female, and accent variants share weights. The sweet
        spot for modern Android tablets. Brass-warm narration that doesn't sound robotic.
      </p>
      <p class="voice-meta muted">
        <a href="https://github.com/hexgrad/kokoro">hexgrad/kokoro</a>
      </p>
    </div>
    <div class="voice-card">
      <div class="voice-tier">Lightest</div>
      <h3>KittenTTS</h3>
      <p class="voice-size">~24 MB (shared, 8 en_US speakers)</p>
      <p>
        The new lightest tier — designed for slow devices where Piper-high struggles. Eight en_US
        speakers share a single 24 MB model. The "first chapter in 10 seconds" voice family.
      </p>
      <p class="voice-meta muted">In-tree (storyvox · v0.5.x)</p>
    </div>
  </div>
  <p class="voices-cloud">
    <strong>Optional cloud:</strong> Bring your own
    <a href="https://learn.microsoft.com/azure/ai-services/speech-service/text-to-speech">Azure HD</a>
    key for studio-grade narration on slow devices. Offline fallback to your local voice if your
    key fails or the network drops. Opt-in, never required.
  </p>
</section>

<section class="screens">
  <h2>What it looks like</h2>
  <p class="muted">Galaxy Tab A7 Lite, 800×1340 px. Tap the theme toggle (top right) to flip light/dark. <a href="screenshots/">Full gallery →</a></p>
  <div class="screens-grid">
    <figure>
      <dark-image src-dark="screenshots/01-browse.png" src-light="screenshots/01-browse-light.png" alt="Browse tab">
        <img src="screenshots/01-browse.png" alt="Browse tab" loading="lazy" />
      </dark-image>
      <figcaption>Browse — infinite-scroll across every source.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/02-detail.png" src-light="screenshots/02-detail-light.png" alt="Fiction detail">
        <img src="screenshots/02-detail.png" alt="Fiction detail" loading="lazy" />
      </dark-image>
      <figcaption>Fiction detail — synopsis, tags, chapter list with read state.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/04-library.png" src-light="screenshots/04-library-light.png" alt="Library tab">
        <img src="screenshots/04-library.png" alt="Library tab" loading="lazy" />
      </dark-image>
      <figcaption>Library — currently-listening with progress + smart resume.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/06-filter-dark.png" src-light="screenshots/06-filter.png" alt="Royal Road filter sheet">
        <img src="screenshots/06-filter-dark.png" alt="Royal Road filter sheet" loading="lazy" />
      </dark-image>
      <figcaption>Royal Road filters — sort, tags include/exclude, content warnings.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/06b-filter-github-dark.png" src-light="screenshots/06b-filter-github.png" alt="GitHub filter sheet">
        <img src="screenshots/06b-filter-github-dark.png" alt="GitHub filter sheet" loading="lazy" />
      </dark-image>
      <figcaption>GitHub filters — stars, language, topics, last-pushed.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/05-settings.png" src-light="screenshots/05-settings-light.png" alt="Settings hub">
        <img src="screenshots/05-settings.png" alt="Settings hub" loading="lazy" />
      </dark-image>
      <figcaption>Settings — brass-edged section hub, eight cards.</figcaption>
    </figure>
  </div>
</section>

<section class="open">
  <h2>Open. Free. Yours.</h2>
  <div class="open-grid">
    <div class="open-card">
      <h3>GPL-3.0</h3>
      <p>
        Inherited from the TTS engine — also a posture. Read the source, modify it, ship your
        fork. No closed components. <a href="https://github.com/techempower-org/storyvox/blob/main/LICENSE">License →</a>
      </p>
    </div>
    <div class="open-card">
      <h3>No telemetry</h3>
      <p>
        Zero analytics. Zero crash reporting. Zero "anonymous usage". The app talks to the
        backends you opt into, the voice repo for downloads, and nothing else.
      </p>
    </div>
    <div class="open-card">
      <h3>No in-app purchases</h3>
      <p>
        No subscriptions, no premium tier, no upsell. Azure HD is BYOK — you pay Microsoft
        directly if you want it. storyvox doesn't take a cut.
      </p>
    </div>
    <div class="open-card">
      <h3>Sideload from GitHub</h3>
      <p>
        Not on the Play Store yet. <a href="https://github.com/techempower-org/storyvox/releases">Grab the APK from Releases</a>,
        enable "Install unknown apps" once, open the file. Three taps and you're in.
      </p>
    </div>
  </div>
</section>

<section class="recent">
  <h2>What just shipped</h2>
  <p>
    <strong>v0.5.44</strong> — Nav restructure: Settings becomes a primary nav destination,
    Browse and Follows tuck under Library. InstantDB sync brings settings + secrets across
    devices. The Settings hub gets follow-through — seven remaining hub cards land as dedicated
    subscreens. Four QA findings closed (#450, #452, #459, #461).
    <a href="https://github.com/techempower-org/storyvox/releases/tag/v0.5.44">Full release notes →</a>
  </p>
  <p>
    <strong>Earlier in v0.5:</strong> <strong>seventeen fiction backends</strong> behind a
    plugin-seam (Hacker News, arXiv, PLOS, Discord, Wikisource, Radio Browser); <strong>three
    voice families</strong> with KittenTTS as the new lightest tier; <strong>AI heavies</strong>
    — cross-fiction memory (<a href="https://github.com/techempower-org/storyvox/issues/217">#217</a>),
    function calling (<a href="https://github.com/techempower-org/storyvox/issues/216">#216</a>),
    multi-modal image input (<a href="https://github.com/techempower-org/storyvox/issues/215">#215</a>);
    magical brass voice-settings icon on the play screen; Plugin manager Settings hub iterating
    the registry; Wear OS Library Nocturne with circular brass scrubber on round watches.
  </p>
  <p class="muted">
    See the <a href="https://github.com/techempower-org/storyvox/wiki">wiki</a> for build, voice
    catalog, and troubleshooting reference, or <a href="architecture/">how the modules fit
    together</a>.
  </p>
</section>

<footer class="site-footer">
  <p>
    storyvox is licensed under the
    <a href="https://github.com/techempower-org/storyvox/blob/main/LICENSE">GNU General Public License v3.0</a>.
    Built by <a href="https://github.com/jphein">JP Hein</a>
    with teams of <a href="https://www.anthropic.com/claude-code">Claude Code</a> agents.
  </p>
</footer>

<script>
  // Resolve the "Download latest APK" button to the actual signed APK asset from
  // the latest release on GitHub. Falls back to the Releases page on any error.
  (() => {
    const btn = document.getElementById('cta-download');
    if (!btn) return;
    const versionEl = btn.querySelector('.cta-version');
    fetch('https://api.github.com/repos/techempower-org/storyvox/releases/latest', {
      headers: { 'Accept': 'application/vnd.github+json' }
    })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then(rel => {
        if (!rel || !rel.tag_name) return;
        // Prefer the signed APK asset; otherwise just deep-link to the release page.
        const apk = (rel.assets || []).find(a => /\.apk$/i.test(a.name));
        if (apk && apk.browser_download_url) {
          btn.href = apk.browser_download_url;
        } else if (rel.html_url) {
          btn.href = rel.html_url;
        }
        if (versionEl) versionEl.textContent = ' · ' + rel.tag_name;
      })
      .catch(() => { /* keep static fallback href */ });
  })();
</script>
