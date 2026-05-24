// Initialize mermaid with theme that respects dark/light mode
document.addEventListener('DOMContentLoaded', function() {
  const observer = new MutationObserver(function() {
    const scheme = document.body.getAttribute('data-md-color-scheme');
    const isDark = scheme === 'slate';
    
    if (window.mermaid) {
      window.mermaid.initialize({
        theme: isDark ? 'dark' : 'default',
        themeVariables: isDark ? {
          primaryColor: '#1e1e2e',
          primaryTextColor: '#cdd6f4',
          primaryBorderColor: '#6c6c8a',
          lineColor: '#6c6c8a',
          secondaryColor: '#313244',
          tertiaryColor: '#181825',
          background: '#1e1e2e',
          mainBkg: '#1e1e2e',
          nodeBorder: '#6c6c8a',
          clusterBkg: '#181825',
          clusterBorder: '#45475a',
          titleColor: '#cdd6f4',
          edgeLabelBackground: '#1e1e2e'
        } : {}
      });
    }
  });
  
  observer.observe(document.body, { attributes: true, attributeFilter: ['data-md-color-scheme'] });
});
