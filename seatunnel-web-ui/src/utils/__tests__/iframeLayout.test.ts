import {
  applyLayoutVisibility,
  isCrossOriginIframe,
  prefixLayoutPath,
  rewriteInAppLocation,
  shouldHideLayout,
  stripLayoutPrefix,
  withLayoutPrefix,
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

describe('iframe layout path helpers', () => {
  it.each([
    ['/iframe', '/'],
    ['/iframe/', '/'],
    ['/iframe/data-source', '/data-source'],
    ['/iframe/sync/link-up', '/sync/link-up'],
    ['/data-source', '/data-source'],
  ])('strips the layout prefix from %s', (pathname, expected) => {
    expect(stripLayoutPrefix(pathname)).toBe(expected);
  });

  it.each([
    ['/data-source', '/iframe/data-source'],
    ['/sync/link-up?tab=stream', '/iframe/sync/link-up?tab=stream'],
    ['/iframe/data-source', '/iframe/data-source'],
    ['https://example.com/x', 'https://example.com/x'],
    ['relative', 'relative'],
    ['/', '/iframe'],
  ])('prefixes absolute in-app path %s', (path, expected) => {
    expect(prefixLayoutPath(path)).toBe(expected);
  });

  it('only rewrites when the current location is under /iframe', () => {
    expect(withLayoutPrefix('/data-source', '/data-source')).toBe('/data-source');
    expect(withLayoutPrefix('/data-source', '/iframe/sync/link-up')).toBe('/iframe/data-source');
  });

  it('rewrites string and object history targets in iframe mode', () => {
    expect(rewriteInAppLocation('/sync/link-up', '/iframe/data-source')).toBe(
      '/iframe/sync/link-up',
    );
    expect(
      rewriteInAppLocation(
        { pathname: '/sync/link-up', search: '?tab=stream' },
        '/iframe/data-source',
      ),
    ).toEqual({ pathname: '/iframe/sync/link-up', search: '?tab=stream' });
  });

  it('leaves history targets unchanged outside iframe mode', () => {
    expect(rewriteInAppLocation('/sync/link-up', '/data-source')).toBe('/sync/link-up');
    expect(
      rewriteInAppLocation({ pathname: '/sync/link-up' }, '/data-source'),
    ).toEqual({ pathname: '/sync/link-up' });
  });
});
