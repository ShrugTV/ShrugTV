// Theme before the first paint (no flash): ?theme=light|dark, otherwise the saved choice
(function () {
  const q = new URLSearchParams(location.search).get('theme');
  let t = q === 'light' || q === 'dark' ? q : null;
  try { if (t) localStorage.setItem('shrugtv.theme', t); else t = localStorage.getItem('shrugtv.theme'); } catch (e) { /* private mode */ }
  document.documentElement.dataset.theme = t === 'light' ? 'light' : 'dark';
})();
