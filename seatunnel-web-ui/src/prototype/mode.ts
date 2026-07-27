export const isPrototypeMode =
  process.env.REACT_APP_PROTOTYPE === '1' ||
  process.env.UMI_APP_PROTOTYPE === '1' ||
  (typeof window !== 'undefined' &&
    new URLSearchParams(window.location.search).get('prototype') === '1');
