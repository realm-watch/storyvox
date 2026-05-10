---
layout: default
title: storyvox — neural-voice audiobook player for serial fiction
description: Offline neural TTS. Royal Road and GitHub fiction sources. Brass-on-warm-dark Library Nocturne aesthetic. Free, GPL-3.0, no per-character billing.
---

<section class="hero">
  <div class="hero-text">
    <h1>storyvox</h1>
    <p class="tagline">A neural-voice audiobook player for serial fiction.</p>
    <p>
      Stream chapters from <a href="https://royalroad.com">Royal Road</a> and
      <a href="https://github.com/jphein/storyvox-registry">GitHub</a>, read aloud by an
      <strong>offline neural TTS engine</strong>, with a hybrid reader/audiobook view that
      highlights the spoken sentence as you listen.
    </p>
    <p class="cta-row">
      <a class="cta-primary" href="install/">Install</a>
      <a class="cta-secondary" href="https://github.com/jphein/storyvox">Source on GitHub</a>
    </p>
  </div>
  <div class="hero-art">
    <img src="screenshots/03-reader.png" alt="storyvox reader playing The Archmage Coefficient with the spoken sentence highlighted in brass." />
  </div>
</section>

<section class="why">
  <h2>Why storyvox</h2>
  <div class="why-grid">
    <div class="card">
      <h3>Offline neural TTS</h3>
      <p>
        Two voice families ship — <a href="https://github.com/rhasspy/piper">Piper</a> (compact, ~14–30 MB)
        and <a href="https://github.com/hexgrad/kokoro">Kokoro</a> (multi-speaker, ~330 MB).
        Voices download once, then live on-device. No cloud, no API keys, no per-character billing.
      </p>
    </div>
    <div class="card">
      <h3>Brass on warm dark</h3>
      <p>
        Library Nocturne theme — brass accents, EB Garamond chapter body, Inter UI. Light mode is
        parchment cream. Adaptive grid for phones (2 col), tablets (5 col), and foldables.
      </p>
    </div>
    <div class="card">
      <h3>Three fiction sources</h3>
      <p>
        Browse Royal Road with the full filter set, browse curated and discoverable GitHub
        fiction repositories via <a href="https://github.com/jphein/storyvox-registry">storyvox-registry</a>,
        or read your <a href="https://github.com/jphein/mempalace">MemPalace</a> drawers as chapters
        when on the home network.
      </p>
    </div>
    <div class="card">
      <h3>Reader view, in sync</h3>
      <p>
        Swipe between audiobook view (cover + scrubber + transport) and reader view (chapter text).
        The current sentence glides along in brass, matching the read-aloud rhythm.
      </p>
    </div>
    <div class="card">
      <h3>Smooth on slow hardware</h3>
      <p>
        PCM cache buffering and optional render-ahead modes keep playback gapless even when
        Piper-high struggles on a Helio P22T. Tuned on a Galaxy Tab A7 Lite.
      </p>
    </div>
    <div class="card">
      <h3>Free and open</h3>
      <p>
        GPL-3.0. Inheritable from the TTS engine, but also a posture: no telemetry, no analytics,
        no upsell. Sideload the APK from <a href="https://github.com/jphein/storyvox/releases">Releases</a>
        and you're done.
      </p>
    </div>
  </div>
</section>

<section class="screens">
  <h2>What it looks like</h2>
  <p class="muted">Galaxy Tab A7 Lite, 800×1340 px. <a href="screenshots/">Full gallery →</a></p>
  <div class="screens-grid">
    <figure>
      <img src="screenshots/01-browse.png" alt="Browse tab" />
      <figcaption>Browse</figcaption>
    </figure>
    <figure>
      <img src="screenshots/02-detail.png" alt="Fiction detail" />
      <figcaption>Fiction detail</figcaption>
    </figure>
    <figure>
      <img src="screenshots/04-library.png" alt="Library tab" />
      <figcaption>Library</figcaption>
    </figure>
    <figure>
      <img src="screenshots/05-settings.png" alt="Settings" />
      <figcaption>Settings</figcaption>
    </figure>
  </div>
</section>

<section class="recent">
  <h2>What just shipped</h2>
  <p>
    <strong>v0.4.55</strong> — sleep timer shake-to-extend during the fade tail (#150),
    backend on/off toggles for Royal Road / GitHub / Memory Palace (#221A), bottom-nav
    default lands on Playing instead of Library, GitHub gist scope fix (#234).
    <a href="https://github.com/jphein/storyvox/releases/tag/v0.4.55">Full release notes →</a>
  </p>
  <p>
    <strong>v0.4 highlights since v0.4.31:</strong> seven-provider AI chat with grounding
    controls, GitHub OAuth Device Flow (#91) with My Repos / Starred / Gists tabs, MemPalace
    as third fiction backend with per-wing filter, Settings redesign (8 sections + brass icons +
    Saga's row composables), AI-generated chapter recap with TTS read-aloud, "Who is X?"
    word lookup, public/private repo filter, and the parallel feature bundle (10 PRs in v0.4.51).
  </p>
  <p class="muted">
    See the <a href="https://github.com/jphein/storyvox/wiki">wiki</a> for per-feature documentation,
    or <a href="architecture/">how the modules fit together</a>.
  </p>
</section>

<footer class="site-footer">
  <p>
    storyvox is licensed under the
    <a href="https://github.com/jphein/storyvox/blob/main/LICENSE">GNU General Public License v3.0</a>.
    Built by <a href="https://github.com/jphein">JP Hein</a>
    with teams of <a href="https://www.anthropic.com/claude-code">Claude Code</a> agents.
  </p>
</footer>
