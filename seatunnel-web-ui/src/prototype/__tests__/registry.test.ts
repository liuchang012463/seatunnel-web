import routes from '../../../config/routes';
import { prototypePageRegistry } from '../registry';
import {
  atomicRequirementIds,
  requirementParents,
  requirementRelations,
} from '../requirements';

describe('data ingestion prototype registry', () => {
  it('contains exactly six first-level menus and twenty second-level pages', () => {
    expect(
      new Set(prototypePageRegistry.map(({ firstMenu }) => firstMenu)).size,
    ).toBe(6);
    expect(prototypePageRegistry).toHaveLength(20);
    expect(new Set(prototypePageRegistry.map(({ route }) => route)).size).toBe(
      20,
    );
  });

  it('maps all 55 atomic requirements and has no orphan page', () => {
    expect(requirementRelations).toHaveLength(55);
    expect(new Set(atomicRequirementIds).size).toBe(55);
    expect(Object.keys(requirementParents)).toHaveLength(21);

    const mappedIds = new Set(
      prototypePageRegistry.flatMap(({ requirementIds }) => requirementIds),
    );
    expect(atomicRequirementIds.filter((id) => !mappedIds.has(id))).toEqual([]);
    expect(
      prototypePageRegistry.filter(({ requirementIds }) => !requirementIds.length),
    ).toEqual([]);
  });

  it('declares technical module, implementation status and source for every page', () => {
    prototypePageRegistry.forEach((page) => {
      expect(page.technicalModules.length).toBeGreaterThan(0);
      expect(page.implementationStatus).toMatch(
        /^(REUSE|ADAPT|INTEGRATE|BUILD|LIMITED)$/,
      );
      expect(page.source).toBeTruthy();
    });
  });

  it('registers twenty business routes and eight hidden detail routes', () => {
    const businessPaths = new Set(
      prototypePageRegistry.map(({ route }) => route),
    );
    const routeList = routes as any[];
    expect(
      routeList.filter(({ path }) => businessPaths.has(path)),
    ).toHaveLength(20);
    expect(
      routeList.filter(({ path }) => /:id\/(detail|config\/)/.test(path || '')),
    ).toHaveLength(8);
  });

  it('supports graph filter inputs and parent expansion data', () => {
    const mod005Pages = prototypePageRegistry.filter(({ technicalModules }) =>
      technicalModules.includes('MOD-005'),
    );
    expect(mod005Pages.map(({ id }) => id)).toEqual(
      expect.arrayContaining(['client', 'bi', 'metrics', 'alarm', 'diagnostics']),
    );
    expect(
      requirementRelations.filter(({ parentId }) => parentId === 'F-01'),
    ).toHaveLength(7);
  });
});
