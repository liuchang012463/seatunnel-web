import {
  buildLoginPath,
  resolvePostLoginRedirect,
} from '../authRedirect';

describe('authentication redirect', () => {
  it('preserves the iframe page query and hash through login', () => {
    const loginPath = buildLoginPath({
      pathname: '/sync/file-link-up',
      search: '?hideMenu=1&source=portal',
      hash: '#tasks',
    });

    expect(loginPath).toBe(
      '/login?redirect=%2Fsync%2Ffile-link-up%3FhideMenu%3D1%26source%3Dportal%23tasks',
    );
    expect(
      resolvePostLoginRedirect(`https://seatunnel.example.com${loginPath}`),
    ).toBe('/sync/file-link-up?hideMenu=1&source=portal#tasks');
  });

  it('rejects a cross-origin post-login redirect', () => {
    expect(
      resolvePostLoginRedirect(
        'https://seatunnel.example.com/login?redirect=https%3A%2F%2Fevil.example%2F',
      ),
    ).toBe('/');
  });
});
