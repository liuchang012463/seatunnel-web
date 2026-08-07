import {
  applyLayoutVisibility,
  isCrossOriginIframe,
  shouldHideLayout,
} from '../iframeLayout';

describe('iframe layout route prefix', () => {
  it.each([
    '/iframe',
    '/iframe/',
    '/iframe/data-source',
    '/iframe/sync/batch-link-up/1/detail?from=list',
  ])('hides the app chrome for route %s', (pathname) => {
    expect(shouldHideLayout(pathname)).toBe(true);
  });

  it.each(['/data-source', '/sync/batch-link-up/1/detail', '/iframe-data-source'])(
    'keeps the app chrome for route %s',
    (pathname) => {
      expect(shouldHideLayout(pathname)).toBe(false);
    },
  );

  it('ignores the deprecated hideMenu query switch', () => {
    expect(shouldHideLayout('/data-source?hideMenu=1')).toBe(false);
  });

  it('marks the document so fixed page actions also use zero chrome offsets', () => {
    applyLayoutVisibility(true);
    expect(document.documentElement.dataset.hideSidebar).toBe('true');
    expect(document.documentElement.dataset.hideHeader).toBe('true');
  });

  it('detects a cross-origin iframe when the parent origin is inaccessible', () => {
    const context = {
      self: {},
      top: {
        get location(): { origin: string } {
          throw new DOMException('Blocked by same-origin policy');
        },
      },
      location: { origin: 'https://seatunnel.example.com' },
    };

    expect(isCrossOriginIframe(context)).toBe(true);
  });

  it('keeps a same-origin iframe on the regular cookie policy', () => {
    const context = {
      self: {},
      top: {
        location: { origin: 'http://localhost:8000' },
      },
      location: { origin: 'http://localhost:8000' },
    };

    expect(isCrossOriginIframe(context)).toBe(false);
  });
});
