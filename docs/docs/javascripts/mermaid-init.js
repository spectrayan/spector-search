// Initialize mermaid with theme that respects dark/light mode
// Runs on initial load AND on theme toggle
(function() {
  function getMermaidThemeConfig() {
    const scheme = document.body.getAttribute('data-md-color-scheme');
    const isDark = scheme === 'slate';

    return {
      startOnLoad: false,
      theme: isDark ? 'dark' : 'default',
      themeVariables: isDark ? {
        primaryColor: '#313244',
        primaryTextColor: '#cdd6f4',
        primaryBorderColor: '#6c6c8a',
        lineColor: '#89b4fa',
        secondaryColor: '#45475a',
        tertiaryColor: '#1e1e2e',
        background: '#1e1e2e',
        mainBkg: '#313244',
        nodeBorder: '#6c6c8a',
        clusterBkg: '#1e1e2e',
        clusterBorder: '#45475a',
        titleColor: '#cdd6f4',
        edgeLabelBackground: '#313244',
        noteTextColor: '#cdd6f4',
        noteBkgColor: '#313244',
        noteBorderColor: '#6c6c8a',
        actorTextColor: '#cdd6f4',
        actorBkg: '#313244',
        actorBorder: '#6c6c8a',
        signalColor: '#cdd6f4',
        signalTextColor: '#cdd6f4',
        labelBoxBkgColor: '#313244',
        labelBoxBorderColor: '#6c6c8a',
        labelTextColor: '#cdd6f4',
        loopTextColor: '#cdd6f4',
        activationBorderColor: '#89b4fa',
        activationBkgColor: '#45475a',
        sequenceNumberColor: '#1e1e2e'
      } : {
        primaryColor: '#dbeafe',
        primaryTextColor: '#1e3a5f',
        primaryBorderColor: '#93c5fd',
        lineColor: '#6366f1',
        secondaryColor: '#f3e8ff',
        tertiaryColor: '#fef3c7'
      }
    };
  }

  function reinitMermaid() {
    if (!window.mermaid) return;

    var config = getMermaidThemeConfig();
    window.mermaid.initialize(config);

    // Re-render all mermaid diagrams on theme change
    document.querySelectorAll('.mermaid[data-processed]').forEach(function(el) {
      el.removeAttribute('data-processed');
    });

    // For mkdocs-material, re-render via mermaid API
    document.querySelectorAll('pre.mermaid').forEach(function(el) {
      var code = el.querySelector('code');
      if (code) {
        var container = document.createElement('div');
        container.className = 'mermaid';
        container.textContent = code.textContent;
        el.parentNode.replaceChild(container, el);
      }
    });

    window.mermaid.run();
  }

  // Initial render on DOMContentLoaded
  document.addEventListener('DOMContentLoaded', function() {
    // Observe theme toggle
    var observer = new MutationObserver(function() {
      reinitMermaid();
    });
    observer.observe(document.body, { attributes: true, attributeFilter: ['data-md-color-scheme'] });
  });
})();
