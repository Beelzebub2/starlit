'use strict';

// Talks to setup.cpp over chrome.webview: the page sends "command|arg|..."
// strings and receives JSON messages (init, dir, progress, done, error, shown).
(() => {
  const params = new URLSearchParams(window.location.search);
  // ?preview opens the page in a plain browser with a fake host, for design work.
  const host = params.has('preview') ? null : window.chrome && window.chrome.webview;
  const $ = (id) => document.getElementById(id);
  const app = $('app');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const SVG = 'http://www.w3.org/2000/svg';
  let send = (...parts) => host && host.postMessage(parts.join('|'));

  // Install and update put every option on one page, so each flow has three stops.
  const RAILS = {
    install: [['setup', 'Setup'], ['progress', 'Install'], ['done', 'Done']],
    update: [['review', 'Review'], ['progress', 'Update'], ['done', 'Done']],
    uninstall: [['confirm', 'Confirm'], ['progress', 'Remove'], ['done', 'Done']],
  };

  const PHASES = {
    downloading: 'Downloading the TV Installer from GitHub…',
    'unpacking-tv': 'Unpacking TV Installer files…',
    idle: 'Getting ready…',
    closing: 'Closing the app…',
    extracting: 'Unpacking files…',
    legacy: 'Removing the previous version…',
    installing: 'Installing Starlit files…',
    'installing-tv': 'Installing Starlit and the TV Installer…',
    finishing: 'Finishing setup…',
    removing: 'Removing files…',
    done: 'Done',
  };

  // The milestones under the ring. `from` is where each one starts on the
  // engine's 0..1 scale (setup_engine.cpp: kExtractEnd 0.90, uninstall 0.1).
  const MILESTONES = {
    install: [
      { label: 'Prepare', from: 0, phases: ['idle', 'downloading', 'closing'] },
      { label: 'Unpack files', from: 0.02, phases: ['unpacking-tv', 'extracting'] },
      { label: 'Finish up', from: 0.9, phases: ['legacy', 'installing', 'installing-tv', 'finishing'] },
      { label: 'Ready', from: 1, phases: ['done'] },
    ],
    uninstall: [
      { label: 'Prepare', from: 0, phases: ['idle', 'closing'] },
      { label: 'Remove files', from: 0.1, phases: ['removing'] },
      { label: 'Done', from: 1, phases: ['done'] },
    ],
  };

  // Corners and edge midpoints of the Starlit mark (in its 1024 grid), joined
  // in order to draw the constellation; the last point closes the shape.
  const STARS = [[247, 246], [507, 351], [734, 512], [507, 673], [247, 778], [214, 512], [247, 246]];
  const RING = 2 * Math.PI * 104;

  const state = {
    mode: 'install',
    name: 'Starlit',
    publisher: '',
    version: '',
    previousVersion: '',
    dir: '',
    startMenu: true,
    desktop: true,
    tvInstaller: false,
    tvInstallerAvailable: false,
    tvInstallerBundled: false,
    required: 0,
    free: 0,
    running: false,
    sandbox: false,
    damaged: false,
    problem: '',
    removeData: false,
    busy: false,
  };

  const steps = {};
  document.querySelectorAll('.step').forEach((step) => { steps[step.dataset.step] = step; });
  let current = null;
  let currentName = '';
  let introPending = true;

  // ------------------------------------------------------------ helpers

  const mb = (bytes) => `${Math.max(0, Math.round(bytes / 1048576)).toLocaleString('en-US')} MB`;
  const size = (bytes) => (bytes >= 1073741824 ? `${(bytes / 1073741824).toFixed(bytes >= 10737418240 ? 0 : 1)} GB` : mb(bytes));
  const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const clamp = (value) => Math.max(0, Math.min(1, value));
  // Frames stop while the window is hidden or minimized, so never wait on one alone.
  const nextFrame = () => new Promise((resolve) => {
    requestAnimationFrame(() => resolve());
    setTimeout(resolve, 100);
  });

  // Keep the drive and the last two folders, which are the parts people check.
  function shortPath(path) {
    const parts = path.split('\\');
    return parts.length <= 5 ? path : [parts[0], parts[1], '…', ...parts.slice(-2)].join('\\');
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function icon(name, className) {
    const svg = document.createElementNS(SVG, 'svg');
    if (className) svg.setAttribute('class', className);
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS(SVG, 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.appendChild(use);
    return svg;
  }

  function shortcutSummary() {
    if (state.startMenu && state.desktop) return 'Start menu and desktop';
    if (state.startMenu) return 'Start menu';
    if (state.desktop) return 'Desktop';
    return 'None';
  }

  function setNotice(notice, text) {
    notice.hidden = !text;
    notice.querySelector('span').textContent = text || '';
  }

  // ------------------------------------------------------------ steps

  function buildRail() {
    const rail = $('rail');
    rail.textContent = '';
    for (const [id, label] of RAILS[state.mode]) {
      const item = element('li', 'rail-item');
      item.dataset.id = id;
      const line = element('span', 'rail-line');
      line.setAttribute('aria-hidden', 'true');
      line.appendChild(element('span'));
      item.append(line, icon('star', 'rail-star'), element('span', 'rail-label', label));
      rail.appendChild(item);
    }
  }

  function updateRail(name) {
    let key = name === 'error' ? 'progress' : name;
    if (key === 'setup' && state.mode === 'update') key = 'review';
    const items = [...$('rail').querySelectorAll('li')];
    const index = items.findIndex((item) => item.dataset.id === key);
    items.forEach((item, i) => {
      item.classList.toggle('is-past', i < index);
      item.classList.toggle('is-current', i === index);
      if (i === index) item.setAttribute('aria-current', 'step');
      else item.removeAttribute('aria-current');
    });
  }

  function enter(step, direction, baseDelay) {
    const distance = reduceMotion ? 0 : 22;
    step.querySelectorAll('[data-enter]').forEach((part, i) => {
      part.animate(
        [{ opacity: 0, transform: `translateX(${distance * direction}px)` }, { opacity: 1, transform: 'none' }],
        {
          duration: reduceMotion ? 160 : 460,
          delay: baseDelay + (reduceMotion ? 0 : i * 45),
          easing: 'cubic-bezier(.2, .8, .2, 1)',
          fill: 'backwards',
        },
      );
    });
  }

  function show(name, direction = 1, enterDelay = 120) {
    const next = steps[name];
    if (!next || next === current) return;
    const previous = current;
    current = next;
    currentName = name;
    updateRail(name);
    if (previous) {
      previous.style.pointerEvents = 'none';
      const leave = previous.animate(
        [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${(reduceMotion ? 0 : -22) * direction}px)` }],
        { duration: reduceMotion ? 120 : 220, easing: 'cubic-bezier(.4, 0, 1, 1)', fill: 'forwards' },
      );
      leave.onfinish = () => {
        previous.hidden = true;
        previous.style.pointerEvents = '';
        leave.cancel();
      };
    }
    next.hidden = false;
    // Before the window is shown, the intro animation plays the first step in.
    if (!introPending) enter(next, direction, previous ? enterDelay : 0);
    focusPrimary(next);
  }

  function focusPrimary(step) {
    const primary = [...step.querySelectorAll('.button.primary')].find((button) => !button.hidden && !button.disabled);
    // Enter works straight away; the ring only appears once someone uses the keyboard.
    if (primary) primary.focus({ preventScroll: true, focusVisible: false });
  }

  function setBusy(busy) {
    state.busy = busy;
    app.classList.toggle('is-busy', busy);
    $('close').disabled = busy;
  }

  // ------------------------------------------------------------ rendering

  function renderStage() {
    $('brand-name').textContent = `${state.name} ${state.mode === 'uninstall' ? 'Uninstall' : 'Setup'}`;
    $('stage-name').textContent = state.name;
    $('stage-version').textContent = state.version ? `Version ${state.version}` : '';
    $('sandbox-pill').hidden = !state.sandbox;
    const credit = $('stage-credit');
    credit.textContent = '';
    if (state.publisher) credit.append(`Made by ${state.publisher}`, document.createElement('br'));
    credit.append('Based on Nuvio, by Tapframe and friends');
  }

  function spaceProblem() {
    const downloadReserve = state.tvInstaller && !state.tvInstallerBundled ? 256 * 1048576 : 0;
    const needed = state.required + downloadReserve;
    const noSpace = !state.problem && state.free < needed + 64 * 1048576;
    return { needed, noSpace };
  }

  function renderSetup() {
    const update = state.mode === 'update';
    $('setup-title').textContent = `${update ? 'Update' : 'Install'} ${state.name}`;
    $('setup-lead').textContent = update
      ? 'Updating keeps your settings, profiles and library.'
      : 'Browse, organize, and play media from sources you add.';

    const path = $('path-text');
    path.textContent = shortPath(state.dir);
    path.title = state.dir;

    const { needed, noSpace } = spaceProblem();
    const hint = $('space-hint');
    const problem = state.problem || (noSpace ? 'Not enough space for the selected TV Installer.' : '');
    hint.classList.toggle('is-problem', Boolean(problem));
    $('no-admin').hidden = Boolean(problem);
    $('space-text').textContent = problem || `${mb(needed)} needed · ${size(state.free)} free`;

    $('opt-start').setAttribute('aria-pressed', String(state.startMenu));
    $('opt-desktop').setAttribute('aria-pressed', String(state.desktop));

    const tv = $('opt-tv-installer');
    tv.setAttribute('aria-checked', String(state.tvInstaller));
    tv.disabled = !state.tvInstallerAvailable;
    $('tv-installer-row').classList.toggle('is-unavailable', !state.tvInstallerAvailable);
    $('tv-installer-description').textContent = state.tvInstallerBundled
      ? 'Optional Samsung/LG TV tool included in this package. Developer preview, not hardware-verified.'
      : state.tvInstallerAvailable
        ? 'Downloads verified Samsung/LG TV preview from GitHub (~120 MB). Internet and TV developer mode required.'
        : 'This build cannot download a matching TV developer preview.';

    $('setup-back').textContent = update ? 'Back' : 'Cancel';
    const install = $('setup-install');
    install.querySelector('span').textContent = update ? 'Update' : 'Install';
    install.disabled = Boolean(state.problem) || noSpace;
    setNotice($('setup-notice'), state.running
      ? `${state.name} is open. Setup will close it before ${update ? 'updating' : 'installing'}.` : '');
  }

  function renderReview() {
    $('review-title').textContent = `Update ${state.name}`;
    const old = $('version-old');
    old.hidden = !state.previousVersion;
    $('version-arrow').style.display = state.previousVersion ? '' : 'none';
    old.textContent = state.previousVersion ? `${state.previousVersion} installed` : '';
    $('version-new').textContent = state.version || 'New version';
    setNotice($('review-notice'), state.running ? `${state.name} is open. Setup will close it before updating.` : '');

    const list = $('summary-list');
    list.textContent = '';
    const addRow = (iconName, color, label, value, title, problem) => {
      const row = element('div', problem ? 'summary-row is-problem' : 'summary-row');
      const glyph = icon(iconName);
      glyph.style.stroke = color;
      const term = element('dt', '', label);
      const detail = element('dd', '', value);
      if (title) detail.title = title;
      row.append(glyph, term, detail);
      list.appendChild(row);
    };
    addRow('folder', 'var(--lavender)', 'Location', shortPath(state.dir), state.dir);
    addRow('start', 'var(--cyan-soft)', 'Shortcuts', shortcutSummary());
    if (state.tvInstaller && state.tvInstallerAvailable) addRow('tv', 'var(--pink-soft)', 'TV Installer', 'Developer preview');
    if (state.problem) addRow('alert', 'var(--danger-text)', 'Problem', state.problem, '', true);
    $('review-update').disabled = Boolean(state.problem);
  }

  function renderConfirm() {
    $('confirm-title').textContent = `Uninstall ${state.name}`;
    const version = state.version ? ` ${state.version}` : '';
    $('confirm-lead').textContent = `This removes ${state.name}${version}, its shortcuts and its entry in Installed apps.`;
    $('data-label').textContent = `Also delete my ${state.name} data`;
    $('opt-data').setAttribute('aria-checked', String(state.removeData));
    $('data-card').classList.toggle('is-on', state.removeData);
    $('data-warning').hidden = !state.removeData;

    const files = ['App files and shortcuts', 'Installed apps entry'];
    const data = ['Settings and profiles', 'Cache'];
    const fill = (list, items, empty) => {
      list.textContent = '';
      for (const item of items) list.appendChild(element('li', '', item));
      if (!items.length) list.appendChild(element('li', 'is-empty', empty));
    };
    fill($('gone-list'), state.removeData ? files.concat(data) : files, '');
    fill($('kept-list'), state.removeData ? [] : data, 'Nothing');
    $('confirm-uninstall').textContent = state.removeData ? 'Uninstall and delete data' : 'Uninstall';
  }

  function renderDone() {
    const launch = $('done-launch');
    const close = $('done-close');
    const list = $('done-list');
    list.textContent = '';
    if (state.mode === 'uninstall') {
      $('done-title').textContent = `${state.name} is uninstalled`;
      $('done-lead').textContent = state.removeData
        ? `Your ${state.name} data on this PC was deleted too.`
        : 'Your settings, profiles and cache are still on this PC for a reinstall.';
      list.hidden = true;
      launch.hidden = true;
      close.className = 'button primary';
      return;
    }
    const where = state.startMenu && state.desktop ? 'Find it in Start and on your desktop.'
      : state.startMenu ? 'Find it in Start.'
        : state.desktop ? 'Find it on your desktop.'
          : "It's in the folder below.";
    $('done-title').textContent = `${state.name} is ${state.mode === 'update' ? 'updated' : 'installed'}`;
    $('done-launch-label').textContent = `Launch ${state.name}`;
    $('done-lead').textContent = state.mode === 'update' && state.version ? `You're on version ${state.version}. ${where}` : where;

    const addItem = (label, value, title) => {
      const item = element('li');
      const check = element('span', 'done-check');
      check.appendChild(icon('check'));
      const text = element('span', 'done-text');
      const detail = element('span', '', value);
      if (title) detail.title = title;
      text.append(element('span', '', label), detail);
      item.append(check, text);
      list.appendChild(item);
    };
    addItem(state.mode === 'update' ? 'Updated in' : 'Installed to', shortPath(state.dir), state.dir);
    if (state.startMenu || state.desktop) addItem('Shortcuts', shortcutSummary());
    if (state.version) addItem('Version', state.version);
    if (state.tvInstaller && state.tvInstallerAvailable) addItem('Starlit TV Installer', 'Developer preview added');
    list.hidden = false;
  }

  // ------------------------------------------------------------ progress

  const sky = {
    ring: $('ring-fill'),
    comet: $('comet'),
    fill: $('cs-fill'),
    coreGlow: $('cs-core-glow'),
    pen: $('cs-pen'),
    penGlow: $('cs-pen-glow'),
    star: $('cs-star'),
    twinkle: $('cs-twinkle'),
    edges: [],
    nodes: [],
    milestones: [],
  };

  function buildConstellation() {
    const edges = $('cs-edges');
    const nodes = $('cs-nodes');
    for (let i = 0; i < STARS.length - 1; i += 1) {
      const [x1, y1] = STARS[i];
      const [x2, y2] = STARS[i + 1];
      const line = document.createElementNS(SVG, 'line');
      line.setAttribute('x1', x1);
      line.setAttribute('y1', y1);
      line.setAttribute('x2', x2);
      line.setAttribute('y2', y2);
      const length = Math.hypot(x2 - x1, y2 - y1);
      line.style.strokeDasharray = `${length} 2000`;
      line.style.strokeDashoffset = String(length);
      edges.appendChild(line);
      sky.edges.push({ line, length, last: -1 });

      const node = document.createElementNS(SVG, 'g');
      for (const [className, radius] of [['node-glow', 46], ['node-core', 12]]) {
        const circle = document.createElementNS(SVG, 'circle');
        circle.setAttribute('class', className);
        circle.setAttribute('cx', x1);
        circle.setAttribute('cy', y1);
        circle.setAttribute('r', radius);
        node.appendChild(circle);
      }
      nodes.appendChild(node);
      sky.nodes.push({ node, lit: false });
    }
  }

  function buildMilestones() {
    const list = $('phases');
    list.textContent = '';
    sky.milestones = [];
    const milestones = MILESTONES[state.mode === 'uninstall' ? 'uninstall' : 'install'];
    list.dataset.count = String(milestones.length);
    for (const milestone of milestones) {
      const item = element('li', 'phase');
      const line = element('span', 'phase-line');
      line.setAttribute('aria-hidden', 'true');
      line.appendChild(element('span'));
      item.append(line, icon('star'), element('span', '', milestone.label));
      list.appendChild(item);
      sky.milestones.push({ item, line, state: '' });
    }
  }

  const progress = {
    reported: 0, shown: 0, phase: 'idle', done: 0, total: 0, last: 0, running: false, milestone: 0, unpackFrom: 0.02,
  };

  function milestoneIndex(milestones) {
    const index = milestones.findIndex((milestone) => milestone.phases.includes(progress.phase));
    return index < 0 ? 0 : index;
  }

  function paintMilestones() {
    const milestones = MILESTONES[state.mode === 'uninstall' ? 'uninstall' : 'install'];
    // The app can close after the optional archive finishes unpacking.
    progress.milestone = Math.max(progress.milestone, milestoneIndex(milestones));
    const active = progress.milestone;
    sky.milestones.forEach((entry, i) => {
      const next = i < active ? 'is-past' : i === active ? (i === milestones.length - 1 ? 'is-past' : 'is-current') : '';
      if (entry.state !== next) {
        entry.item.className = `phase ${next}`;
        if (i === active) entry.item.setAttribute('aria-current', 'step');
        else entry.item.removeAttribute('aria-current');
        entry.state = next;
      }
      if (i >= milestones.length - 1) return;
      const from = i === 1 && state.mode !== 'uninstall' ? progress.unpackFrom : milestones[i].from;
      const to = i === 0 && state.mode !== 'uninstall' ? progress.unpackFrom : milestones[i + 1].from;
      const fill = i < active ? 1 : i > active ? 0 : clamp((progress.shown - from) / Math.max(0.001, to - from));
      entry.line.style.setProperty('--fill', fill.toFixed(3));
    });
  }

  function paintSky() {
    const p = progress.shown;
    const uninstall = state.mode === 'uninstall';
    sky.ring.style.strokeDashoffset = (RING * (1 - p)).toFixed(2);
    sky.comet.style.transform = `rotate(${(p * 360).toFixed(2)}deg)`;
    sky.comet.style.opacity = state.busy && p > 0.003 && p < 0.997 ? '1' : '0';

    // Installing draws the mark star by star; uninstalling takes it apart in reverse.
    const drawn = uninstall ? 1 - clamp(p / 0.95) : clamp(p / 0.96);
    const q = drawn * sky.edges.length;
    sky.edges.forEach((edge, i) => {
      const offset = Math.round(edge.length * (1 - clamp(q - i)) * 10) / 10;
      if (offset !== edge.last) {
        edge.line.style.strokeDashoffset = String(offset);
        edge.last = offset;
      }
      const lit = drawn > 0.002 && q >= i;
      if (lit !== sky.nodes[i].lit) {
        sky.nodes[i].node.classList.toggle('is-lit', lit);
        sky.nodes[i].lit = lit;
      }
    });

    const drawing = q > 0 && q < sky.edges.length && (state.busy || uninstall);
    if (drawing) {
      const k = Math.min(sky.edges.length - 1, Math.floor(q));
      const f = q - k;
      const x = STARS[k][0] + (STARS[k + 1][0] - STARS[k][0]) * f;
      const y = STARS[k][1] + (STARS[k + 1][1] - STARS[k][1]) * f;
      for (const dot of [sky.pen, sky.penGlow]) {
        dot.setAttribute('cx', x.toFixed(1));
        dot.setAttribute('cy', y.toFixed(1));
      }
    }
    sky.pen.style.opacity = drawing ? '1' : '0';
    sky.penGlow.style.opacity = drawing ? '1' : '0';

    const fill = uninstall ? 0 : clamp((p - 0.96) / 0.04);
    const ease = 1 - Math.pow(1 - (uninstall ? drawn : p), 3);
    sky.fill.style.opacity = fill.toFixed(3);
    $('cs-edges').style.opacity = (1 - fill * 0.75).toFixed(3);
    sky.star.style.transform = `scale(${(0.3 + 0.7 * ease).toFixed(3)}) rotate(${((1 - ease) * -45).toFixed(1)}deg)`;
    sky.star.style.opacity = (0.45 + 0.55 * ease).toFixed(3);
    sky.coreGlow.style.opacity = (0.15 + 0.65 * ease).toFixed(3);
    const twinkle = uninstall ? 0 : clamp((p - 0.97) / 0.03);
    sky.twinkle.style.opacity = twinkle.toFixed(3);
    sky.twinkle.style.transform = `scale(${(0.3 + 0.7 * twinkle).toFixed(3)})`;
  }

  function paintProgress() {
    app.style.setProperty('--p', progress.shown.toFixed(3));
    const percent = Math.round(progress.shown * 100);
    $('progress-percent').textContent = `${percent}%`;
    $('meter').setAttribute('aria-valuenow', String(percent));
    $('progress-bytes').textContent = progress.total && progress.done ? `${mb(progress.done)} of ${mb(progress.total)}` : '';
    paintSky();
    paintMilestones();
  }

  function tick(now) {
    const dt = Math.min(64, now - progress.last);
    progress.last = now;
    // Removing the old MSI reports nothing while it runs; ease forward inside its slice.
    if (progress.phase === 'legacy' && progress.reported < 0.955) {
      progress.reported = Math.min(0.955, progress.reported + dt * 0.000004);
    }
    const target = progress.reported;
    progress.shown += (target - progress.shown) * (1 - Math.exp(-dt / 160));
    if (Math.abs(target - progress.shown) < 0.0004) progress.shown = target;
    paintProgress();
    if (state.busy || progress.shown !== target) requestAnimationFrame(tick);
    else progress.running = false;
  }

  function animateProgress() {
    if (progress.running) return;
    progress.running = true;
    progress.last = performance.now();
    requestAnimationFrame(tick);
  }

  function onProgress(message) {
    progress.reported = Math.max(progress.reported, message.value);
    progress.phase = message.phase;
    progress.done = message.done;
    progress.total = message.total;
    const status = message.phase === 'closing' ? `Closing ${state.name}…` : PHASES[message.phase] || PHASES.idle;
    const statusElement = $('progress-status');
    if (statusElement.textContent !== status) statusElement.textContent = status;
    $('progress-file').textContent = ['downloading', 'unpacking-tv', 'extracting', 'installing', 'installing-tv']
      .includes(message.phase) ? message.file : '';
    animateProgress();
  }

  function begin() {
    if (state.busy) return;
    setBusy(true);
    const unpackFrom = state.tvInstallerAvailable && state.tvInstaller && !state.tvInstallerBundled ? 0.10 : 0.02;
    Object.assign(progress, { reported: 0, shown: 0, phase: 'idle', done: 0, total: 0, milestone: 0, unpackFrom });
    app.classList.remove('is-error', 'is-landed', 'is-complete');
    app.classList.add('is-installing');
    buildMilestones();
    paintProgress();
    const titles = { install: 'Installing', update: 'Updating', uninstall: 'Uninstalling' };
    $('progress-title').textContent = `${titles[state.mode]} ${state.name}`;
    $('progress-status').textContent = PHASES.idle;
    $('progress-file').textContent = '';
    show('progress');
    if (state.mode === 'uninstall') send('uninstall', state.removeData ? '1' : '0');
    else send('install', state.dir, state.startMenu ? '1' : '0', state.desktop ? '1' : '0',
      state.tvInstallerAvailable && state.tvInstaller ? '1' : '0');
  }

  // The finished mark leaves the ring and settles back on the stage.
  function flyHome(from) {
    const logo = $('stage-logo');
    const to = logo.getBoundingClientRect();
    if (!from.width || !to.width) return;
    const dx = from.left + from.width / 2 - (to.left + to.width / 2);
    const dy = from.top + from.height / 2 - (to.top + to.height / 2);
    logo.animate(
      [{ transform: `translate(${dx}px, ${dy}px) scale(${from.width / to.width})`, opacity: 1 }, { transform: 'none', opacity: 1 }],
      { duration: 820, easing: 'cubic-bezier(.65, 0, .35, 1)' },
    );
  }

  async function onDone() {
    setBusy(false);
    progress.phase = 'done';
    progress.reported = 1;
    animateProgress();
    const started = performance.now();
    while (progress.shown < 0.999 && performance.now() - started < 2500) await nextFrame();
    progress.shown = 1;
    paintProgress();
    const uninstall = state.mode === 'uninstall';
    $('progress-status').textContent = PHASES.done;
    if (!uninstall) {
      $('progress-title').textContent = `${state.name} is ${state.mode === 'update' ? 'up to date' : 'ready'}`;
      app.classList.add('is-landed');
    }
    await delay(reduceMotion ? 0 : uninstall ? 420 : 950);
    renderDone();
    const from = !uninstall && !reduceMotion ? $('constellation').getBoundingClientRect() : null;
    app.classList.remove('is-installing');
    // While the mark flies home, hold the text back so it never passes over it.
    show('done', 1, from ? 520 : 120);
    app.classList.add('is-complete');
    if (from) flyHome(from);
  }

  function onError(message, fatal = false) {
    setBusy(false);
    app.classList.remove('is-installing', 'is-landed');
    app.classList.add('is-error');
    const titles = { install: 'Installation stopped', update: 'Update stopped', uninstall: 'Uninstall stopped' };
    $('error-title').textContent = fatal ? "This installer can't run" : titles[state.mode];
    $('error-message').textContent = message;
    $('error-retry').hidden = fatal;
    $('error-options').hidden = fatal || state.mode === 'uninstall';
    $('error-close').className = fatal ? 'button primary' : 'button secondary';
    show('error');
  }

  // ------------------------------------------------------------ host messages

  async function onInit(message) {
    Object.assign(state, message);
    app.dataset.mode = state.mode;
    buildRail();
    renderStage();
    renderSetup();
    renderReview();
    renderConfirm();
    if (state.damaged) onError(state.problem, true);
    else show({ install: 'setup', update: 'review', uninstall: 'confirm' }[state.mode] || 'setup');
    // The window is still hidden here, so no animation frames: wait for the
    // fonts, then ask to be shown.
    try { await document.fonts.ready; } catch { /* fall back to system fonts */ }
    updateRail(currentName);
    send('ready');
  }

  function onShown() {
    if (!introPending) return;
    introPending = false;
    app.classList.remove('is-intro');
    if (current) enter(current, 1, reduceMotion ? 0 : 260);
  }

  function onMessage(message) {
    switch (message.type) {
      case 'init': onInit(message); break;
      case 'shown': onShown(); break;
      case 'dir':
        state.dir = message.dir;
        state.free = message.free;
        state.problem = message.problem;
        renderSetup();
        renderReview();
        break;
      case 'progress': onProgress(message); break;
      case 'done': onDone(); break;
      case 'error': onError(message.message); break;
      default: break;
    }
  }

  // ------------------------------------------------------------ wiring

  buildConstellation();

  $('setup-install').addEventListener('click', begin);
  $('setup-back').addEventListener('click', () => (state.mode === 'update' ? show('review', -1) : send('close')));
  $('browse').addEventListener('click', () => send('browse'));
  $('review-update').addEventListener('click', begin);
  $('review-options').addEventListener('click', () => show('setup'));
  $('review-cancel').addEventListener('click', () => send('close'));
  $('confirm-cancel').addEventListener('click', () => send('close'));
  $('confirm-uninstall').addEventListener('click', begin);
  $('done-launch').addEventListener('click', () => send('launch'));
  $('done-close').addEventListener('click', () => send('close'));
  $('error-close').addEventListener('click', () => send('close'));
  $('error-options').addEventListener('click', () => {
    app.classList.remove('is-error');
    show('setup', -1);
  });
  $('error-retry').addEventListener('click', begin);
  $('error-log').addEventListener('click', () => send('log'));
  $('minimize').addEventListener('click', () => send('minimize'));
  $('close').addEventListener('click', () => send('close'));

  const chips = { 'opt-start': 'startMenu', 'opt-desktop': 'desktop' };
  for (const [id, key] of Object.entries(chips)) {
    $(id).addEventListener('click', () => {
      state[key] = !state[key];
      renderSetup();
      renderReview();
    });
  }

  $('opt-tv-installer').addEventListener('click', () => {
    if ($('opt-tv-installer').disabled) return;
    state.tvInstaller = !state.tvInstaller;
    renderSetup();
    renderReview();
  });

  $('opt-data').addEventListener('click', () => {
    state.removeData = !state.removeData;
    renderConfirm();
  });

  document.querySelectorAll('[data-drag]').forEach((region) => {
    region.addEventListener('mousedown', (event) => {
      if (event.button !== 0 || event.target.closest('button')) return;
      event.preventDefault();
      send('drag');
    });
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !state.busy) send('close');
  });
  window.addEventListener('dragover', (event) => event.preventDefault());
  window.addEventListener('drop', (event) => event.preventDefault());

  if (host) {
    host.addEventListener('message', (event) => onMessage(event.data));
    send('hello');
    return;
  }

  // Fake host for ?preview: &mode=install|update|uninstall
  // &step=setup|options|progress|done|error &running &sandbox &tv-opted &tv-bundled &tv-unavailable &problem
  const mode = params.get('mode') || 'install';
  let timer = 0;
  send = (command) => {
    if (command === 'ready') setTimeout(() => onMessage({ type: 'shown' }), 50);
    if (command === 'browse') {
      onMessage({ type: 'dir', dir: 'D:\\Apps\\Starlit', free: 412e9, problem: '' });
      return;
    }
    if (command !== 'install' && command !== 'uninstall') return;
    let value = 0;
    let phase = 'idle';
    clearInterval(timer);
    timer = setInterval(() => {
      value = Math.min(1, value + 0.006 + Math.random() * 0.006);
      phase = mode === 'uninstall' ? (value < 0.1 ? 'closing' : 'removing')
        : value < 0.03 ? 'closing' : value < 0.9 ? 'extracting' : value < 0.96 && mode === 'update' ? 'legacy' : 'finishing';
      const files = ['runtime\\lib\\modules', 'runtime\\bin\\server\\jvm.dll', 'app\\composeApp-desktop-20a2ba6c5cd9521184e6d2cc2d427acb.jar', 'Starlit.exe'];
      onMessage({ type: 'progress', phase, value, done: Math.min(1, value / 0.9) * 282e6, total: mode === 'uninstall' ? 0 : 282e6, file: files[Math.floor(value * 18) % files.length] });
      if (params.get('step') === 'progress' && value > 0.58) clearInterval(timer);
      else if (value >= 1) {
        clearInterval(timer);
        if (params.get('step') === 'error') onMessage({ type: 'error', message: 'Some files in C:\\Users\\you\\AppData\\Local\\Starlit are in use, so setup couldn\'t replace them. Close Starlit, then select Try again.' });
        else onMessage({ type: 'done' });
      }
    }, 60);
  };
  onMessage({
    type: 'init',
    mode,
    name: params.has('sandbox') ? 'Starlit (setup test)' : 'Starlit',
    publisher: 'Beelzebub2',
    version: '1.30.3',
    previousVersion: mode === 'update' ? '1.30.2' : '',
    dir: 'C:\\Users\\you\\AppData\\Local\\Starlit',
    startMenu: true,
    desktop: true,
    tvInstaller: params.has('tv-opted'),
    tvInstallerAvailable: !params.has('tv-unavailable'),
    tvInstallerBundled: params.has('tv-bundled'),
    required: 282e6,
    free: 128e9,
    running: params.has('running'),
    sandbox: params.has('sandbox'),
    damaged: false,
    problem: params.has('problem') ? 'Setup can\'t write to this folder. Pick another location.' : '',
  });
  const step = params.get('step');
  if (step === 'options' || step === 'setup') setTimeout(() => show('setup'), 600);
  if (step === 'progress' || step === 'done' || step === 'error') setTimeout(begin, 600);
})();
