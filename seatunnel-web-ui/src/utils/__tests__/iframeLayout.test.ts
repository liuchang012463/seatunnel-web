import {
  applySidebarVisibility,
  isCrossOriginIframe,
  shouldHideSidebar,
} from '../iframeLayout';

describe('iframe layout sidebar switch', () => {
  it.each(['1', 'true', 'yes', 'on'])(
    'hides the sidebar for query value %s',
    (value) => {
      expect(shouldHideSidebar(`?hideMenu=${value}`, {})).toBe(true);
    },
  );

  it.each(['0', 'false', 'no', 'off'])(
    'shows the sidebar for query value %s',
    (value) => {
      expect(shouldHideSidebar(`?hideMenu=${value}`, {})).toBe(false);
    },
  );

  it('lets the query switch override the deployment default', () => {
    expect(
      shouldHideSidebar('?hideMenu=0', { UMI_APP_HIDE_SIDEBAR: '1' }),
    ).toBe(false);
  });

  it('uses the Umi environment switch when the query is absent', () => {
    expect(shouldHideSidebar('', { UMI_APP_HIDE_SIDEBAR: 'true' })).toBe(
      true,
    );
  });

  it('marks the document so fixed page actions also use zero sider width', () => {
    applySidebarVisibility(true);
    expect(document.documentElement.dataset.hideSidebar).toBe('true');
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
