import { useEffect } from 'react';
import { resolveDocumentTitle, SITE_NAME } from '../utils/documentTitle';

const normalize = () => {
  if (typeof document === 'undefined') {
    return;
  }

  // Umi may reset the title to the site default after a nested route mounts. Restore the
  // route-derived title, but never override a page-specific one (for example the 404 page).
  if (!document.title || document.title === SITE_NAME) {
    const title = resolveDocumentTitle(window.location.pathname);
    if (title !== SITE_NAME) {
      document.title = title;
    }
  }

  // ProLayout renders an empty outer <header> around the real global header; two banner
  // landmarks confuse screen readers. Mark the empty wrapper as presentational.
  document.querySelectorAll('header.ant-layout-header').forEach((header) => {
    if (!header.textContent?.trim()) {
      header.setAttribute('role', 'presentation');
    }
  });

  // Ant Design PageContainer renders its title as a span. When the page has no other heading,
  // expose it as the page's level-1 heading without changing the visual markup.
  const pageTitle = document.querySelector('.ant-page-header-heading-title');
  if (pageTitle) {
    const hasVisibleH1 = Array.from(document.querySelectorAll('h1')).some(
      (heading) => heading.getClientRects().length > 0,
    );
    if (!hasVisibleH1 && !pageTitle.hasAttribute('role')) {
      pageTitle.setAttribute('role', 'heading');
      pageTitle.setAttribute('aria-level', '1');
    } else if (hasVisibleH1 && pageTitle.hasAttribute('role')) {
      // The page rendered its own h1 after data loaded; avoid two level-1 headings.
      pageTitle.removeAttribute('role');
      pageTitle.removeAttribute('aria-level');
    }
  }
};

/**
 * Keeps landmark/heading semantics correct across Ant Design Pro layout updates without
 * forking every page header.
 */
const AccessibilityNormalizer: React.FC = () => {
  useEffect(() => {
    normalize();

    let frame = 0;
    const observer = new MutationObserver(() => {
      if (frame) {
        return;
      }
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        normalize();
      });
    });
    observer.observe(document.body, { childList: true, subtree: true });

    return () => {
      if (frame) {
        window.cancelAnimationFrame(frame);
      }
      observer.disconnect();
    };
  }, []);

  return null;
};

export default AccessibilityNormalizer;
